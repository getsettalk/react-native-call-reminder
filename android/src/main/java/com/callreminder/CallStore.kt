package com.callreminder

import android.content.Context
import org.json.JSONObject

internal enum class CallState(val key: String) {
  /** Ringing notification posted, waiting for Answer/Decline/timeout. */
  RINGING("ringing"),
  /** Answered: call screen open, reminder being / has been spoken. */
  ACTIVE("active"),
  /** Posted quietly (user was busy); can still be answered from the notification. */
  NOTIFIED("notified");

  companion object {
    fun from(key: String?) = entries.firstOrNull { it.key == key }
  }
}

internal data class CallRecord(
    val call: IncomingCall,
    val state: CallState,
    val presentation: String,
    val createdAt: Long,
    val deadlineAt: Long,
    val answeredAt: Long = 0L,
    val silenced: Boolean = false,
    /** Process that owns the in-memory side of an ACTIVE call (speech, timers). */
    val pid: Int,
) {
  val callId: String
    get() = call.callId

  fun toJson(): JSONObject =
      JSONObject()
          .put("call", call.toJson())
          .put("state", state.key)
          .put("presentation", presentation)
          .put("createdAt", createdAt)
          .put("deadlineAt", deadlineAt)
          .put("answeredAt", answeredAt)
          .put("silenced", silenced)
          .put("pid", pid)

  companion object {
    fun fromJson(json: JSONObject): CallRecord? =
        runCatching {
              CallRecord(
                  call = IncomingCall.fromJson(json.getJSONObject("call")),
                  state = CallState.from(json.optString("state")) ?: return null,
                  presentation = json.optString("presentation"),
                  createdAt = json.optLong("createdAt"),
                  deadlineAt = json.optLong("deadlineAt"),
                  answeredAt = json.optLong("answeredAt"),
                  silenced = json.optBoolean("silenced"),
                  pid = json.optInt("pid"),
              )
            }
            .getOrNull()
  }
}

/**
 * Durable state of live calls plus a short "recently presented" index used to
 * drop duplicate deliveries (e.g. the same push arriving twice). Writes use
 * `commit()`: a receiver's process may be killed right after it returns.
 */
internal class CallStore private constructor(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  fun all(): List<CallRecord> {
    val json = read(KEY_CALLS)
    return json.keys().asSequence().mapNotNull { key -> json.optJSONObject(key)?.let(CallRecord::fromJson) }.toList()
  }

  @Synchronized fun get(callId: String): CallRecord? = read(KEY_CALLS).optJSONObject(callId)?.let(CallRecord::fromJson)

  @Synchronized
  fun put(record: CallRecord) {
    val json = read(KEY_CALLS).put(record.callId, record.toJson())
    write(KEY_CALLS, json)
  }

  @Synchronized
  fun remove(callId: String) {
    val json = read(KEY_CALLS)
    if (json.has(callId)) {
      json.remove(callId)
      write(KEY_CALLS, json)
    }
  }

  @Synchronized
  fun markPresented(callId: String, at: Long, retainMs: Long) {
    val json = read(KEY_RECENT)
    val cutoff = at - retainMs.coerceAtLeast(MIN_RETAIN_MS)
    json.keys().asSequence().toList().forEach { key -> if (json.optLong(key) < cutoff) json.remove(key) }
    json.put(callId, at)
    write(KEY_RECENT, json)
  }

  @Synchronized
  fun presentedAt(callId: String): Long? = read(KEY_RECENT).let { if (it.has(callId)) it.optLong(callId) else null }

  private fun read(key: String): JSONObject =
      prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

  private fun write(key: String, json: JSONObject) {
    prefs.edit().putString(key, json.toString()).commit()
  }

  companion object {
    private const val PREFS = "com.callreminder.calls"
    private const val KEY_CALLS = "calls"
    private const val KEY_RECENT = "recent"
    private const val MIN_RETAIN_MS = 60 * 60 * 1000L

    @Volatile private var instance: CallStore? = null

    fun get(context: Context): CallStore =
        instance ?: synchronized(this) { instance ?: CallStore(context).also { instance = it } }
  }
}
