package com.hazuki.imageorganizer.viewmodel

import com.hazuki.imageorganizer.data.DisplayEntry
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.util.ImageGrouping

enum class SlideshowInterval(val seconds: Int) {
    S1(1), S3(3), S5(5), S9(9)
}

data class OrganizerUiState(
    val isLoading: Boolean = true,
    val isStreaming: Boolean = false, // 段階読込中(バックグラウンドでバッチ追加中)は true。画面消灯防止に使う。
    val currentFolderLabel: String = "Pictures/未整理",
    val entries: List<DisplayEntry> = emptyList(),
    val totalImageCount: Int = 0,

    val sameImageOnly: Boolean = false, // 「同画像のみ表示」トグル(初期OFF)
    val groupThreshold: Int = ImageGrouping.DEFAULT_THRESHOLD,

    val sortOption: SortOption = SortOption.DEFAULT,
    val sortSheetVisible: Boolean = false,

    val thumbnailSize: ThumbnailSize = ThumbnailSize.SMALL,

    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),

    val fullscreenIndex: Int? = null, // nullでない場合、その位置の画像を全画面表示

    val slideshowActive: Boolean = false,
    val slideshowInterval: SlideshowInterval = SlideshowInterval.S3,
    val slideshowIndex: Int = 0,

    val snackbarMessage: String? = null
)
