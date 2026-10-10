package com.alharith.ai.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AssistantState { IDLE, WAITING_WAKE, LISTENING, THINKING, SPEAKING, CONFIRMING }

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val fromUser: Boolean,
    val text: String,
    val isAction: Boolean = false   // سطر حالة مثل "يبحث في جهات الاتصال…"
)

data class PendingConfirmation(
    val question: String,
    val detail: String,
    val deferred: CompletableDeferred<Boolean>
)

/** حالة مشتركة بين الخدمة والواجهة. */
object ConversationStore {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _confirmation = MutableStateFlow<PendingConfirmation?>(null)
    val confirmation: StateFlow<PendingConfirmation?> = _confirmation.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun add(msg: ChatMessage) = _messages.update { (it + msg).takeLast(200) }
    fun user(text: String) = add(ChatMessage(fromUser = true, text = text))
    fun assistant(text: String) = add(ChatMessage(fromUser = false, text = text))
    fun action(text: String) = add(ChatMessage(fromUser = false, text = text, isAction = true))
    fun clear() { _messages.value = emptyList() }

    fun setState(s: AssistantState) { _state.value = s }
    fun setPartial(p: String) { _partial.value = p }

    /** مستوى صوت المتحدث (0..1) أثناء الاستماع — تتفاعل معه الدائرة */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    fun setLevel(v: Float) { _level.value = v }

    /** طلب فتح نافذة الإدخال الصوتي البديلة (عند تعذّر الاستماع داخل التطبيق) */
    private val _dialogRequest = MutableStateFlow(0L)
    val dialogRequest: StateFlow<Long> = _dialogRequest.asStateFlow()
    fun requestVoiceDialog() { _dialogRequest.value = System.currentTimeMillis() }
    fun setError(e: String?) { _error.value = e }

    fun showConfirmation(c: PendingConfirmation?) { _confirmation.value = c }

    /** يُستدعى من أزرار الواجهة. */
    fun answerConfirmation(yes: Boolean) {
        _confirmation.value?.deferred?.complete(yes)
    }
}
