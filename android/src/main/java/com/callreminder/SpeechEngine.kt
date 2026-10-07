package com.callreminder

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Process-wide wrapper around [TextToSpeech]:
 * - initialises lazily and queues work until the engine is ready;
 * - resolves the language with a fallback chain (requested → fallback →
 *   device language), reporting which one was used, and switches to the
 *   request's fallback text when the requested language had no voice —
 *   including when the engine claimed a voice it then could not synthesise
 *   with (a language whose voice is still to be downloaded);
 * - speaks long texts in chunks within the engine's input limit, optionally
 *   repeated with a short pause, and reports start / word ranges / completion;
 * - gives up on an engine that never finishes initialising, and shuts the
 *   engine down after a period of inactivity.
 *
 * All public methods must be called on the main thread; callbacks are
 * delivered on the main thread.
 */
internal class SpeechEngine private constructor(private val appContext: Context) {

  data class Request(
      val text: String,
      val language: String,
      val fallbackLanguage: String?,
      val rate: Float,
      val pitch: Float,
      val repeat: Int,
      val usage: Int,
      /** Spoken instead of [text] when [language] has no voice (e.g. the same message in English). */
      val fallbackText: String? = null,
  )

  /** The language actually used and the text actually spoken. */
  data class Resolution(val language: String, val usedFallback: Boolean, val text: String)

  interface Listener {
    fun onResolved(resolution: Resolution) {}

    fun onStart() {}

    /** Character range within the original text currently being spoken (API 26+). */
    fun onRange(start: Int, end: Int) {}

    fun onDone() {}

    /** Stopped by [stop] or replaced by a newer request. */
    fun onStopped() {}

    fun onError(code: String) {}
  }

  private enum class EngineState {
    IDLE,
    INITIALIZING,
    READY,
  }

  private class Chunk(val text: String, val offset: Int)

  private class Session(
      val key: String,
      val request: Request,
      val listener: Listener,
      /** Index of the language used in [candidates]. */
      val candidate: Int,
      val chunks: List<Chunk>,
      val lastUtteranceId: String,
  ) {
    var started = false
  }

  private val main = Handler(Looper.getMainLooper())
  private var tts: TextToSpeech? = null
  private var state = EngineState.IDLE
  private val waiting = ArrayList<(TextToSpeech?) -> Unit>()
  private var session: Session? = null
  private var sessionCounter = 0
  private val shutdownRunnable = Runnable { shutdownNow() }
  private val initTimeoutRunnable = Runnable { onInitTimeout() }
  /** Identifies the engine being initialised, so a late callback of an abandoned one is ignored. */
  private var initGeneration = 0

  val isSpeaking: Boolean
    get() = session != null

  fun speak(request: Request, listener: Listener) {
    withEngine { engine ->
      if (engine == null) {
        listener.onError(ERROR_UNAVAILABLE)
        return@withEngine
      }
      stopSession(notify = true)
      engine.stop()
      start(engine, request, listener, from = 0)
    }
  }

  fun stop() {
    stopSession(notify = true)
    tts?.stop()
    scheduleShutdown()
  }

  fun availability(language: String, callback: (String) -> Unit) {
    withEngine { engine ->
      val result =
          when (engine?.let { languageStatus(it, Locale.forLanguageTag(language)) }) {
            null -> AVAILABILITY_NOT_SUPPORTED
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> AVAILABILITY_AVAILABLE
            TextToSpeech.LANG_MISSING_DATA -> AVAILABILITY_MISSING_DATA
            else -> AVAILABILITY_NOT_SUPPORTED
          }
      callback(result)
      scheduleShutdown()
    }
  }

  /**
   * The engine's default language (BCP-47) if the engine is already
   * initialised — never starts it. For diagnostics.
   */
  fun defaultLanguageIfReady(): String? {
    if (state != EngineState.READY) return null
    @Suppress("DEPRECATION")
    val locale = runCatching { tts?.defaultLanguage }.getOrNull() ?: return null
    return locale.toLanguageTag().takeIf { it.isNotEmpty() && it != "und" }
  }

  fun languages(callback: (List<String>) -> Unit) {
    withEngine { engine ->
      val locales =
          engine?.let {
            runCatching { it.availableLanguages?.toList() }.getOrNull()
                ?: runCatching { it.voices?.map { voice -> voice.locale } }.getOrNull()
          } ?: emptyList()
      callback(locales.map { it.toLanguageTag() }.distinct().sorted())
      scheduleShutdown()
    }
  }

  // ---------------------------------------------------------------------------

  private fun withEngine(block: (TextToSpeech?) -> Unit) {
    main.removeCallbacks(shutdownRunnable)
    when (state) {
      EngineState.READY -> block(tts)
      EngineState.INITIALIZING -> waiting += block
      EngineState.IDLE -> {
        waiting += block
        state = EngineState.INITIALIZING
        val generation = ++initGeneration
        main.postDelayed(initTimeoutRunnable, INIT_TIMEOUT_MS)
        try {
          tts = TextToSpeech(appContext) { status -> main.post { onInit(generation, status) } }
        } catch (error: Exception) {
          Log.e(TAG, "TextToSpeech could not be created", error)
          main.post { onInit(generation, TextToSpeech.ERROR) }
        }
      }
    }
  }

