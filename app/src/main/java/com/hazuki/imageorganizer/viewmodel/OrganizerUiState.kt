package com.hazuki.imageorganizer.viewmodel

import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize

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
    val isComparing: Boolean = false, // 拡張選択の絞り込み計算中は true。画面消灯防止 + 上部バーの「計算中」表示に使う。
    val currentFolderLabel: String = "Download/未整理",
    val recentEntries: List<RecentEntry> = emptyList(),
    val entries: List<ImageItem> = emptyList(),
    val totalImageCount: Int = 0, // これまでに読み込み済みの枚数
    val folderTotalCount: Int = 0, // フォルダ内の画像総数(先にわかる場合のみ設定。0は「不明」ではなく未設定状態を含む)

    val sortOption: SortOption = SortOption.DEFAULT,
    val sortSheetVisible: Boolean = false,

    val thumbnailSize: ThumbnailSize = ThumbnailSize.SMALL,

    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val currentJumpIndex: Int? = null, // 選択画像の中での現在の巡回位置(1〜N)。未ジャンプ時はnull。

    val fullscreenIndex: Int? = null, // nullでない場合、その位置の画像を全画面表示

    val slideshowActive: Boolean = false,
    val slideshowInterval: SlideshowInterval = SlideshowInterval.S3,
    val slideshowIndex: Int = 0,
    val pendingScrollRequest: ScrollRequest? = null, // 一覧をこの位置までスクロールさせるための一時的な指示(スライドショー終了後・フォルダ再読込後・拡張選択終了後など)

    // ---- 拡張選択: 基準画像(origin)に似た画像を絞り込む ----
    // 選択している画像がある時だけ使える(未選択時はそもそも起動しない)。
    val extensionSelectionActive: Boolean = false, // ON/OFF(上部バーの「拡張選択」ボタンの反転状態と対応)
    val originImageId: Long? = null, // 基準画像のID
    val hashMatchEnabled: Boolean = false, // ON: 知覚ハッシュが近い(=ほぼ同一の)画像のみ表示
    val saturationTolerance: Float = 0f, // 0f〜0.35f。0fはこの項目では絞り込まない
    val brightnessTolerance: Float = 0f, // 0f〜0.35f。0fはこの項目では絞り込まない
    val colorPresetStep: Int = 0, // 0=OFF, 1=A, 2=B, 3=C。「プリセット」ボタンを押すたびにローテーションする
    val aspectRatioOnly: Boolean = false, // ON: 基準画像とアスペクト比が一致する画像のみ表示
    val styleMatchThreshold: Float = 0f, // 0.0〜100.0。0はフィルタなし(RGB5色パレットの一致度による絞り込みをしない)
    val matchedCount: Int = 0, // 直近の絞り込みで一致した枚数(設定変更の効果を確認しやすくするため)

    val snackbarMessage: String? = null
)
