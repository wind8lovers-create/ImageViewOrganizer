package com.hazuki.imageorganizer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手動グルーピング(分類)の情報を端末内(SharedPreferences)に保存する。
 * RecentFoldersStore.kt と同じ方式(追加ライブラリ不要のorg.json手書きパース)。
 *
 * 要件定義 Q20: アプリ内部のデータ領域に保存(通常のアップデートでは消えない。アンインストールで消える)。
 */
class ClassificationStore(context: Context) {

    private val prefs = context.getSharedPreferences("classification_prefs", Context.MODE_PRIVATE)
    private val KEY_GROUPS = "groups"

    fun loadGroups(): List<ClassificationGroup> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val idsArray = obj.optJSONArray("imageIds") ?: JSONArray()
                val ids = (0 until idsArray.length()).map { idsArray.getLong(it) }
                ClassificationGroup(
                    category = obj.getString("category").first(),
                    sequence = obj.getInt("sequence"),
                    name = obj.optString("name", ""),
                    imageIds = ids,
                    sortOption = try {
                        SortOption.valueOf(obj.optString("sortOption", SortOption.DEFAULT.name))
                    } catch (e: Exception) {
                        SortOption.DEFAULT
                    },
                    representativeId = if (obj.has("representativeId") && !obj.isNull("representativeId")) {
                        obj.optLong("representativeId")
                    } else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveGroups(groups: List<ClassificationGroup>) {
        val array = JSONArray()
        groups.forEach { g ->
            val obj = JSONObject()
            obj.put("category", g.category.toString())
            obj.put("sequence", g.sequence)
            obj.put("name", g.name)
            obj.put("imageIds", JSONArray(g.imageIds))
            obj.put("sortOption", g.sortOption.name)
            if (g.representativeId != null) obj.put("representativeId", g.representativeId) else obj.put("representativeId", JSONObject.NULL)
            array.put(obj)
        }
        prefs.edit().putString(KEY_GROUPS, array.toString()).apply()
    }
}
