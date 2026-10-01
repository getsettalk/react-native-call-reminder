package com.callreminder

import android.os.Bundle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversions between React Native bridge types and org.json. Everything the
 * library persists (config, calls, events) is stored as JSON, so receivers and
 * the call screen can run in a process where React Native never started.
 */
internal fun ReadableMap.toJson(): JSONObject {
  val json = JSONObject()
  val iterator = keySetIterator()
  while (iterator.hasNextKey()) {
    val key = iterator.nextKey()
    when (getType(key)) {
      ReadableType.Null -> Unit
      ReadableType.Boolean -> json.put(key, getBoolean(key))
      ReadableType.Number -> json.put(key, getDouble(key))
      ReadableType.String -> json.put(key, getString(key))
      ReadableType.Map -> getMap(key)?.let { json.put(key, it.toJson()) }
      ReadableType.Array -> getArray(key)?.let { json.put(key, it.toJson()) }
    }
  }
  return json
}

internal fun ReadableArray.toJson(): JSONArray {
  val json = JSONArray()
  for (index in 0 until size()) {
    when (getType(index)) {
      ReadableType.Null -> json.put(JSONObject.NULL)
      ReadableType.Boolean -> json.put(getBoolean(index))
      ReadableType.Number -> json.put(getDouble(index))
      ReadableType.String -> json.put(getString(index))
      ReadableType.Map -> json.put(getMap(index)?.toJson() ?: JSONObject.NULL)
      ReadableType.Array -> json.put(getArray(index)?.toJson() ?: JSONObject.NULL)
    }
  }
  return json
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

internal fun JSONObject.optStringMap(key: String): Map<String, String> {
  val source = optJSONObject(key) ?: return emptyMap()
  val out = LinkedHashMap<String, String>()
  source.keys().forEach { name ->
    if (!source.isNull(name)) {
      out[name] = source.optString(name)
    }
  }
  return out
}

internal fun Map<String, String>.toJson(): JSONObject =
    JSONObject().also { json -> forEach { (key, value) -> json.put(key, value) } }

internal fun Map<String, String>.toWritableMap(): WritableMap =
    Arguments.createMap().also { map -> forEach { (key, value) -> map.putString(key, value) } }

internal fun Map<String, String>.toBundle(): Bundle =
    Bundle().also { bundle -> forEach { (key, value) -> bundle.putString(key, value) } }
