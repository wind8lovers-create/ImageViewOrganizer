package com.hazuki.imageorganizer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 前回開いたフォルダ/ZIP書庫を記憶し、起動時の自動読込と「履歴」表示に使う。
 * SharedPreferences + org.json での簡易永続化(追加ライブラリ不要)。
 */
class RecentFoldersStore(context: Context) {

    private val prefs = context.getSharedPreferences("recent_folders_prefs", Context.MODE_PRIVATE)
    private val KEY_HISTORY = "history"
    private val MAX_HISTORY = 10

    fun getLastOpened(): RecentEntry? = getHistory().firstOrNull()

    fun getHistory(): List<RecentEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                RecentEntry(
                    type = RecentEntryType.valueOf(obj.getString("type")),
                    uri = obj.getString("uri"),
                    label = obj.getString("label"),
                    timestampMillis = obj.getLong("ts")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 履歴の先頭に追加(同じURIが既にあれば一旦削除してから先頭に移動)し、"前回開いたもの"として保存する */
    fun recordOpened(entry: RecentEntry) {
        val current = getHistory().toMutableList()
        current.removeAll { it.uri == entry.uri }
        current.add(0, entry)
        val trimmed = current.take(MAX_HISTORY)

        val array = JSONArray()
        trimmed.forEach { e ->
            val obj = JSONObject()
            obj.put("type", e.type.name)
            obj.put("uri", e.uri)
            obj.put("label", e.label)
            obj.put("ts", e.timestampMillis)
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /** アクセスできなくなった(移動・削除された)履歴エントリを1件削除する */
    fun removeEntry(uri: String) {
        val current = getHistory().toMutableList()
        val removed = current.removeAll { it.uri == uri }
        if (removed) {
            val array = JSONArray()
            current.forEach { e ->
                val obj = JSONObject()
                obj.put("type", e.type.name)
                obj.put("uri", e.uri)
                obj.put("label", e.label)
                obj.put("ts", e.timestampMillis)
                array.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
        }
    }
}
