"""طبقة موحّدة لمزودي الذكاء الاصطناعي.

الصيغة الداخلية للرسائل:
  {"role": "user"|"assistant"|"tool", "content": str,
   "tool_calls": [{"id","name","args","sig"?}],   # للمساعد
   "tool_call_id": str, "name": str}             # لنتيجة أداة
"""
from __future__ import annotations

import asyncio
import base64
import json
import random
import uuid
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

import httpx

from .config import Settings


class AIError(Exception):
    def __init__(self, message: str, retryable: bool = False, status: int = 0):
        super().__init__(message)
        self.retryable = retryable
        self.status = status


@dataclass
class ToolCall:
    id: str
    name: str
    args: dict
    sig: str | None = None  # توقيع تفكير Gemini يجب إعادته كما هو


@dataclass
class AIResponse:
    text: str
    tool_calls: list[ToolCall] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0
    provider: str = ""
    model: str = ""


DEFAULT_MODELS = {
    "gemini": "gemini-flash-latest",
    "openai": "gpt-4o-mini",
    "anthropic": "claude-haiku-4-5",
    "openrouter": "openai/gpt-4o-mini",
    "custom": "",
    "fake": "fake",
}

# أسعار تقديرية بالدولار لكل مليون رمز (إدخال، إخراج) — للتقدير فقط
PRICES = {
    "gemini-flash-latest": (0.30, 2.50), "gemini-2.5-flash": (0.30, 2.50), "gemini-2.5-pro": (1.25, 10.0),
    "gpt-4o-mini": (0.15, 0.60), "gpt-4o": (2.50, 10.0), "gpt-4.1-mini": (0.40, 1.60),
    "claude-haiku-4-5": (1.0, 5.0), "claude-sonnet-4-5": (3.0, 15.0),
}


def estimate_cost(model: str, tin: int, tout: int) -> float:
    key = model.split("/")[-1]
    pin, pout = PRICES.get(key, (1.0, 4.0))
    return round(tin / 1e6 * pin + tout / 1e6 * pout, 6)


ProviderFn = Callable[[str, list[dict], list[dict]], Awaitable[AIResponse]]


