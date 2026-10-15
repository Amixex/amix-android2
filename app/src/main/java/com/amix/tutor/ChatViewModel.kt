package com.amix.tutor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ChatMessage(val text: String, val fromUser: Boolean)

/**
 * NEED_DOWNLOAD — модели нет ни в APK, ни на телефоне (запасной путь: скачать)
 * DOWNLOADING   — скачивание (запасной путь)
 * COPYING       — один раз после установки: вшитая модель копируется во внутреннюю память
 * LOADING       — модель запускается (несколько секунд при каждом старте)
 */
enum class AppStage { NEED_DOWNLOAD, DOWNLOADING, COPYING, LOADING, READY, ERROR }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _stage = MutableStateFlow(initialStage(app))
    val stage: StateFlow<AppStage> = _stage.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(ChatMessage("Привет! Я Amix. Спроси что-нибудь.", fromUser = false))
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var engine: LlmEngine? = null
    private var working = false

    init {
        begin()
    }

    private fun initialStage(app: Application): AppStage = when {
        ModelDownloader.isDownloaded(app) -> AppStage.LOADING
        ModelDownloader.hasBundledModel(app) -> AppStage.COPYING
        else -> AppStage.NEED_DOWNLOAD
    }

    private fun begin() {
        val app = getApplication<Application>()
        when {
            ModelDownloader.isDownloaded(app) -> loadEngine()
            ModelDownloader.hasBundledModel(app) -> prepareBundled()
            else -> _stage.value = AppStage.NEED_DOWNLOAD
        }
    }

    /** Один раз после установки: копируем вшитую модель во внутреннюю память. */
    private fun prepareBundled() {
        if (working) return
        working = true
        _stage.value = AppStage.COPYING
        _progress.value = 0f
        _error.value = null
        viewModelScope.launch {
            try {
                ModelDownloader.copyFromAssets(getApplication()) { d, t ->
                    if (t > 0) _progress.value = (d.toFloat() / t.toFloat()).coerceIn(0f, 1f)
                }
                working = false
                loadEngine()
            } catch (t: Throwable) {
                working = false
                fail(t)
            }
        }
    }

    /** Запасной путь: скачивание, если модели нет в APK. */
    fun downloadModel() {
        if (working || _stage.value == AppStage.DOWNLOADING) return
        working = true
        _stage.value = AppStage.DOWNLOADING
        _progress.value = 0f
        _error.value = null
        viewModelScope.launch {
            try {
                ModelDownloader.download(getApplication()) { d, t ->
                    if (t > 0) _progress.value = (d.toFloat() / t.toFloat()).coerceIn(0f, 1f)
                }
                working = false
                loadEngine()
            } catch (t: Throwable) {
                working = false
                fail(t)
            }
        }
    }

    private fun loadEngine() {
        if (working) return
        working = true
        _stage.value = AppStage.LOADING
        viewModelScope.launch {
            try {
                val e = withContext(Dispatchers.IO) { LlmEngine.create(getApplication()) }
                engine = e
                _stage.value = AppStage.READY
            } catch (t: Throwable) {
                fail(t)
            } finally {
                working = false
            }
        }
    }

    fun retry() {
        val app = getApplication<Application>()
        _error.value = null
        when {
            ModelDownloader.isDownloaded(app) -> loadEngine()
            ModelDownloader.hasBundledModel(app) -> prepareBundled()
            else -> downloadModel()
        }
    }

    private fun fail(t: Throwable) {
        _error.value = friendly(t)
        _stage.value = AppStage.ERROR
    }

    private fun friendly(t: Throwable): String {
        if (t is ModelDownloader.NoSpaceException) {
            val gb = t.missingBytes / 1073741824.0
            return String.format(
                Locale.US,
                "Не хватает места на телефоне. Освободите ещё около %.1f ГБ и нажмите «Повторить».",
                gb
            )
        }
        val m = t.message.orEmpty()
        return when {
            t is OutOfMemoryError || m.contains("memory", ignoreCase = true) || m.contains("alloc", ignoreCase = true) ->
                "Не хватило оперативной памяти для запуска модели. Закройте другие приложения и нажмите «Повторить»."
            m.isBlank() -> "Неизвестная ошибка. Нажмите «Повторить»."
            else -> m
        }
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || _busy.value || _stage.value != AppStage.READY) return
        val e = engine ?: return
        _messages.value = _messages.value + ChatMessage(q, fromUser = true)
        _busy.value = true
        var acc = ""
        val idx = _messages.value.size
        _messages.value = _messages.value + ChatMessage("", fromUser = false)

        // В историю не входят последний вопрос (он добавляется в конце) и пустой ответ-заготовка
        val prompt = buildPrompt(_messages.value.dropLast(2), q)
        e.generate(
            prompt = prompt,
            onToken = { chunk ->
                acc += chunk
                val list = _messages.value.toMutableList()
                if (idx < list.size) list[idx] = ChatMessage(acc, fromUser = false)
                _messages.value = list
            },
            onDone = {
                _busy.value = false
            },
            onError = { t ->
                _error.value = t.message ?: "generation error"
                _busy.value = false
            }
        )
    }

    private fun buildPrompt(history: List<ChatMessage>, last: String): String {
        // Простой промпт-формат для instruct-модели; длинные сообщения обрезаются,
        // чтобы всё помещалось в окно модели (1280 токенов)
        val sys = "Ты Amix, офлайн-репетитор. Отвечай на языке пользователя, кратко и по делу."
        val recent = history.filter { it.text.isNotBlank() }.takeLast(6)
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
        for (m in recent) {
            val role = if (m.fromUser) "user" else "assistant"
            sb.append("<|im_start|>").append(role).append("\n").append(m.text.take(600)).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(last.take(1500)).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
    }
}
