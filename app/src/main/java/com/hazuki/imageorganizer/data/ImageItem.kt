package com.hazuki.imageorganizer.data

import android.net.Uri
import com.hazuki.imageorganizer.util.ColorPalette

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
    // SAF/ZIP経由の読込では読込時点では不明(0)なため var にしてあり、
    // 拡張機能(代表色抽出)のバックグラウンド計算時に判明した実サイズで後から埋める。
    var width: Int,
    var height: Int,
    // 類似判定用の知覚ハッシュ(64bit)。計算前は null。
    var perceptualHash: Long? = null,
    // 彩度・明度の平均値(0f〜1f)。グルーピングの追加判定に使う。計算前は null。
    var avgSaturation: Float? = null,
    var avgBrightness: Float? = null,
    // 拡張機能(スタイル一致度検索)用の代表色パレット(5色+割合)。計算前は null。
    var colorPalette: List<ColorPalette.PaletteColor>? = null,
    // 【所属フォルダ名】画像が実際に置かれている親フォルダの名前（下層フォルダ読み込み時などに利用）
    val parentFolderName: String? = null
) {
    /** 拡張子(ドット無し、小文字) */
    val extension: String
        get() = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    /** 撮影日が無い場合は日付(更新)にフォールバック */
    val effectiveTakenEpochMillis: Long
        get() = dateTakenEpochMillis ?: (dateModifiedEpochSec * 1000L)

    /** 幅・高さが判明していれば算出するアスペクト比(width/height)。未判明(0)ならnull */
    val aspectRatio: Float?
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
}

/** ソート項目 */
enum class SortOption(val label: String) {
    // リネーム済みファイル（_nn_mm形式）のみを抽出し、グループごとにカラー枠線で囲んで名前順で表示
    GROUP_SEQ_ASC("🏷️ グループ連番（枠色別）↑"),
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
enum class ThumbnailSize(val dp: Int, val label: String) {
    SMALL(100, "小"),
    LARGE(150, "大");
}