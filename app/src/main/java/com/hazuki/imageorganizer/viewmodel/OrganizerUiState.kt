package com.hazuki.imageorganizer.viewmodel

import com.hazuki.imageorganizer.data.ClassificationGroup
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize

/** 画面モード。上部バーの「画像一覧⇔分類一覧」ボタンで GALLERY / CLASSIFICATION_LIST を行き来し、
 *  分類一覧でタグをタップすると GROUP_DETAIL に入る。 */
enum class ScreenMode {
    GALLERY,            // 通常の画像一覧
    CLASSIFICATION_LIST,// 分類一覧(A〜Zカテゴリごとに島状に並ぶグリッド)
    GROUP_DETAIL        // 1つのグループの中(画像追加/削除ができる)
}

/** 分類一覧グリッドの1タイル分の表示用データ(グループ本体+代表画像)。 */
data class ClassificationTile(
    val group: ClassificationGroup,
    val representative: ImageItem?
)

/** スライドショーの再生間隔設定。ユーザーの要望に合わせて0.3秒〜5.0秒の範囲を定義。 */
enum class SlideshowInterval(val label: String, val seconds: Float) {
    S03("0.3秒", 0.3f),
    S05("0.5秒", 0.5f),
    S08("0.8秒", 0.8f),
    S20("2.0秒", 2.0f),
    S50("5.0秒", 5.0f)
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
    // true: ZIPファイルを開いている(TopBarでは「Zip編集中」ボタンとして表示する)
    val isZipMode: Boolean = false,
    // ボタンをタップした時に表示する詳細情報。フォルダの場合はルートからのフルパス、
    // ZIPの場合は実際のZIPファイル名。
    val folderDetailLabel: String = "Download/未整理",
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
    val slideshowPaused: Boolean = false, // 一時停止中なら true
    val slideshowInterval: SlideshowInterval = SlideshowInterval.S20,
    val slideshowIndex: Int = 0,
    // スライドショー開始時点で「選択中の画像だけ」を表示順のまま抜き出したリスト。
    // 選択が無い状態で開始した場合は、entries(表示中の全件)と同じ内容になる。
    val slideshowEntries: List<ImageItem> = emptyList(),
    val pendingScrollRequest: ScrollRequest? = null, // 一覧をこの位置までスクロールさせるための一時的な指示(スライドショー終了後・フォルダ再読込後・拡張選択終了後など)

    // ---- 拡張選択: 基準画像(origin)に似た画像を絞り込む ----
    // 選択している画像がある時だけ使える(未選択時はそもそも起動しない)。
    val extensionSelectionActive: Boolean = false, // ON/OFF(上部バーの「拡張選択」ボタンの反転状態と対応)
    val originImageId: Long? = null, // 基準画像のID
    // 下層フォルダ（サブフォルダ）も含めて読み込むかどうかのフラグ。
    // デフォルトは false（現在のフォルダ直下のみ読み込み、高速化）。
    // ★ボタン内の「下層📁:OFF / ON」ボタンで切り替え可能。
    val includeSubFolders: Boolean = false,
    val hashMatchEnabled: Boolean = false, // ON: 知覚ハッシュが近い(=ほぼ同一の)画像のみ表示
    val hashMatchThreshold: Int = 10, // ハッシュ許容閾値(1〜40)。値が小さいほど完全一致、大きいほど大まか一致
    val hashProgressText: String? = null, // ハッシュ値計算中の進捗メッセージ(例: "ハッシュ計算中... 120/500枚 (24%)")
    val extensionGroupColors: Map<Long, Char> = emptyMap(), // 重複画像グループごとの色分け(画像ID -> 'A'..'Z')。グリッドの枠線色分けに使用
    val saturationTolerance: Float = 0f, // 0f〜0.35f。0fはこの項目では絞り込まない
    val brightnessTolerance: Float = 0f, // 0f〜0.35f。0fはこの項目では絞り込まない
    val colorPresetStep: Int = 0, // 0=OFF, 1=A, 2=B, 3=C。「プリセット」ボタンを押すたびにローテーションする
    val aspectRatioOnly: Boolean = false, // ON: 基準画像とアスペクト比が一致する画像のみ表示
    val styleMatchThreshold: Float = 0f, // 0.0〜100.0。0はフィルタなし(RGB5色パレットの一致度による絞り込みをしない)
    val matchedCount: Int = 0, // 直近の絞り込みで一致した枚数(設定変更の効果を確認しやすくするため)
    
    // ---- 【※2＋α】拡張選択・グループ比較表示機能 ----
    val isGroupComparisonMode: Boolean = false, // グループ比較モード中かどうか（上部ラベルがライトグリーン #4FFF94 に変化）
    val comparisonBackupEntries: List<ImageItem>? = null, // 比較前のハッシュ一覧バックアップ（長押し復帰時に一瞬で戻す用）
    val comparisonBackupGroupColors: Map<Long, Char> = emptyMap(), // 比較前の枠色マップバックアップ
    val comparisonBookmarkIds: Set<Long> = emptySet(), // 【※2＋α】記憶した類似画像ID（選択が0枚になってもこの画像たちにしおりジャンプ可能）

