package com.hazuki.imageorganizer.data

import android.net.Uri

/**
 * 一覧に表示する画像1枚分の情報。
 * MediaStore から読み込んだメタ情報を保持する。
 */
data class ImageItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateModifiedEpochSec: Long,   // タイムスタンプ(フォールバック込み、DATE_MODIFIED相当)
    val dateAddedEpochSec: Long,
    var dateTakenEpochMillis: Long?,  // 撮影日(EXIF起源、無ければ null。ソートで撮影日が必要になるまでは遅延)
    val mimeType: String,
    val width: Int,
    val height: Int,
    // 類似判定用の知覚ハッシュ(64bit)。計算前は null。
    var perceptualHash: Long? = null
) {
    /** 拡張子(ドット無し、小文字) */
    val extension: String
        get() = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    /** 撮影日が無い場合は日付(更新)にフォールバック */
    val effectiveTakenEpochMillis: Long
        get() = dateTakenEpochMillis ?: (dateModifiedEpochSec * 1000L)
}

/** ソート項目(10種類) */
enum class SortOption(val label: String) {
    NAME_ASC("ファイル名 ↑"),
    NAME_DESC("ファイル名 ↓"),
    SIZE_ASC("サイズ ↑"),
    SIZE_DESC("サイズ ↓"),
    DATE_ASC("日付 ↑"),
    DATE_DESC("日付 ↓"),
    TAKEN_ASC("撮影日 ↑"),
    TAKEN_DESC("撮影日 ↓"),
    TYPE_ASC("形式 ↑"),
    TYPE_DESC("形式 ↓");

    companion object {
        val DEFAULT = DATE_DESC
    }
}

/** サムネイルサイズ(2段階固定) */
enum class ThumbnailSize(val dp: Int) {
    SMALL(100),
    LARGE(150)
}

/** 画像1枚、またはグループとしてまとめられた表示単位 */
sealed class DisplayEntry {
    data class Single(val image: ImageItem) : DisplayEntry()
    data class Grouped(val image: ImageItem, val groupId: Int, val colorIndex: Int) : DisplayEntry()
}
