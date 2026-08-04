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
    val pendingScrollToIndex: Int? = null, // スライドショー終了後、一覧をこの位置までスクロールさせるための一時的な指示

    val snackbarMessage: String? = null
)
