package com.callreminder

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.util.AtomicFile
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class CallEvent(
    val id: String,
    val type: String,
    val callId: String,
    val payload: Map<String, String>,
    val timestamp: Long,
    val actionId: String? = null,
    val presentation: String? = null,
    val reason: String? = null,
    val error: String? = null,
) {
  fun toJson(): JSONObject =
      JSONObject().apply {
        put("id", id)
        put("type", type)
        put("callId", callId)
        put("payload", payload.toJson())
        put("timestamp", timestamp)
        actionId?.let { put("actionId", it) }
        presentation?.let { put("presentation", it) }
        reason?.let { put("reason", it) }
        error?.let { put("error", it) }
      }

  fun toWritableMap(): WritableMap =
      Arguments.createMap().apply {
        putString("id", id)
        putString("type", type)
        putString("callId", callId)
        putMap("payload", payload.toWritableMap())
        putDouble("timestamp", timestamp.toDouble())
        actionId?.let { putString("actionId", it) }
        presentation?.let { putString("presentation", it) }
        reason?.let { putString("reason", it) }
        error?.let { putString("error", it) }
      }

  /** Headless task arguments (converted to a JS object by React Native). */
  fun toBundle(): Bundle =
      Bundle().apply {
        putString("id", id)
        putString("type", type)
        putString("callId", callId)
        putBundle("payload", payload.toBundle())
        putDouble("timestamp", timestamp.toDouble())
        actionId?.let { putString("actionId", it) }
        presentation?.let { putString("presentation", it) }
        reason?.let { putString("reason", it) }
        error?.let { putString("error", it) }
      }

  companion object {
    const val SHOWN = "shown"
    const val ANSWERED = "answered"
    const val DECLINED = "declined"
    const val TIMEOUT = "timeout"
    const val ACTION = "action"
    const val SPEECH_STARTED = "speech_started"
    const val SPEECH_DONE = "speech_done"
    const val SPEECH_ERROR = "speech_error"
    const val ENDED = "ended"

    fun create(
        type: String,
        call: IncomingCall?,
        callId: String = call?.callId.orEmpty(),
        actionId: String? = null,
        presentation: String? = null,
        reason: String? = null,
        error: String? = null,
        payload: Map<String, String> = call?.payload.orEmpty(),
    ) =
        CallEvent(
            id = UUID.randomUUID().toString(),
            type = type,
            callId = callId,
            payload = payload,
            timestamp = System.currentTimeMillis(),
            actionId = actionId,
            presentation = presentation,
            reason = reason,
            error = error,
        )

    fun fromJson(json: JSONObject): CallEvent? {
      val id = json.optStringOrNull("id") ?: return null
      val type = json.optStringOrNull("type") ?: return null
      return CallEvent(
          id = id,
          type = type,
          callId = json.optString("callId"),
          payload = json.optStringMap("payload"),
          timestamp = json.optLong("timestamp"),
          actionId = json.optStringOrNull("actionId"),
          presentation = json.optStringOrNull("presentation"),
          reason = json.optStringOrNull("reason"),
          error = json.optStringOrNull("error"),
      )
    }
  }
}

/**
 * Durable FIFO of events not yet acknowledged by JS. Written atomically to
 * no-backup storage (events are device-local and must not be restored onto
 * another device). Bounded so a host that never acknowledges cannot grow it
 * without limit.
 */
internal class EventStore private constructor(context: Context) {
  private val file =
      AtomicFile(File(File(context.applicationContext.noBackupFilesDir, DIR).apply { mkdirs() }, FILE))

  @Synchronized
  fun append(event: CallEvent) {
    val events = readAll().toMutableList()
    events += event
    write(if (events.size > MAX_EVENTS) events.takeLast(MAX_EVENTS) else events)
  }

  @Synchronized fun pending(): List<CallEvent> = readAll()

  @Synchronized
  fun acknowledge(ids: Collection<String>) {
    if (ids.isEmpty()) return
    val idSet = ids.toHashSet()
    val events = readAll()
    val remaining = events.filterNot { it.id in idSet }
    if (remaining.size != events.size) {
      write(remaining)
    }
  }

  private fun readAll(): List<CallEvent> {
    if (!file.baseFile.exists()) return emptyList()
    return try {
      val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
      (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(CallEvent::fromJson) }
    } catch (error: Exception) {
      Log.w(TAG, "Discarding unreadable event queue", error)
      emptyList()
    }
  }

  private fun write(events: List<CallEvent>) {
    val array = JSONArray().also { array -> events.forEach { array.put(it.toJson()) } }
    val stream =
        try {
          file.startWrite()
        } catch (error: Exception) {
          Log.e(TAG, "Unable to open event queue for writing", error)
          return
        }
    try {
      stream.write(array.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
    } catch (error: Exception) {
      file.failWrite(stream)
      Log.e(TAG, "Unable to persist event queue", error)
    }
  }

  companion object {
    private const val TAG = "CallReminder"
    private const val DIR = "callreminder"
    private const val FILE = "events.json"
    private const val MAX_EVENTS = 500

    @Volatile private var instance: EventStore? = null

    fun get(context: Context): EventStore =
        instance ?: synchronized(this) { instance ?: EventStore(context).also { instance = it } }
  }
}
