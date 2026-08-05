package com.hazuki.imageorganizer.viewmodel

import com.hazuki.imageorganizer.data.DisplayEntry
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.util.ColorGroupPreset
import com.hazuki.imageorganizer.util.ImageGrouping

enum class SlideshowInterval(val seconds: Int) {
    S1(1), S3(3), S5(5), S9(9)
}

/**
 * 一覧のスクロール位置を指示するためのワンショットイベント。
 * indexだけを保持する形だと「前回と同じ0番目に戻したい」場合などに値が変化せず
 * LaunchedEffectが再発火しないため、nonce(発行のたびに変わる値)を持たせて
 * 毎回確実にスクロール処理が走るようにしている。
 */
data class ScrollRequest(val index: Int, val nonce: Long = System.nanoTime())

data class OrganizerUiState(
    val isLoading: Boolean = true,
    val isStreaming: Boolean = false, // 段階読込中(バックグラウンドでバッチ追加中)は true。画面消灯防止に使う。
    val isGrouping: Boolean = false, // ハッシュ計算・グルーピング処理中は true。画面消灯防止に使う。
    val currentFolderLabel: String = "Download/未整理",
    val recentEntries: List<RecentEntry> = emptyList(),
    val entries: List<DisplayEntry> = emptyList(),
    val totalImageCount: Int = 0, // これまでに読み込み済みの枚数
    val folderTotalCount: Int = 0, // フォルダ内の画像総数(先にわかる場合のみ設定。0は「不明」ではなく未設定状態を含む)

    val sameImageOnly: Boolean = false, // 「同画像のみ表示」トグル(初期OFF)
    val groupThreshold: Int = ImageGrouping.DEFAULT_THRESHOLD,
    val colorPreset: ColorGroupPreset? = null, // 彩度・明度プリセット(A/B/C)。未選択ならnull(彩度・明度は判定に使わない)
    val groupCount: Int = 0, // 直近のグルーピングで見つかったグループ数(設定変更の効果を確認しやすくするため)
    val hideSinglesWhenGrouped: Boolean = false, // ON: グループに属さない単独画像を一覧から隠す(グループのみ表示)

    val sortOption: SortOption = SortOption.DEFAULT,
    val sortSheetVisible: Boolean = false,

    val thumbnailSize: ThumbnailSize = ThumbnailSize.SMALL,

    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),

    val fullscreenIndex: Int? = null, // nullでない場合、その位置の画像を全画面表示

    val slideshowActive: Boolean = false,
    val slideshowInterval: SlideshowInterval = SlideshowInterval.S3,
    val slideshowIndex: Int = 0,
    val pendingScrollRequest: ScrollRequest? = null, // 一覧をこの位置までスクロールさせるための一時的な指示(スライドショー終了後・フォルダ再読込後など)

    // ---- 拡張機能: スタイル一致度検索(基準画像に似た色使いの画像を絞り込む) ----
    val extensionSheetVisible: Boolean = false, // 設定パネル(ボトムシート)の表示中フラグ
    val extensionActive: Boolean = false, // 一覧へのフィルタが実際に有効かどうか
    val originImageId: Long? = null, // 基準画像のID
    val aspectRatioOnly: Boolean = false, // ON: 基準画像とアスペクト比が一致する画像のみ表示
    val styleMatchThreshold: Int = 0, // 0〜100。0はフィルタなし(スタイル一致度による絞り込みをしない)

    val snackbarMessage: String? = null
)