    val snackbarMessage: String? = null,

    // ---- 手動グルーピング(分類)機能 ----
    val screenMode: ScreenMode = ScreenMode.GALLERY,
    val classificationGroups: List<ClassificationGroup> = emptyList(),
    val classificationTiles: List<ClassificationTile> = emptyList(), // 分類一覧グリッド表示用(カテゴリごとに島状にまとめ済み)
    val groupedImageIds: Map<Long, Char> = emptyMap(), // 画像ID -> 所属カテゴリ(枠色・暗表示の判定に使う)

    val activeGroupKey: String? = null, // GROUP_DETAIL 表示中のグループキー(例:"C_04")
    val groupDetailEntries: List<ImageItem> = emptyList(), // そのグループの画像一覧(グループごとのソート状態を反映済み)
    val groupDetailSelectedIds: Set<Long> = emptySet(), // グループ内画面での「削除対象として選んだ画像」(通常一覧の選択とは別管理)

    // 分類登録・リネーム共用ダイアログ
    val nameDialogVisible: Boolean = false,
    val nameDialogEditingKey: String? = null, // null=新規グループ作成、非null=既存グループのリネーム
    val nameDialogPendingImageIds: List<Long> = emptyList(), // 新規作成時、確定したら所属させる画像ID

    // 画像追加モード(グループ内画面の「画像追加」ボタン→通常一覧に切り替えて同カテゴリのみ選択可能にする)
    val addModeActive: Boolean = false,
    val addModeTargetKey: String? = null, // 追加先グループのキー
    val addModeCategory: Char? = null, // このカテゴリの画像だけ選択可能にする

    // ---- 選択モード・リネーム機能 ----
    val labels: List<String> = emptyList(), // AI認識カテゴリーリスト（約23個）
    val selectedCount: Int = 0, // 現在選択中のファイル枚数
    val currentSelectedLabel: String = "", // 現在選択中のラベル
    val isSelectionMode: Boolean = false, // 選択モード中かどうか

    // ---- フォルダ階層移動（親フォルダへ戻る「..⤴」機能） ----
    val canNavigateUp: Boolean = false, // 上の階層に戻れるかどうか（下層フォルダに潜っている場合のみ true）

    // ---- フォーカス中のファイル（タップ・選択直近のファイル） ----
    // 拡張選択メニューのファイル表示欄（所属フォルダ・ファイル名・○/◎枚目）で利用
    val focusedImageId: Long? = null
) {
    /**
     * 現在フォーカスされている画像オブジェクトを取得
     * 1. focusedImageId があればそれを優先
     * 2. なければ selectedIds の中の1枚、あるいは originImageId
     */
    val focusedImage: ImageItem?
        get() = entries.firstOrNull { it.id == focusedImageId }
            ?: entries.firstOrNull { it.id in selectedIds }
            ?: entries.firstOrNull { it.id == originImageId }

    /**
     * 【フォーカスファイル情報】
     * フォルダ名、ファイル名、およびリネーム規則に基づく「○/◎枚目」の表示情報をまとめます。
     */
    val focusedFileInfo: FocusedFileInfo?
        get() {
            val item = focusedImage ?: return null
            // 所属フォルダ名（下層フォルダ読み込み時は実際の親フォルダ、それ以外は現在のフォルダ名）
            val folderName = item.parentFolderName ?: currentFolderLabel.ifBlank { "フォルダ" }
            val fileName = item.displayName

            // リネーム規則（例: 01_犬_12_33.jpg）の解析
            val renameInfo = com.hazuki.imageorganizer.data.RenameMoveHelper.parseRenamedFileInfo(fileName)
            val countText = if (renameInfo != null) {
                // 同じグループキー（例: 01_犬_12）を持つ画像が一覧内に何枚あるかをリアルタイム算出
                val totalInGroup = entries.count {
                    com.hazuki.imageorganizer.data.RenameMoveHelper.parseRenamedFileInfo(it.displayName)?.groupKey == renameInfo.groupKey
                }
                val currentSeq = com.hazuki.imageorganizer.data.RenameMoveHelper.fromSeqCode(renameInfo.seqCode)
                if (currentSeq != null && totalInGroup > 0) {
                    "${currentSeq}/${totalInGroup}枚目"
                } else {
                    "- / -"
                }
            } else {
                "- / -"
            }

            return FocusedFileInfo(
                folderName = folderName,
                fileName = fileName,
                countText = countText
            )
        }
}

/**
 * 【フォーカス中ファイルの表示情報】
 * 拡張選択メニュー上部のファイル表示欄で使用するデータクラス
 */
data class FocusedFileInfo(
    val folderName: String, // 所属フォルダ名（例: "01_犬"）
    val fileName: String,   // ファイル名（例: "01_犬_12_33.jpg"）
    val countText: String   // グループ内の枚数情報（例: "33/50枚目" または "- / -"）
)