  private fun onInit(generation: Int, status: Int) {
    if (generation != initGeneration || state != EngineState.INITIALIZING) return
    main.removeCallbacks(initTimeoutRunnable)
    val engine = tts
    val ready = status == TextToSpeech.SUCCESS && engine != null
    if (ready) {
      state = EngineState.READY
      engine.setOnUtteranceProgressListener(progressListener)
    } else {
      Log.w(TAG, "TextToSpeech initialisation failed ($status)")
      runCatching { engine?.shutdown() }
      tts = null
      state = EngineState.IDLE
    }
    val pending = ArrayList(waiting)
    waiting.clear()
    pending.forEach { it(if (ready) engine else null) }
    if (session == null) {
      scheduleShutdown()
    }
  }

  /**
   * Some engines never call back from initialisation (a crashed or half-updated
   * TTS app). Fail the waiting work instead of leaving it, and every later
   * request, stuck behind an engine that will never be ready.
   */
  private fun onInitTimeout() {
    if (state != EngineState.INITIALIZING) return
    Log.w(TAG, "TextToSpeech did not initialise within ${INIT_TIMEOUT_MS}ms")
    initGeneration++
    runCatching { tts?.shutdown() }
    tts = null
    state = EngineState.IDLE
    val pending = ArrayList(waiting)
    waiting.clear()
    pending.forEach { it(null) }
  }

