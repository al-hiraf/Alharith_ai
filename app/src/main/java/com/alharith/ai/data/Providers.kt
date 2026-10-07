package com.alharith.ai.data

/**
 * مزوّدو الذكاء الاصطناعي المدعومون.
 * kind: gemini (واجهة Google) | claude (واجهة Anthropic) | openai (أي واجهة متوافقة مع OpenAI Chat Completions)
 */
data class Provider(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val keyUrl: String,
    val models: List<Pair<String, String>>,
    val fastModel: String? = null,
    val note: String = "",
    val keyOptional: Boolean = false
)

object Providers {

    val ALL: List<Provider> = listOf(
        Provider(
            "gemini", "Google Gemini", "gemini", "", "aistudio.google.com ← Get API key",
            listOf(
                "gemini-flash-latest" to "Gemini Flash (أحدث) — مُوصى به",
                "gemini-flash-lite-latest" to "Gemini Flash-Lite — الأسرع",
                "gemini-pro-latest" to "Gemini Pro — الأقوى",
                "gemini-2.5-flash" to "Gemini 2.5 Flash",
                "gemini-2.5-pro" to "Gemini 2.5 Pro"
            ),
            fastModel = "gemini-flash-lite-latest", note = "فيه حد مجاني يومي."
        ),
        Provider(
            "openai", "OpenAI (ChatGPT)", "openai", "https://api.openai.com/v1", "platform.openai.com ← API keys",
            listOf(
                "gpt-4.1" to "GPT-4.1 — سريع وذكي",
                "gpt-4.1-mini" to "GPT-4.1 mini — أرخص",
                "gpt-5-mini" to "GPT-5 mini",
                "gpt-5" to "GPT-5 — الأقوى",
                "gpt-4o" to "GPT-4o",
                "gpt-4o-mini" to "GPT-4o mini"
            ),
            fastModel = "gpt-4.1-mini", note = "يحتاج رصيدًا مدفوعًا في Billing."
        ),
        Provider(
            "claude", "Anthropic Claude", "claude", "", "console.anthropic.com ← API Keys",
            listOf(
                "claude-sonnet-5-5" to "Claude Sonnet 5.5 — مُوصى به",
                "claude-haiku-4-5-20251001" to "Claude Haiku 4.5 — الأسرع",
                "claude-opus-5-5" to "Claude Opus 5.5 — الأقوى"
            ),
            fastModel = "claude-haiku-4-5-20251001"
        ),
        Provider(
            "openrouter", "OpenRouter (مئات النماذج بمفتاح واحد)", "openai", "https://openrouter.ai/api/v1",
            "openrouter.ai ← Keys",
            listOf(
                "openrouter/auto" to "اختيار تلقائي لأفضل نموذج",
                "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
                "openai/gpt-4.1-mini" to "GPT-4.1 mini",
                "anthropic/claude-sonnet-4" to "Claude Sonnet 4",
                "deepseek/deepseek-chat" to "DeepSeek V3",
                "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B",
                "qwen/qwen-2.5-72b-instruct" to "Qwen 2.5 72B"
            ),
            note = "مفتاح واحد يفتح نماذج كل الشركات. اكتب اسم أي نموذج من موقعهم."
        ),
        Provider(
            "groq", "Groq (فائق السرعة)", "openai", "https://api.groq.com/openai/v1", "console.groq.com ← API Keys",
            listOf(
                "llama-3.3-70b-versatile" to "Llama 3.3 70B",
                "openai/gpt-oss-120b" to "GPT-OSS 120B",
                "qwen/qwen3-32b" to "Qwen3 32B",
                "llama-3.1-8b-instant" to "Llama 3.1 8B — الأسرع"
            ),
            fastModel = "llama-3.1-8b-instant", note = "فيه حد مجاني."
        ),
        Provider(
            "deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "platform.deepseek.com ← API keys",
            listOf("deepseek-chat" to "DeepSeek Chat (V3)", "deepseek-reasoner" to "DeepSeek Reasoner (R1)"),
            note = "رخيص جدًا."
        ),
        Provider(
            "mistral", "Mistral AI", "openai", "https://api.mistral.ai/v1", "console.mistral.ai ← API Keys",
            listOf(
                "mistral-large-latest" to "Mistral Large",
                "mistral-medium-latest" to "Mistral Medium",
                "mistral-small-latest" to "Mistral Small — أسرع"
            ),
            fastModel = "mistral-small-latest", note = "فيه حد مجاني."
        ),
        Provider(
            "xai", "xAI Grok", "openai", "https://api.x.ai/v1", "console.x.ai ← API Keys",
            listOf("grok-4" to "Grok 4", "grok-3" to "Grok 3", "grok-3-mini" to "Grok 3 mini — أسرع"),
            fastModel = "grok-3-mini"
        ),
        Provider(
            "qwen", "Alibaba Qwen (DashScope)", "openai", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "alibabacloud.com ← Model Studio ← API Key",
            listOf("qwen-plus" to "Qwen Plus", "qwen-max" to "Qwen Max — الأقوى", "qwen-turbo" to "Qwen Turbo — الأسرع"),
            fastModel = "qwen-turbo"
        ),
        Provider(
            "moonshot", "Moonshot Kimi", "openai", "https://api.moonshot.ai/v1", "platform.moonshot.ai ← API Keys",
            listOf("kimi-k2-0905-preview" to "Kimi K2", "moonshot-v1-32k" to "Moonshot v1 32k")
        ),
        Provider(
            "zai", "Z.ai GLM (Zhipu)", "openai", "https://api.z.ai/api/paas/v4", "z.ai ← API Keys",
            listOf("glm-4.5" to "GLM-4.5", "glm-4.5-air" to "GLM-4.5 Air — أسرع"),
            fastModel = "glm-4.5-air"
        ),
        Provider(
            "together", "Together AI", "openai", "https://api.together.xyz/v1", "api.together.ai ← API Keys",
            listOf(
                "meta-llama/Llama-3.3-70B-Instruct-Turbo" to "Llama 3.3 70B Turbo",
                "Qwen/Qwen2.5-72B-Instruct-Turbo" to "Qwen 2.5 72B Turbo",
                "deepseek-ai/DeepSeek-V3" to "DeepSeek V3"
            )
        ),
        Provider(
            "fireworks", "Fireworks AI", "openai", "https://api.fireworks.ai/inference/v1", "fireworks.ai ← API Keys",
            listOf(
                "accounts/fireworks/models/llama-v3p3-70b-instruct" to "Llama 3.3 70B",
                "accounts/fireworks/models/deepseek-v3" to "DeepSeek V3",
                "accounts/fireworks/models/qwen2p5-72b-instruct" to "Qwen 2.5 72B"
            )
        ),
        Provider(
            "cerebras", "Cerebras (فائق السرعة)", "openai", "https://api.cerebras.ai/v1", "cloud.cerebras.ai ← API Keys",
            listOf("llama-3.3-70b" to "Llama 3.3 70B", "gpt-oss-120b" to "GPT-OSS 120B", "qwen-3-32b" to "Qwen3 32B"),
            note = "فيه حد مجاني."
        ),
        Provider(
            "cohere", "Cohere", "openai", "https://api.cohere.ai/compatibility/v1", "dashboard.cohere.com ← API Keys",
            listOf("command-a-03-2025" to "Command A", "command-r-plus-08-2024" to "Command R+")
        ),
        Provider(
            "nvidia", "NVIDIA NIM", "openai", "https://integrate.api.nvidia.com/v1", "build.nvidia.com ← Get API Key",
            listOf(
                "meta/llama-3.3-70b-instruct" to "Llama 3.3 70B",
                "qwen/qwen2.5-coder-32b-instruct" to "Qwen 2.5 32B",
                "deepseek-ai/deepseek-r1" to "DeepSeek R1"
            ),
            note = "فيه رصيد مجاني."
        ),
        Provider(
            "huggingface", "Hugging Face", "openai", "https://router.huggingface.co/v1", "huggingface.co ← Settings ← Access Tokens",
            listOf(
                "meta-llama/Llama-3.3-70B-Instruct" to "Llama 3.3 70B",
                "Qwen/Qwen2.5-72B-Instruct" to "Qwen 2.5 72B",
                "deepseek-ai/DeepSeek-V3" to "DeepSeek V3"
            )
        ),
        Provider(
            "github", "GitHub Models", "openai", "https://models.github.ai/inference", "github.com ← Settings ← Personal access tokens",
            listOf("openai/gpt-4.1" to "GPT-4.1", "openai/gpt-4.1-mini" to "GPT-4.1 mini", "meta/Llama-3.3-70B-Instruct" to "Llama 3.3 70B"),
            note = "مجاني بحدود لمن لديه حساب GitHub."
        ),
        Provider(
            "custom", "مخصص (أي خدمة متوافقة مع OpenAI)", "openai", "", "من مزوّد الخدمة",
            emptyList(),
            note = "مثل Ollama أو LM Studio على شبكتك، أو أي سيرفر يدعم /chat/completions. ضع الرابط الأساسي واسم النموذج.",
            keyOptional = true
        )
    )

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
