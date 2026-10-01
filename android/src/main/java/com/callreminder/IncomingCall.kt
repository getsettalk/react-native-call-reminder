package com.callreminder

import org.json.JSONArray
import org.json.JSONObject

/** A button on the call screen after answering (e.g. "Taken", "Snooze"). */
internal data class CallAction(
    val id: String,
    val label: String,
    val style: String,
    val dismissesCall: Boolean,
    val opensApp: Boolean,
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("id", id)
          .put("label", label)
          .put("style", style)
          .put("dismissesCall", dismissesCall)
          .put("opensApp", opensApp)

  companion object {
    const val STYLE_PRIMARY = "primary"
    const val STYLE_SECONDARY = "secondary"
    const val STYLE_DESTRUCTIVE = "destructive"

    fun fromJson(json: JSONObject): CallAction? {
      val id = json.optStringOrNull("id") ?: return null
      val label = json.optStringOrNull("label") ?: return null
      val style =
          json.optStringOrNull("style")?.takeIf {
            it == STYLE_PRIMARY || it == STYLE_SECONDARY || it == STYLE_DESTRUCTIVE
          } ?: STYLE_SECONDARY
      return CallAction(
          id = id,
          label = label,
          style = style,
          dismissesCall = json.optBoolean("dismissesCall", true),
          opensApp = json.optBoolean("opensApp", false),
      )
    }

    fun listFrom(array: JSONArray?): List<CallAction> {
      if (array == null) return emptyList()
      return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(::fromJson)
      }
    }
  }
}

/** One reminder call, as passed to `showIncomingCall`. Immutable and JSON-persistable. */
internal data class IncomingCall(
    val callId: String,
    val callerName: String,
    val title: String,
    val body: String,
    val speakText: String,
    val language: String,
    val fallbackLanguage: String?,
    /** Spoken (in the fallback voice) instead of [speakText] when [language] has no voice. */
    val fallbackSpeakText: String?,
    val speechRate: Float,
    val pitch: Float,
    val repeatSpeech: Int,
    val timeoutSeconds: Int,
    /** Null = use the configured default actions. */
    val actions: List<CallAction>?,
    val payload: Map<String, String>,
    val avatarUri: String?,
    val ringtone: String?,
    val privateOnLockScreen: Boolean,
) {
  fun resolvedActions(config: CallReminderConfig): List<CallAction> =
      actions ?: config.defaultActions

  fun toJson(): JSONObject =
      JSONObject().apply {
        put("callId", callId)
        put("callerName", callerName)
        put("title", title)
        put("body", body)
        put("speakText", speakText)
        put("language", language)
        fallbackLanguage?.let { put("fallbackLanguage", it) }
        fallbackSpeakText?.let { put("fallbackSpeakText", it) }
        put("speechRate", speechRate.toDouble())
        put("pitch", pitch.toDouble())
        put("repeatSpeech", repeatSpeech)
        put("timeoutSeconds", timeoutSeconds)
        actions?.let { list -> put("actions", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }) }
        put("payload", payload.toJson())
        avatarUri?.let { put("avatarUri", it) }
        ringtone?.let { put("ringtone", it) }
        put("lockScreenPrivacy", if (privateOnLockScreen) "private" else "public")
      }

  companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 45
    private const val MIN_TIMEOUT_SECONDS = 10
    private const val MAX_TIMEOUT_SECONDS = 300
    private const val MAX_REPEAT = 5

    /** @throws IllegalArgumentException when a required field is missing. */
    fun fromJson(json: JSONObject): IncomingCall {
      fun required(key: String): String =
          json.optStringOrNull(key)?.takeIf { it.isNotBlank() }
              ?: throw IllegalArgumentException("$key is required")

      return IncomingCall(
          callId = required("callId"),
          callerName = required("callerName"),
          title = required("title"),
          body = required("body"),
          speakText = required("speakText"),
          language = required("language").replace('_', '-'),
          fallbackLanguage = json.optStringOrNull("fallbackLanguage")?.replace('_', '-'),
          fallbackSpeakText = json.optStringOrNull("fallbackSpeakText")?.takeIf { it.isNotBlank() },
          speechRate = json.optDouble("speechRate", 1.0).toFloat().coerceIn(0.25f, 3f),
          pitch = json.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f),
          repeatSpeech = json.optInt("repeatSpeech", 1).coerceIn(1, MAX_REPEAT),
          timeoutSeconds =
              json
                  .optInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
                  .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
          actions = json.optJSONArray("actions")?.let { CallAction.listFrom(it) },
          payload = json.optStringMap("payload"),
          avatarUri = json.optStringOrNull("avatarUri"),
          ringtone = json.optStringOrNull("ringtone"),
          privateOnLockScreen = json.optStringOrNull("lockScreenPrivacy") == "private",
      )
    }
  }
}