  /** Speaks [request] in the first usable language of its chain, from candidate [from] on. */
  private fun start(engine: TextToSpeech, request: Request, listener: Listener, from: Int) {
    val (candidate, resolution) =
        resolve(engine, request, from)
            ?: run {
              listener.onError(ERROR_LANGUAGE)
              scheduleShutdown()
              return
            }
    engine.setSpeechRate(request.rate)
    engine.setPitch(request.pitch)
    engine.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(request.usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())

    val chunks = chunk(resolution.text, TextToSpeech.getMaxSpeechInputLength())
    val key = "callreminder-${++sessionCounter}"
    val repeat = request.repeat.coerceAtLeast(1)
    val current = Session(key, request, listener, candidate, chunks, utteranceId(key, repeat - 1, chunks.lastIndex))
    session = current
    listener.onResolved(resolution)

    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
    for (round in 0 until repeat) {
      if (round > 0) {
        engine.playSilentUtterance(REPEAT_GAP_MS, TextToSpeech.QUEUE_ADD, "$key|pause$round|-")
      }
      chunks.forEachIndexed { index, chunk ->
        val result = engine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId(key, round, index))
        if (result != TextToSpeech.SUCCESS && session === current) {
          finishSession(current)
          engine.stop()
          listener.onError(ERROR_SPEAK)
          return
        }
      }
    }
  }

  private fun candidates(request: Request): List<String> =
      listOfNotNull(request.language, request.fallbackLanguage, Locale.getDefault().toLanguageTag()).distinct()

  /** The first candidate from [from] on that the engine has a voice for, with its index. */
  private fun resolve(engine: TextToSpeech, request: Request, from: Int): Pair<Int, Resolution>? {
    candidates(request).forEachIndexed { index, tag ->
      if (index < from) return@forEachIndexed
      val locale = Locale.forLanguageTag(tag)
      if (languageStatus(engine, locale) >= TextToSpeech.LANG_AVAILABLE) {
        val set = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (set >= TextToSpeech.LANG_AVAILABLE) {
          val usedFallback = index > 0
          // Text in the requested language's script read by another voice is
          // gibberish; the caller's fallback wording is meant for exactly this.
          val text = request.fallbackText?.takeIf { usedFallback && it.isNotBlank() } ?: request.text
          return index to Resolution(tag, usedFallback, text)
        }
      }
    }
    return null
  }

  /**
   * [TextToSpeech.isLanguageAvailable], except that a language whose voices
   * are all still to be downloaded counts as [TextToSpeech.LANG_MISSING_DATA].
   * Google's engine reports such languages as available, then starts the
   * download and fails the synthesis ("no voice found").
   */
  private fun languageStatus(engine: TextToSpeech, locale: Locale): Int {
    val status = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
    if (status < TextToSpeech.LANG_AVAILABLE) return status
    val voices = runCatching { engine.voices }.getOrNull()?.filter { sameLanguage(it.locale, locale) }
    if (voices.isNullOrEmpty()) return status // engine without a voice list: trust the status
    val sameCountry = voices.filter { sameCountry(it.locale, locale) }.ifEmpty { voices }
    return if (sameCountry.any { it.isInstalled() }) status else TextToSpeech.LANG_MISSING_DATA
  }

  private fun Voice.isInstalled(): Boolean =
      features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

  private fun sameLanguage(a: Locale, b: Locale): Boolean =
      runCatching { a.isO3Language == b.isO3Language }.getOrDefault(a.language == b.language)

  private fun sameCountry(a: Locale, b: Locale): Boolean =
      b.country.isEmpty() || runCatching { a.isO3Country == b.isO3Country }.getOrDefault(a.country == b.country)

  private fun stopSession(notify: Boolean) {
    val current = session ?: return
    finishSession(current)
    if (notify) {
      current.listener.onStopped()
    }
  }

  private fun finishSession(current: Session) {
    if (session === current) {
      session = null
    }
  }

  private fun scheduleShutdown() {
    main.removeCallbacks(shutdownRunnable)
    main.postDelayed(shutdownRunnable, IDLE_SHUTDOWN_MS)
  }

  private fun shutdownNow() {
    if (session != null || state == EngineState.INITIALIZING) return
    runCatching { tts?.shutdown() }
    tts = null
    state = EngineState.IDLE
  }

  private fun sessionFor(utteranceId: String?): Session? {
    val current = session ?: return null
    return if (utteranceId != null && utteranceId.startsWith("${current.key}|")) current else null
  }

  private fun chunkIndex(utteranceId: String): Int? = utteranceId.split('|').getOrNull(2)?.toIntOrNull()

  private val progressListener =
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
          main.post {
            val current = sessionFor(utteranceId) ?: return@post
            if (!current.started) {
              current.started = true
              current.listener.onStart()
            }
          }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
          main.post {
            val current = sessionFor(utteranceId) ?: return@post
            val chunk = utteranceId?.let(::chunkIndex)?.let { current.chunks.getOrNull(it) } ?: return@post
            current.listener.onRange(chunk.offset + start, chunk.offset + end)
          }
        }

        override fun onDone(utteranceId: String?) {
          main.post {
            val current = sessionFor(utteranceId) ?: return@post
            if (utteranceId == current.lastUtteranceId) {
              finishSession(current)
              current.listener.onDone()
              scheduleShutdown()
            }
          }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
          onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
          main.post {
            val current = sessionFor(utteranceId) ?: return@post
            finishSession(current)
            val engine = tts
            engine?.stop()
            if (engine != null && !current.started && errorCode in VOICE_ERRORS &&
                current.candidate < candidates(current.request).lastIndex) {
              // Nothing was heard yet and the voice itself failed (e.g. one that
              // is still downloading): carry on down the language chain.
              Log.i(TAG, "No usable voice for ${current.request.language} ($errorCode), trying the next language")
              start(engine, current.request, current.listener, current.candidate + 1)
              return@post
            }
            current.listener.onError("tts_error_$errorCode")
            scheduleShutdown()
          }
        }
      }

  companion object {
    private const val TAG = "CallReminder"
    private const val REPEAT_GAP_MS = 900L
    private const val IDLE_SHUTDOWN_MS = 30_000L
    private const val INIT_TIMEOUT_MS = 10_000L

    const val ERROR_UNAVAILABLE = "tts_unavailable"
    const val ERROR_LANGUAGE = "language_unavailable"
    const val ERROR_SPEAK = "speak_failed"
    const val ERROR_INTERRUPTED = "interrupted"

    /** Synthesis errors that mean "no voice for this language right now", worth a fallback. */
    private val VOICE_ERRORS =
        setOf(
            TextToSpeech.ERROR_SYNTHESIS,
            TextToSpeech.ERROR_NOT_INSTALLED_YET,
            TextToSpeech.ERROR_NETWORK,
            TextToSpeech.ERROR_NETWORK_TIMEOUT,
        )

    const val AVAILABILITY_AVAILABLE = "available"
    const val AVAILABILITY_MISSING_DATA = "missing_data"
    const val AVAILABILITY_NOT_SUPPORTED = "not_supported"

    @Volatile private var instance: SpeechEngine? = null

    /** The engine if this process already created it; never creates one. */
    fun peek(): SpeechEngine? = instance

    fun get(context: Context): SpeechEngine =
        instance
            ?: synchronized(this) {
              instance ?: SpeechEngine(context.applicationContext).also { instance = it }
            }

    private fun utteranceId(key: String, round: Int, chunk: Int) = "$key|$round|$chunk"

    /** Splits at sentence/word boundaries so each piece fits the engine limit. */
    private fun chunk(text: String, limit: Int): List<Chunk> {
      val max = limit.coerceAtLeast(64) - 1
      if (text.length <= max) return listOf(Chunk(text, 0))
      val chunks = ArrayList<Chunk>()
      var start = 0
      while (start < text.length) {
        var end = (start + max).coerceAtMost(text.length)
        if (end < text.length) {
          val window = text.substring(start, end)
          val sentence = window.indexOfLast { it == '.' || it == '!' || it == '?' || it == '।' || it == '\n' }
          val space = window.lastIndexOf(' ')
          val cut = if (sentence > max / 2) sentence + 1 else if (space > max / 2) space + 1 else window.length
          end = start + cut
        }
        chunks += Chunk(text.substring(start, end), start)
        start = end
      }
      return chunks
    }
  }
}