class AIRouter:
    """يرسل الطلب للمزوّد الأساسي مع إعادة محاولة محدودة، ثم للمزوّد الاحتياطي إن وُجد."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.s = settings
        self.http = client or httpx.AsyncClient(timeout=httpx.Timeout(90, connect=15))
        self.fake_script: list[AIResponse] | None = None  # للاختبارات
        self.fake_handler: Callable[[list[dict], list[dict]], AIResponse] | None = None

    # ——— الاختيار
    def configured(self, provider: str | None = None) -> bool:
        p = provider or self.s.ai_provider
        return bool(self._key(p)) or p == "fake" or (p == "custom" and bool(self.s.custom_base_url))

    def _key(self, p: str) -> str:
        return {
            "gemini": self.s.gemini_api_key, "openai": self.s.openai_api_key,
            "anthropic": self.s.anthropic_api_key, "openrouter": self.s.openrouter_api_key,
            "custom": self.s.custom_api_key,
        }.get(p, "")

    def model_for(self, p: str) -> str:
        if p == self.s.ai_provider and self.s.ai_model:
            return self.s.ai_model
        return DEFAULT_MODELS.get(p, "")

    async def chat(self, system: str, messages: list[dict], tools: list[dict]) -> AIResponse:
        providers = [self.s.ai_provider]
        fb = self.s.ai_fallback_provider
        if fb and fb != self.s.ai_provider and self.configured(fb):
            providers.append(fb)
        last: AIError | None = None
        for p in providers:
            if not self.configured(p):
                last = AIError(f"المزوّد {p} غير مُعدّ: أضف مفتاحه في ملف .env")
                continue
            try:
                return await self._with_retry(lambda: self._call(p, system, messages, tools))
            except AIError as e:
                last = e
        raise last or AIError("لا يوجد مزوّد ذكاء اصطناعي مُعدّ")

    async def _with_retry(self, fn: Callable[[], Awaitable[AIResponse]], attempts: int = 3) -> AIResponse:
        delay = 1.5
        for i in range(attempts):
            try:
                return await fn()
            except AIError as e:
                if not e.retryable or i == attempts - 1:
                    raise
            except (httpx.TimeoutException, httpx.TransportError) as e:
                if i == attempts - 1:
                    raise AIError(f"تعذّر الاتصال بالمزوّد: {type(e).__name__}", retryable=True)
            await asyncio.sleep(delay + random.random())
            delay *= 2
        raise AIError("فشلت المحاولات")

    async def _call(self, p: str, system: str, messages: list[dict], tools: list[dict]) -> AIResponse:
        model = self.model_for(p)
        if p == "fake":
            return self._fake(messages, tools)
        if p == "gemini":
            return await self._gemini(model, system, messages, tools)
        if p == "anthropic":
            return await self._anthropic(model, system, messages, tools)
        base = {
            "openai": "https://api.openai.com/v1",
            "openrouter": "https://openrouter.ai/api/v1",
            "custom": self.s.custom_base_url.rstrip("/"),
        }.get(p)
        if not base:
            raise AIError(f"مزوّد غير معروف: {p}")
        return await self._openai(p, base, model, system, messages, tools)

    # ——— مزوّد وهمي للاختبارات (لا يُستخدم في التشغيل الحقيقي)
    def _fake(self, messages: list[dict], tools: list[dict]) -> AIResponse:
        if self.fake_handler:
            r = self.fake_handler(messages, tools)
        elif self.fake_script:
            r = self.fake_script.pop(0)
        else:
            r = AIResponse(text="تم.")
        r.provider, r.model = "fake", "fake"
        return r

    @staticmethod
    def _check(resp: httpx.Response, who: str) -> dict:
        if resp.status_code >= 400:
            retry = resp.status_code in (408, 409, 429) or resp.status_code >= 500
            body = resp.text[:400]
            raise AIError(f"{who} {resp.status_code}: {body}", retryable=retry, status=resp.status_code)
        try:
            return resp.json()
        except ValueError:
            raise AIError(f"{who}: رد غير صالح", retryable=True)

    # ——— OpenAI Chat Completions (وأي خدمة متوافقة)
    async def _openai(self, p: str, base: str, model: str, system: str, messages: list[dict],
                      tools: list[dict]) -> AIResponse:
        msgs: list[dict] = [{"role": "system", "content": system}]
        for m in messages:
            if m["role"] == "tool":
                msgs.append({"role": "tool", "tool_call_id": m["tool_call_id"], "content": m["content"]})
            elif m["role"] == "assistant" and m.get("tool_calls"):
                msgs.append({
                    "role": "assistant", "content": m.get("content") or None,
                    "tool_calls": [{"id": c["id"], "type": "function",
                                    "function": {"name": c["name"], "arguments": json.dumps(c["args"], ensure_ascii=False)}}
                                   for c in m["tool_calls"]],
                })
            else:
                msgs.append({"role": m["role"], "content": m["content"]})
        body: dict[str, Any] = {"model": model, "messages": msgs}
        if tools:
            body["tools"] = [{"type": "function", "function": t} for t in tools]
        headers = {"Authorization": f"Bearer {self._key(p)}"}
        if p == "openrouter":
            headers["X-Title"] = "Rafiq"
        data = self._check(await self.http.post(f"{base}/chat/completions", json=body, headers=headers), p)
        choice = (data.get("choices") or [{}])[0].get("message", {})
        calls = []
        for c in choice.get("tool_calls") or []:
            fn = c.get("function", {})
            try:
                args = json.loads(fn.get("arguments") or "{}")
            except ValueError:
                args = {}
            calls.append(ToolCall(id=c.get("id") or uuid.uuid4().hex[:12], name=fn.get("name", ""), args=args))
        u = data.get("usage") or {}
        return AIResponse(text=choice.get("content") or "", tool_calls=calls,
                          input_tokens=u.get("prompt_tokens", 0), output_tokens=u.get("completion_tokens", 0),
                          provider=p, model=model)

    # ——— Anthropic Messages
    async def _anthropic(self, model: str, system: str, messages: list[dict], tools: list[dict]) -> AIResponse:
        out: list[dict] = []
        for m in messages:
            if m["role"] == "tool":
                block = {"type": "tool_result", "tool_use_id": m["tool_call_id"], "content": m["content"]}
                if out and out[-1]["role"] == "user" and isinstance(out[-1]["content"], list):
                    out[-1]["content"].append(block)
                else:
                    out.append({"role": "user", "content": [block]})
            elif m["role"] == "assistant":
                blocks: list[dict] = []
                if m.get("content"):
                    blocks.append({"type": "text", "text": m["content"]})
                for c in m.get("tool_calls") or []:
                    blocks.append({"type": "tool_use", "id": c["id"], "name": c["name"], "input": c["args"]})
                out.append({"role": "assistant", "content": blocks or [{"type": "text", "text": "…"}]})
            else:
                out.append({"role": "user", "content": m["content"]})
        body: dict[str, Any] = {"model": model, "max_tokens": 2048, "system": system, "messages": out}
        if tools:
            body["tools"] = [{"name": t["name"], "description": t["description"], "input_schema": t["parameters"]}
                             for t in tools]
        headers = {"x-api-key": self._key("anthropic"), "anthropic-version": "2023-06-01"}
        data = self._check(await self.http.post("https://api.anthropic.com/v1/messages", json=body, headers=headers),
                           "anthropic")
        text, calls = [], []
        for b in data.get("content", []):
            if b.get("type") == "text":
                text.append(b.get("text", ""))
            elif b.get("type") == "tool_use":
                calls.append(ToolCall(id=b["id"], name=b["name"], args=b.get("input") or {}))
        u = data.get("usage") or {}
        return AIResponse(text="\n".join(text), tool_calls=calls, input_tokens=u.get("input_tokens", 0),
                          output_tokens=u.get("output_tokens", 0), provider="anthropic", model=model)

    # ——— Gemini generateContent
    async def _gemini(self, model: str, system: str, messages: list[dict], tools: list[dict]) -> AIResponse:
        contents: list[dict] = []
        for m in messages:
            if m["role"] == "tool":
                part = {"functionResponse": {"name": m.get("name", "tool"), "response": {"result": m["content"]}}}
                if contents and contents[-1]["role"] == "user" and "functionResponse" in contents[-1]["parts"][0]:
                    contents[-1]["parts"].append(part)
                else:
                    contents.append({"role": "user", "parts": [part]})
            elif m["role"] == "assistant":
                parts: list[dict] = []
                if m.get("content"):
                    parts.append({"text": m["content"]})
                for c in m.get("tool_calls") or []:
                    p: dict[str, Any] = {"functionCall": {"name": c["name"], "args": c["args"]}}
                    if c.get("sig"):
                        p["thoughtSignature"] = c["sig"]
                    parts.append(p)
                contents.append({"role": "model", "parts": parts or [{"text": "…"}]})
            else:
                parts = [{"text": m["content"]}]
                for att in m.get("attachments") or []:
                    parts.append({"inline_data": {"mime_type": att["mime"], "data": att["b64"]}})
                contents.append({"role": "user", "parts": parts})
        body: dict[str, Any] = {"contents": contents, "systemInstruction": {"parts": [{"text": system}]}}
        if tools:
            body["tools"] = [{"functionDeclarations": [_gemini_schema(t) for t in tools]}]
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        data = self._check(await self.http.post(url, json=body, headers={"x-goog-api-key": self._key("gemini")}),
                           "gemini")
        cands = data.get("candidates") or []
        if not cands:
            reason = (data.get("promptFeedback") or {}).get("blockReason", "رد فارغ")
            raise AIError(f"gemini: {reason}", retryable=reason == "رد فارغ")
        text, calls = [], []
        for part in (cands[0].get("content") or {}).get("parts", []):
            if part.get("thought"):
                continue
            if "text" in part:
                text.append(part["text"])
            if "functionCall" in part:
                fc = part["functionCall"]
                calls.append(ToolCall(id=uuid.uuid4().hex[:12], name=fc.get("name", ""), args=fc.get("args") or {},
                                      sig=part.get("thoughtSignature")))
        u = data.get("usageMetadata") or {}
        return AIResponse(text="".join(text), tool_calls=calls, input_tokens=u.get("promptTokenCount", 0),
                          output_tokens=u.get("candidatesTokenCount", 0), provider="gemini", model=model)

    # ——— الصوت
    async def transcribe(self, audio: bytes, mime: str = "audio/ogg") -> str:
        """تحويل الصوت إلى نص: Gemini أو OpenAI Whisper حسب المفاتيح المتاحة."""
        if self.s.gemini_api_key:
            body = {"contents": [{"role": "user", "parts": [
                {"text": "حوّل هذا التسجيل الصوتي إلى نص حرفيًا بنفس لغته (غالبًا عربية). أعد النص فقط."},
                {"inline_data": {"mime_type": mime, "data": base64.b64encode(audio).decode()}}]}]}
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{DEFAULT_MODELS['gemini']}:generateContent"
            data = self._check(await self.http.post(url, json=body, headers={"x-goog-api-key": self.s.gemini_api_key}),
                               "gemini-stt")
            parts = ((data.get("candidates") or [{}])[0].get("content") or {}).get("parts", [])
            return "".join(p.get("text", "") for p in parts).strip()
        if self.s.openai_api_key:
            files = {"file": ("voice.ogg", audio, mime)}
            r = await self.http.post("https://api.openai.com/v1/audio/transcriptions",
                                     headers={"Authorization": f"Bearer {self.s.openai_api_key}"},
                                     data={"model": "whisper-1"}, files=files)
            return self._check(r, "whisper").get("text", "").strip()
        raise AIError("تحويل الصوت إلى نص يحتاج مفتاح Gemini أو OpenAI")

    async def speak(self, text: str) -> tuple[bytes, str] | None:
        """تحويل النص إلى كلام. يعيد (بيانات، نوع) أو None إن لم يتوفر مزوّد."""
        text = text[:1500]
        if self.s.openai_api_key:
            r = await self.http.post("https://api.openai.com/v1/audio/speech",
                                     headers={"Authorization": f"Bearer {self.s.openai_api_key}"},
                                     json={"model": "gpt-4o-mini-tts", "voice": "onyx", "input": text,
                                           "response_format": "opus"})
            if r.status_code < 400:
                return r.content, "audio/ogg"
        if self.s.gemini_api_key:
            body = {"contents": [{"parts": [{"text": text}]}],
                    "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {
                        "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Charon"}}}}}
            url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"
            r = await self.http.post(url, json=body, headers={"x-goog-api-key": self.s.gemini_api_key})
            if r.status_code < 400:
                parts = ((r.json().get("candidates") or [{}])[0].get("content") or {}).get("parts", [])
                for p in parts:
                    inline = p.get("inlineData") or p.get("inline_data")
                    if inline:
                        return pcm_to_wav(base64.b64decode(inline["data"])), "audio/wav"
        return None

    async def aclose(self) -> None:
        await self.http.aclose()


def pcm_to_wav(pcm: bytes, rate: int = 24000, channels: int = 1, width: int = 2) -> bytes:
    import io
    import wave
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


def _gemini_schema(t: dict) -> dict:
    def clean(s: Any) -> Any:
        if isinstance(s, dict):
            return {k: clean(v) for k, v in s.items() if k not in ("additionalProperties", "default", "$schema")}
        if isinstance(s, list):
            return [clean(x) for x in s]
        return s
    params = clean(t.get("parameters") or {"type": "object", "properties": {}})
    if not params.get("properties"):
        return {"name": t["name"], "description": t["description"]}
    return {"name": t["name"], "description": t["description"], "parameters": params}
