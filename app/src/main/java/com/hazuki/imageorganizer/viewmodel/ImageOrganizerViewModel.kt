package com.hazuki.imageorganizer.viewmodel

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hazuki.imageorganizer.data.ClassificationGroup
import com.hazuki.imageorganizer.data.ClassificationStore
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.ImageRepository
import com.hazuki.imageorganizer.data.LoadProgress
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.RecentEntryType
import com.hazuki.imageorganizer.data.RecentFoldersStore
import com.hazuki.imageorganizer.data.RenameMoveHelper
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.util.ColorGroupPreset
import com.hazuki.imageorganizer.util.ColorPalette
import com.hazuki.imageorganizer.util.FileOperations
import com.hazuki.imageorganizer.util.ImageGrouping
import com.hazuki.imageorganizer.util.PerceptualHash
import com.hazuki.imageorganizer.util.RenameUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class PendingMediaAction {
    data class Delete(val targets: List<ImageItem>, val restoreScrollIndex: Int) : PendingMediaAction()
    data class Move(val targets: List<ImageItem>, val restoreScrollIndex: Int) : PendingMediaAction()
    data class Rename(val orderedTargets: List<ImageItem>, val restoreScrollIndex: Int, val prefix: String) : PendingMediaAction()
}

class ImageOrganizerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImageRepository(application)
    private val fileOps = FileOperations(application)
    private val recentStore = RecentFoldersStore(application)
    private val classificationStore = ClassificationStore(application)

    private val _uiState = MutableStateFlow(OrganizerUiState())
    val uiState: StateFlow<OrganizerUiState> = _uiState.asStateFlow()

    // ソート済みの生画像リスト(絞り込み前の状態を保持しておく)
    private var allImages: List<ImageItem> = emptyList()

    private var comparingJob: Job? = null
    private var slideshowJob: Job? = null
    private var thresholdDebounceJob: Job? = null
    private var jumpCursorIndex: Int = -1 // 「選択へジャンプ」の巡回位置(entries上のインデックス)

    private var pendingAction: PendingMediaAction? = null
    private val _intentSenderRequest = MutableStateFlow<IntentSender?>(null)
    val intentSenderRequest: StateFlow<IntentSender?> = _intentSenderRequest.asStateFlow()

    // 現在開いているフォルダのソース(再読込時にどちらを呼び直すか判定するため)
    private var currentFolderUri: Uri? = null
    // ユーザーが選択した起点フォルダのTree URI（永続パーミッションを保持するマスターキー）
    private var rootTreeUri: Uri? = null
    // 現在閲覧中のフォルダのDocument ID（サブフォルダに潜っている場合はそのID、ルート時はnull）
    private var currentFolderDocId: String? = null
    private var currentZipDir: java.io.File? = null

    /**
     * 【下層📁ON時のハッシュ重複計算結果キャッシュ】
     * 18,800枚などの大規模な下層画像全体のハッシュ比較結果をメモリに一時保持します。
     * アプリ起動中に★をOFFにして通常一覧を見たり、再度★をONに戻した際、
     * 重い全件再スキャンや再計算を一切走らせず、0.1秒で即座にハッシュ一覧を復元するために使用します。
     */
    private data class SubFolderHashCache(
        val entries: List<ImageItem>,
        val groupColors: Map<Long, Char>,
        val matchedCount: Int,
        val hashThreshold: Int?,
        val saturationTolerance: Float?,
        val brightnessTolerance: Float?,
        val styleThreshold: Float
    ) {
        /** スライダーなどで条件が変わっていないか検証（条件が同じならキャッシュがそのまま使える） */
        fun matchesFilter(state: OrganizerUiState): Boolean {
            val effHash = if (state.hashMatchEnabled) state.hashMatchThreshold else null
            val effSat = if (state.saturationTolerance > 0f) state.saturationTolerance else null
            val effBri = if (state.brightnessTolerance > 0f) state.brightnessTolerance else null
            return hashThreshold == effHash &&
                   saturationTolerance == effSat &&
                   brightnessTolerance == effBri &&
                   styleThreshold == state.styleMatchThreshold
        }
    }
    private var subFolderHashCache: SubFolderHashCache? = null

    /**
     * リネーム直後、次のreloadCurrentFolder()完了時にこの名前群と一致する画像を選択し直すための一時保存。
     * SAF/ローカルではリネームによって画像のID自体が変わりうる(URIやパスが変わるため)ので、
     * IDの一致に頼らず「リネームで付くはずの新ファイル名」との一致で選択を復元する。
     */
    private var pendingSelectByName: Set<String>? = null

    init {
        _uiState.update { it.copy(recentEntries = recentStore.getHistory()) }
        val savedGroups = classificationStore.loadGroups()
        if (savedGroups.isNotEmpty()) {
            // 保存されたグループがある場合、UI表示用の状態を構築する
            rebuildClassificationDerivedState(savedGroups)
        }
        // ラベルリスト（AI認識カテゴリー）を読み込む
        val labels = loadLabels()
        val initialLabel = labels.firstOrNull() ?: "01 未分類"
        _uiState.update { it.copy(labels = labels, currentSelectedLabel = initialLabel) }
        val last = recentStore.getLastOpened()
        if (last == null) {
            loadDocumentsFolder()
        } else {
            viewModelScope.launch {
                val uri = Uri.parse(last.uri)
                val accessible = when (last.type) {
                    RecentEntryType.FOLDER -> repository.isTreeAccessible(uri)
                    RecentEntryType.ZIP -> repository.isZipAccessible(uri)
                }
                if (accessible) {
                    when (last.type) {
                        RecentEntryType.FOLDER -> openFolder(uri, last.label, recordHistory = false)
                        RecentEntryType.ZIP -> openZipFile(uri, last.label, recordHistory = false)
                    }
                } else {
                    // 前回開いていたフォルダ/ZIPが移動・削除されていた場合は履歴から削除し、既定フォルダへ
                    recentStore.removeEntry(last.uri)
                    _uiState.update { it.copy(recentEntries = recentStore.getHistory()) }
                    loadDocumentsFolder()
                }
            }
        }
    }

    /**
     * 履歴から選択した際の入り口。移動・削除済みで開けない場合は、
     * 「見つかりませんでした」を通知した上で履歴からそのエントリを削除する。
     */
    fun openRecentEntry(entry: RecentEntry) {
        val uri = Uri.parse(entry.uri)
        viewModelScope.launch {
            val accessible = when (entry.type) {
                RecentEntryType.FOLDER -> repository.isTreeAccessible(uri)
                RecentEntryType.ZIP -> repository.isZipAccessible(uri)
            }
            if (accessible) {
                when (entry.type) {
                    RecentEntryType.FOLDER -> openFolder(uri, entry.label)
                    RecentEntryType.ZIP -> openZipFile(uri, entry.label)
                }
            } else {
                recentStore.removeEntry(entry.uri)
                _uiState.update {
                    it.copy(
                        recentEntries = recentStore.getHistory(),
                        snackbarMessage = "「${entry.label}」が見つからないため、履歴から削除しました"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------

    // =================================================================
    // 【フォルダ階層ナビゲーション履歴管理】
    // 下層フォルダへ移動した際、親フォルダへ「..⤴」ボタンで戻れるようにスタック（履歴）を管理します。
    // =================================================================

    /** 親フォルダへの戻りナビゲーション用データクラス（URI、表示ラベル、DocIdを保持） */
    private data class NavFolderHistory(
        val uri: Uri?,
        val label: String,
        val docId: String? = null
    )

    /** フォルダ階層の戻り履歴スタック（下層に潜るたびに現在の親フォルダ情報を退避） */
    private val folderBackStack = mutableListOf<NavFolderHistory>()

    /**
     * 【※1：範囲一括選択用の起点画像ID】
     * 選択モード中にユーザーが直近でタップして選択した画像のIDを記憶します。
     * 別の画像を長押しした際、この起点Aから長押し画像Bまでの全画像を一括選択するために使用します。
     */
    private var lastSelectedImageId: Long? = null

    /** 現在のフォルダを戻り履歴スタックに記録し、戻るボタンを有効化 */
    private fun pushCurrentFolderToBackStack() {
        val currentLabel = _uiState.value.currentFolderLabel
        folderBackStack.add(NavFolderHistory(currentFolderUri, currentLabel, currentFolderDocId))
        _uiState.update { it.copy(canNavigateUp = true) }
    }

    /** 戻り履歴スタックを初期化（起点となる新しいフォルダをユーザーが開いた際などにリセット） */
    private fun clearFolderBackStack() {
        folderBackStack.clear()
        _uiState.update { it.copy(canNavigateUp = false) }
    }

    /**
     * @param scrollTarget 読込完了後に一覧をスクロールさせる位置。
     *   フォルダを新しく開く操作(起動時の自動復元・フォルダ選択・履歴選択)では既定値の0(先頭)のままでよい。
     *   移動/削除/リネーム後の再読込(reloadCurrentFolder経由)では、実行前の表示位置を渡すことで
     *   一覧の見ていた位置をなるべく維持する。
     * @param clearBackStack 起点フォルダとして開く場合はtrue（履歴スタックを初期化）
     */
    fun loadDocumentsFolder(scrollTarget: Int = 0, clearBackStack: Boolean = true) {
        // 【ハッシュ計算の即時ストップ】別フォルダを読み込むため、実行中のハッシュ計算・比較を中断
        cancelComparing()

        if (clearBackStack) {
            clearFolderBackStack()
            subFolderHashCache = null // フォルダ切り替えのためキャッシュ解放
        }
        currentFolderUri = null
        currentZipDir = null
        _uiState.update {
            it.copy(
                isLoading = true,
                isStreaming = true,
                currentFolderLabel = "Download/未整理",
                isZipMode = false,
                folderDetailLabel = "Download/未整理",
                folderTotalCount = 0
            )
        }
        allImages = emptyList()
        val includeSubFolders = _uiState.value.includeSubFolders
        viewModelScope.launch {
            repository.loadDefaultFolderStreaming(includeSubFolders = includeSubFolders)
                .onCompletion {
                    _uiState.update { s -> s.copy(isStreaming = false) }
                    applyDisplayList()
                    requestScroll(scrollTarget)
                }
                .collect { progress -> onBatchReceived(progress) }
        }
    }

    fun openFolder(
        treeUri: Uri,
        label: String,
        recordHistory: Boolean = true,
        scrollTarget: Int = 0,
        clearBackStack: Boolean = true,
        targetDocId: String? = null,
        // 【特定画像へのスクロールターゲット】フォルダ読込完了後に、この画像IDの位置へ最上部スクロールします
        targetImageId: Long? = null
    ) {
        // 【ハッシュ計算の即時ストップ】フォルダ移動時に実行中のハッシュ計算・重複比較ジョブを即座に中断
        cancelComparing()

        if (clearBackStack) {
            clearFolderBackStack()
            subFolderHashCache = null // フォルダ切り替えのためキャッシュ解放
            // 起点フォルダの権限（マスターキー）として保持
            rootTreeUri = treeUri
            currentFolderDocId = null
        } else {
            if (rootTreeUri == null) {
                rootTreeUri = treeUri
            }
            currentFolderDocId = targetDocId
        }
        currentFolderUri = treeUri
        currentZipDir = null
        val includeSubFolders = _uiState.value.includeSubFolders
        _uiState.update {
            it.copy(
                isLoading = true,
                isStreaming = true,
                currentFolderLabel = label,
                isZipMode = false,
                folderDetailLabel = buildReadableFullPath(treeUri, currentFolderDocId),
                folderTotalCount = 0
            )
        }
        allImages = emptyList()
        if (recordHistory) {
            recentStore.recordOpened(RecentEntry(RecentEntryType.FOLDER, treeUri.toString(), label, System.currentTimeMillis()))
            _uiState.update { it.copy(recentEntries = recentStore.getHistory()) }
        }
        viewModelScope.launch {
            try {
                // マスターキー(rootTreeUri)の権限傘下として対象フォルダ(currentFolderDocId)を読み込む
                val baseTree = rootTreeUri ?: treeUri
                repository.loadFromTreeStreaming(
                    treeUri = baseTree,
                    includeSubFolders = includeSubFolders,
                    folderDocId = currentFolderDocId
                )
                    .onCompletion {
                        _uiState.update { s -> s.copy(isStreaming = false) }
                        applyDisplayList()
                        // targetImageIdが指定されている場合はその画像の位置へ、なければscrollTargetへスクロール
                        val finalScroll = if (targetImageId != null) {
                            val foundIndex = _uiState.value.entries.indexOfFirst { it.id == targetImageId }
                            if (foundIndex >= 0) foundIndex else scrollTarget
                        } else {
                            scrollTarget
                        }
                        requestScroll(finalScroll)
                    }
                    .collect { progress -> onBatchReceived(progress) }
            } catch (e: SecurityException) {
                // 前回のアクセス権限が失効している場合(端末再起動などでURI許可が切れた場合)は既定フォルダへフォールバック
                _uiState.update { it.copy(snackbarMessage = "以前のフォルダにアクセスできませんでした") }
                loadDocumentsFolder()
            }
        }
    }

    /** ZIP書庫を選択した際の読込。アプリキャッシュへ展開してから通常フォルダと同様に扱う。 */
    fun openZipFile(zipUri: Uri, label: String, recordHistory: Boolean = true, scrollTarget: Int = 0) {
        // 【ハッシュ計算の即時ストップ】ZIP展開・読込前にハッシュ計算を即座に中断
        cancelComparing()

        clearFolderBackStack()
        subFolderHashCache = null // ZIP切り替えのためキャッシュ解放
        currentFolderUri = null
        _uiState.update {
            it.copy(
                isLoading = true,
                isStreaming = true,
                // 長いZIPファイル名が上部メニューを圧迫しないよう、普段は固定の短い文言にしておく。
                // 実際のファイル名はfolderDetailLabelに入れ、ボタンをタップした時だけ表示する。
                currentFolderLabel = "Zip編集中",
                isZipMode = true,
                folderDetailLabel = label,
                folderTotalCount = 0
            )
        }
        allImages = emptyList()
        if (recordHistory) {
            recentStore.recordOpened(RecentEntry(RecentEntryType.ZIP, zipUri.toString(), label, System.currentTimeMillis()))
            _uiState.update { it.copy(recentEntries = recentStore.getHistory()) }
        }
        viewModelScope.launch {
            try {
                val dir = repository.extractZipToCache(zipUri)
                currentZipDir = dir
                repository.loadFromLocalDirectoryStreaming(dir)
                    .onCompletion {
                        _uiState.update { s -> s.copy(isStreaming = false) }
                        applyDisplayList()
                        requestScroll(scrollTarget)
                    }
                    .collect { progress -> onBatchReceived(progress) }
            } catch (e: SecurityException) {
                _uiState.update { it.copy(snackbarMessage = "以前のZIPファイルにアクセスできませんでした") }
                loadDocumentsFolder()
            }
        }
    }

    /**
     * 現在開いているフォルダを、そのソースのまま再読込する(移動/削除/リネーム後の更新用)。
     * @param scrollTarget 再読込完了後に一覧を戻すスクロール位置(実行前の表示位置)。
     */
    private fun reloadCurrentFolder(scrollTarget: Int = 0) {
        val zipDir = currentZipDir
        val uri = currentFolderUri
        when {
            zipDir != null -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isStreaming = true) }
                    repository.loadFromLocalDirectoryStreaming(zipDir)
                        .onCompletion {
                            _uiState.update { s -> s.copy(isStreaming = false) }
                            applyDisplayList()
                            requestScroll(scrollTarget)
                        }
                        .collect { progress -> onBatchReceived(progress) }
                }
            }
            uri != null -> openFolder(
                treeUri = uri,
                label = _uiState.value.currentFolderLabel,
                recordHistory = false,
                scrollTarget = scrollTarget,
                clearBackStack = false,
                targetDocId = currentFolderDocId
            )
            else -> loadDocumentsFolder(scrollTarget = scrollTarget, clearBackStack = false)
        }
    }

    /** 一覧をこの位置までスクロールさせるよう、Compose側(MainScreen)に一時的な指示を出す */
    private fun requestScroll(index: Int) {
        _uiState.update { it.copy(pendingScrollRequest = ScrollRequest(index.coerceAtLeast(0))) }
    }

    /**
     * SAFで選択したフォルダのUriから、ルートからの読みやすいフルパスを組み立てる。
     * SAFのドキュメントIDは "primary:DCIM/Camera" のような「ボリューム名:相対パス」形式になっているため、
     * これを "内部ストレージ/DCIM/Camera" のような表示用の文字列に変換する。
     * (取得に失敗した場合は、フォルダ名だけでも表示できるようフォールバックする)
     */
    private fun buildReadableFullPath(treeUri: Uri, docIdOverride: String? = null): String {
        return try {
            val docId = docIdOverride ?: DocumentsContract.getTreeDocumentId(treeUri)
            val colonIndex = docId.indexOf(':')
            if (colonIndex < 0) return docId
            val volume = docId.substring(0, colonIndex)
            val relativePath = docId.substring(colonIndex + 1)
            // "primary" は端末本体のストレージを指す。それ以外はSDカード等の外部ストレージ。
            val volumeLabel = if (volume == "primary") "内部ストレージ" else "SDカード"
            if (relativePath.isBlank()) volumeLabel else "$volumeLabel/$relativePath"
        } catch (e: Exception) {
            treeUri.lastPathSegment ?: ""
        }
    }

    /**
     * ストリーミング中の各バッチ受信時に呼ぶ。読み込み中は「読み込み済み/総数」の表示更新だけ行い、
     * 重い処理(ハッシュ・代表色計算)は走らせない(読み込み完了後にonCompletionで1回だけ実行する)。
     */
    private fun onBatchReceived(progress: LoadProgress) {
        allImages = progress.images
        _uiState.update {
            it.copy(
                totalImageCount = progress.images.size,
                folderTotalCount = progress.totalCount,
                isLoading = false
            )
        }
        // 読み込み中は軽量な一覧表示のみ更新する(entriesを空のままにして
        // 「画像が見つかりませんでした」の誤表示が出ないようにするため)。
        val sorted = sortedImages(_uiState.value.sortOption)
        _uiState.update { it.copy(entries = sorted) }
    }

    // ------------------------------------------------------------------
    // ソート / 一覧表示
    // ------------------------------------------------------------------

    private fun sortedImages(sortOption: SortOption): List<ImageItem> = sortImageList(allImages, sortOption)

    /**
     * 任意の画像リストを指定のソート条件で並べ替える共通ヘルパー。
     * ・通常の一覧(allImages)のソート
     * ・グループ内画面(GroupDetailScreen)でのグループごとのソート
     * ・分類一覧でのカテゴリ表示順の判定
     * の3箇所で共用する。
     */
    private fun sortImageList(images: List<ImageItem>, sortOption: SortOption): List<ImageItem> {
        return when (sortOption) {
            // リネーム済み（_nn_mm形式）のファイルのみを抽出し、名前順（昇順）でソート
            SortOption.GROUP_SEQ_ASC -> images
                .filter { RenameMoveHelper.parseRenamedFileInfo(it.displayName) != null }
                .sortedBy { it.displayName.lowercase() }
            SortOption.NAME_ASC -> images.sortedBy { it.displayName.lowercase() }
            SortOption.NAME_DESC -> images.sortedByDescending { it.displayName.lowercase() }
            SortOption.SIZE_ASC -> images.sortedBy { it.sizeBytes }
            SortOption.SIZE_DESC -> images.sortedByDescending { it.sizeBytes }
            SortOption.DATE_ASC -> images.sortedBy { it.dateModifiedEpochSec }
            SortOption.DATE_DESC -> images.sortedByDescending { it.dateModifiedEpochSec }
            SortOption.TAKEN_ASC -> images.sortedBy { it.effectiveTakenEpochMillis }
            SortOption.TAKEN_DESC -> images.sortedByDescending { it.effectiveTakenEpochMillis }
            SortOption.TYPE_ASC -> images.sortedBy { it.extension }
            SortOption.TYPE_DESC -> images.sortedByDescending { it.extension }
        }
    }

    /**
     * 一覧の中身を決める中心の処理。
     * ・拡張選択ON: applyExtensionSelectionFilter() に任せる(基準画像または全体グルーピングで絞り込み)
     * ・「🏷️ グループ連番（枠色別）↓」: リネーム形式ファイルのみ抽出＋グループごとに枠色を付与
     * ・その他のソート: 通常の並び替え表示
     */
    private fun applyDisplayList() {
        reconcileSelection()
        reconcileClassification()
        val state = _uiState.value
        val sorted = sortedImages(state.sortOption)

        if (state.extensionSelectionActive) {
            // 【グループ比較モード中】フォルダ再読込後もハッシュ再計算に飛ばず、グループ連番一覧を維持する
            if (state.isGroupComparisonMode) {
                val groupSeqImages = sortImageList(allImages, SortOption.GROUP_SEQ_ASC)
                val missingSimilarImages = allImages.filter { it.id in state.comparisonBookmarkIds && groupSeqImages.none { g -> g.id == it.id } }
                val groupEntries = (groupSeqImages + missingSimilarImages).distinctBy { it.id }.ifEmpty {
                    sortImageList(allImages, SortOption.NAME_ASC)
                }
                val groupColors = mutableMapOf<Long, Char>()
                val groupKeyIndexMap = mutableMapOf<String, Int>()
                for (img in groupEntries) {
                    val info = RenameMoveHelper.parseRenamedFileInfo(img.displayName) ?: continue
                    val groupIdx = groupKeyIndexMap.getOrPut(info.groupKey) { groupKeyIndexMap.size }
                    val colorCategory = ('A'.code + (groupIdx % 26)).toChar()
                    groupColors[img.id] = colorCategory
                }
                _uiState.update {
                    it.copy(
                        entries = groupEntries,
                        extensionGroupColors = groupColors,
                        matchedCount = groupEntries.size
                    )
                }
                return
            }

            applyExtensionSelectionFilter(sorted, state)
            return
        }

        comparingJob?.cancel()

        // 「🏷️ グループ連番（枠色別）↓」ソートが選ばれている場合
        if (state.sortOption == SortOption.GROUP_SEQ_ASC) {
            // 【自動フォールバック処理】
            // グループ連番（_nn_mm形式）の画像がフォルダ内に1枚も存在しない場合、
            // 「画像が見つかりませんでした」画面で作業が止まるのを防ぐため、
            // 自動的にソート順を「日付 ↑（古い順）」へ切り替えて全画像を一覧表示します。
            if (sorted.isEmpty() && allImages.isNotEmpty()) {
                val fallbackOption = SortOption.DATE_ASC
                val fallbackSorted = sortedImages(fallbackOption)
                _uiState.update {
                    it.copy(
                        sortOption = fallbackOption, // ソート順を「日付 ↑」に更新
                        entries = fallbackSorted,    // 日付順にソートされた全画像を一覧にセット
                        isComparing = false,
                        matchedCount = 0,
                        extensionGroupColors = emptyMap(),
                        snackbarMessage = "グループ連番の画像がないため、日付↑で表示しました"
                    )
                }
                return
            }

            val groupColors = mutableMapOf<Long, Char>()
            val groupKeyIndexMap = mutableMapOf<String, Int>()

            for (img in sorted) {
                val info = RenameMoveHelper.parseRenamedFileInfo(img.displayName) ?: continue
                // グループ一意キー（例: "01犬_01"）ごとに出現順でインデックス（0, 1, 2...）を割り当てる
                val groupIdx = groupKeyIndexMap.getOrPut(info.groupKey) { groupKeyIndexMap.size }
                // 26色パレット（A〜Z）をグループ番号順に循環割り当て
                val colorCategory = ('A'.code + (groupIdx % 26)).toChar()
                groupColors[img.id] = colorCategory
            }

            val msg = if (sorted.isEmpty()) "リネーム形式（_nn_mm）の画像が見つかりませんでした" else null

            _uiState.update {
                it.copy(
                    entries = sorted,
                    isComparing = false,
                    matchedCount = sorted.size,
                    extensionGroupColors = groupColors,
                    snackbarMessage = msg
                )
            }
            return
        }

        // それ以外の通常ソート時は枠線色をリセット
        _uiState.update {
            it.copy(
                entries = sorted,
                isComparing = false,
                matchedCount = 0,
                extensionGroupColors = emptyMap()
            )
        }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option, sortSheetVisible = false) }
        if (option == SortOption.TAKEN_ASC || option == SortOption.TAKEN_DESC) {
            // 撮影日ソートの時だけEXIFを読みに行く(他のソートでは不要な重い処理を避ける)
            viewModelScope.launch {
                allImages = repository.ensureDateTaken(allImages)
                applyDisplayList()
            }
        } else {
            applyDisplayList()
        }
    }

    fun toggleSortSheet(visible: Boolean) {
        _uiState.update { it.copy(sortSheetVisible = visible) }
    }

    fun setThumbnailSize(size: ThumbnailSize) {
        _uiState.update { it.copy(thumbnailSize = size) }
    }

    // ------------------------------------------------------------------
    // 拡張選択(基準画像に似た画像を絞り込む)
    // ------------------------------------------------------------------

    /** ハッシュ判定(ほぼ同一画像)の許容ハミング距離。値が小さいほど厳しい判定になる。 */
    private val HASH_MATCH_THRESHOLD = 10

    /**
     * 上部バーの「拡張選択」ボタン。
     * ・OFF→ON: 選択中の画像を基準(origin)にする。選択が1枚ならその画像自身、
     *   2枚以上なら(ソート表示上での)最初の画像を基準にする。未選択なら起動しない。
     * ・ON→OFF: フィルタを解除し、通常の一覧表示に戻す。基準画像(origin)が見える位置までスクロールする。
     */
    /**
     * 上部バーの「拡張選択」ボタン。
     * ・OFF→ON: フォルダ内全体の重複・類似画像を検索する。
     *   画像を選択している場合はその画像を基準画像(origin)として優先表示し、
     *   未選択の場合でもフォルダ全体の重複画像を総当たりで抽出する。
     * ・ON→OFF: フィルタを解除し、通常の一覧表示に戻す。
     */
    fun toggleExtensionSelection() {
        val state = _uiState.value

        // 【※2：下層フォルダ潜り中からの★ワープ復帰】
        // 下層📁ONのまとめ一覧から下層フォルダへ一時ジャンプしている最中に「★」を押した場合は、
        // ★を解除してしまうのではなく、ルートフォルダの★ハッシュ値一覧へ一瞬で復帰します！
        if (state.isSubFolderGroupMode) {
            exitSubFolderGroupMode()
            return
        }

        if (state.extensionSelectionActive) {
            disableExtensionSelection()
            return
        }
        // 選択画像がある場合は基準画像(origin)として保持し、未選択ならフォルダ全件を対象にする
        val origin = state.entries.firstOrNull { it.id in state.selectedIds } ?: selectedImages().firstOrNull()

        // 【下層📁状態のスマート引き継ぎ】
        // 直前に下層📁ONだった場合、または既に下層のハッシュキャッシュが存在する場合は
        // 「下層📁:ON」を維持して即座にキャッシュ復旧できるようにします。
        val keepSubFolders = state.includeSubFolders || (subFolderHashCache != null)

        _uiState.update {
            it.copy(
                extensionSelectionActive = true,
                originImageId = origin?.id,
                focusedImageId = origin?.id, // 拡張選択開始時は基準画像をフォーカス対象に設定
                includeSubFolders = keepSubFolders, // 下層📁ONまたはキャッシュがあればONを維持！
                hashMatchEnabled = true, // 最初からハッシュ比較をONにして重複を探す
                hashMatchThreshold = 10, // デフォルト閾値10
                saturationTolerance = 0f,
                brightnessTolerance = 0f,
                colorPresetStep = 0,
                aspectRatioOnly = false,
                styleMatchThreshold = 0f,
                extensionGroupColors = emptyMap(),
                hashProgressText = null
            )
        }
        applyDisplayList()
    }

    /**
     * 「下層📁:OFF / 下層📁:ON」ボタンの切り替え。
     * OFF ➔ ON: 下層フォルダ（サブフォルダ）も含めた全画像を再読込し、重複・ハッシュ計算を全体で実行
     * ON ➔ OFF: 現在のフォルダ直下の画像のみに絞り直して再読込
     */
    fun toggleIncludeSubFolders() {
        val newInclude = !_uiState.value.includeSubFolders
        _uiState.update { it.copy(includeSubFolders = newInclude) }
        // 【下層📁切り替え時のキャッシュ解放】
        // 下層を含めるかどうかが切り替わったため、キャッシュをクリアしてメモリを解放
        subFolderHashCache = null
        // 現在のフォルダを即座に再読込
        reloadCurrentFolder()
    }

    /**
     * 【ハッシュ計算・重複比較の即時中断】
     * 実行中のハッシュ計算・重複比較ジョブを即座に安全中断し、
     * 画面上部の進捗オーバーレイ（○○/○○枚 ○％）をクリアします。
     */
    fun cancelComparing() {
        comparingJob?.cancel()
        comparingJob = null
        _uiState.update {
            it.copy(
                isComparing = false,
                hashProgressText = null
            )
        }
    }

    /**
     * 拡張選択（★）を解除し、通常の一覧表示に戻す。
     *
     * 【追跡＆最上部スクロール動作】
     * 1. 追跡対象のターゲット画像を特定：
     *    - 複数選択されている場合は「ソート順で一番先頭にある選択ファイル」を追跡
     *    - 選択されていない場合は「直前にタップ（フォーカス）したファイル」を追跡
     * 2. ターゲット画像が現在と異なるサブフォルダ（下層フォルダ）に属している場合：
     *    - 現在のフォルダを戻り履歴スタックに退避（「..⤴」ボタンで元の親フォルダへ戻れるようにする）
     *    - ターゲット画像のサブフォルダへ移動して読み込み、完了時にその画像を一覧の最上部にスクロール
     * 3. ターゲット画像が現在のフォルダ内にある場合：
     *    - フォルダ移動は行わず、通常一覧の中でその画像を最上部にスクロール
     */
    private fun disableExtensionSelection() {
        // 【ハッシュ計算の即時ストップ】「★」ボタンで解除した瞬間に、裏のハッシュ計算を即座に中断
        cancelComparing()

        val currentState = _uiState.value

        // 【※2：下層フォルダ移動中グループ表示からの安全脱出】
        // 下層フォルダへ一時的に潜っていた最中に「★」ボタンで拡張選択が解除された場合、
        // 親フォルダのスタックを取り出して親フォルダへ復帰させ、フォルダ名表示も元に戻します
        if (currentState.isSubFolderGroupMode) {
            exitSubFolderGroupMode()
            _uiState.update {
                it.copy(
                    extensionSelectionActive = false,
                    originImageId = null,
                    hashMatchEnabled = false,
                    saturationTolerance = 0f,
                    brightnessTolerance = 0f,
                    colorPresetStep = 0,
                    aspectRatioOnly = false,
                    styleMatchThreshold = 0f,
                    extensionGroupColors = emptyMap(),
                    hashProgressText = null,
                    isGroupComparisonMode = false,
                    isSubFolderGroupMode = false,
                    comparisonBackupEntries = null,
                    comparisonBackupGroupColors = emptyMap()
                )
            }
            applyDisplayList()
            return
        }

        // ① 追跡対象のターゲット画像を特定
        // 複数選択時はソート順で一番最初に選択されている画像。未選択時はフォーカス中画像、それもなければ起動時の基準画像
        val targetImage = currentState.entries.firstOrNull { it.id in currentState.selectedIds }
            ?: currentState.focusedImage
            ?: currentState.entries.firstOrNull { it.id == currentState.originImageId }

        val baseTree = rootTreeUri ?: currentFolderUri

        _uiState.update {
            it.copy(
                extensionSelectionActive = false,
                originImageId = null,
                hashMatchEnabled = false,
                saturationTolerance = 0f,
                brightnessTolerance = 0f,
                colorPresetStep = 0,
                aspectRatioOnly = false,
                styleMatchThreshold = 0f,
                extensionGroupColors = emptyMap(),
                hashProgressText = null,
                // グループ比較モードも安全に解除
                isGroupComparisonMode = false,
                comparisonBackupEntries = null,
                comparisonBackupGroupColors = emptyMap()
            )
        }

        // ② 現在のフォルダのまま通常表示へ反映
        // ※下層フォルダへの個別移動は「画像長押し」で行えるため、
        //   ★解除時は勝手に下層フォルダへジャンプせず、今いるフォルダ（ルート）の通常一覧へ安全に戻ります。
        applyDisplayList()

        // ターゲット画像が現在のフォルダ内に存在する場合は、その位置へスクロール
        if (targetImage != null) {
            val restoredIndex = _uiState.value.entries.indexOfFirst { it.id == targetImage.id }
            if (restoredIndex >= 0) {
                requestScroll(restoredIndex)
            }
        }
    }

    /**
     * 拡張選択のフィルタを一覧に反映する。
     *
     * 【処理の流れ】
     * 1. フォルダ全体の画像について知覚ハッシュ・彩度明度・代表色パレットをバックグラウンド計算。
     *    計算の進捗状況（〇/〇枚 〇％）をリアルタイムにUIへ通知する。
     * 2. ImageGrouping.group を使用し、フォルダ内の全画像ペアを総当たり比較して
     *    条件を満たす重複・類似画像グループ（2枚以上）をすべて抽出する。
     * 3. 似ている画像同士が隣り合うようにリストに並べ替え、単独画像は除外する。
     * 4. グループごとに見分けやすい枠線の色（'A'〜'Z'）を割り当ててグリッドに表示する。
     */
    private fun applyExtensionSelectionFilter(sorted: List<ImageItem>, state: OrganizerUiState) {
        comparingJob?.cancel()

        // 【下層📁ON時のキャッシュ即時復旧（待ち時間ゼロ機能）】
        // 下層📁ONの状態で既にハッシュ計算済みのキャッシュが存在し、かつスライダー等のフィルタ条件が変わっていない場合は、
        // 18,800枚の重い再計算を完全にスキップして0.1秒で即座にハッシュ一覧を復元します！
        val cache = subFolderHashCache
        if (state.includeSubFolders && cache != null && cache.matchesFilter(state)) {
            _uiState.update {
                it.copy(
                    entries = cache.entries,
                    isComparing = false,
                    matchedCount = cache.matchedCount,
                    extensionGroupColors = cache.groupColors,
                    hashProgressText = null
                )
            }
            return
        }

        comparingJob = viewModelScope.launch {
            _uiState.update { it.copy(isComparing = true) }

            // ハッシュ・彩度明度・代表色パレットが未計算の画像だけ、ここでバックグラウンド計算する
            val analyzed = repository.computeHashes(sorted) { done, total ->
                if (total > 0) {
                    val percent = (done * 100) / total
                    _uiState.update {
                        it.copy(hashProgressText = "ハッシュ計算中... $done/${total}枚 ($percent%)")
                    }
                }
            }

            val current = _uiState.value
            // ハッシュ値の閾値（スイッチON時は指定閾値1〜40、OFF時はnullでハッシュ判定スキップ）
            val hashThreshold = if (current.hashMatchEnabled) current.hashMatchThreshold else null
            val satTolerance = if (current.saturationTolerance > 0f) current.saturationTolerance else null
            val briTolerance = if (current.brightnessTolerance > 0f) current.brightnessTolerance else null
            val styleThreshold = current.styleMatchThreshold

            // 【ハッシュ値OFFかつ全条件未指定時の処理】
            // ハッシュ値スイッチがOFFにされ、かつ他のフィルタ条件（彩度・明度・RGB5色）も指定されていない場合は、
            // フィルタをかけずに全画像をそのまま通常の一覧として表示します。
            val isNoFilter = !current.hashMatchEnabled && satTolerance == null && briTolerance == null && styleThreshold <= 0f
            if (isNoFilter) {
                _uiState.update {
                    it.copy(
                        entries = sorted, // フォルダ内の全画像を表示
                        isComparing = false,
                        matchedCount = 0,
                        extensionGroupColors = emptyMap(),
                        hashProgressText = null
                    )
                }
                return@launch
            }

            // ハッシュ値スイッチがONの場合のみ、その閾値を適用
            val effectiveHashThreshold = hashThreshold

            // フォルダ内の全画像を総当たり比較して、2枚以上の類似グループを抽出
            val groups = ImageGrouping.group(
                images = analyzed,
                threshold = effectiveHashThreshold,
                saturationTolerance = satTolerance,
                brightnessTolerance = briTolerance,
                styleMatchThreshold = styleThreshold
            )

            // グループごとに枠線色（'A'..'Z'）を割り当て、似ている画像同士が隣り合うように並べる
            val groupColors = mutableMapOf<Long, Char>()
            val orderedEntries = mutableListOf<ImageItem>()

            // 基準画像(origin)がある場合は、その画像が含まれるグループを先頭に配置
            val originId = current.originImageId
            val sortedGroupList = if (originId != null) {
                groups.values.sortedByDescending { groupList -> groupList.any { it.id == originId } }
            } else {
                groups.values.toList()
            }

            sortedGroupList.forEachIndexed { groupIdx, groupImages ->
                // アルファベット26色（A〜Z）をグループ番号順に循環割り当て
                val colorCategory = ('A'.code + (groupIdx % 26)).toChar()
                for (img in groupImages) {
                    groupColors[img.id] = colorCategory
                    orderedEntries.add(img)
                }
            }

            // 【ハッシュ値判定で該当なしの場合の自動切り替え処理】
            // ハッシュ値比較がONだったが、条件に該当する重複・類似画像が1組もなかった（0件）場合、
            // 「画像が見つかりませんでした」画面になるのを防ぎ、
            // ハッシュ値スイッチを自動でOFFに切り替えて「該当する画像はありません」と案内し、
            // フォルダ内の全画像をそのまま一覧表示します。
            if (current.hashMatchEnabled && orderedEntries.isEmpty() && sorted.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        hashMatchEnabled = false, // ハッシュ値スイッチを自動でOFFに切り替え
                        entries = sorted,          // フォルダ内の全画像をすべて表示
                        isComparing = false,
                        matchedCount = 0,
                        extensionGroupColors = emptyMap(),
                        hashProgressText = null,
                        snackbarMessage = "該当する画像はありません" // 通知メッセージを表示
                    )
                }
                return@launch
            }

            // 【下層📁ON時の計算結果をキャッシュに保存】
            // 次回★をONにした際に瞬時に再表示できるようにメモリにキープ
            if (current.includeSubFolders) {
                subFolderHashCache = SubFolderHashCache(
                    entries = orderedEntries,
                    groupColors = groupColors,
                    matchedCount = orderedEntries.size,
                    hashThreshold = effectiveHashThreshold,
                    saturationTolerance = satTolerance,
                    brightnessTolerance = briTolerance,
                    styleThreshold = styleThreshold
                )
            }

            _uiState.update {
                it.copy(
                    entries = orderedEntries,
                    isComparing = false,
                    matchedCount = orderedEntries.size,
                    extensionGroupColors = groupColors,
                    hashProgressText = null
                )
            }
        }
    }

    fun toggleHashMatch(enabled: Boolean) {
        _uiState.update { it.copy(hashMatchEnabled = enabled) }
        applyDisplayList()
    }

    /** ハッシュ値の許容閾値スライダー(1〜17)。操作中に毎フレーム再計算が走らないようデバウンスする。 */
    fun setHashMatchThreshold(value: Int) {
        val clamped = value.coerceIn(1, 17)
        _uiState.update { it.copy(hashMatchThreshold = clamped) }
        debounceApply()
    }

    fun toggleAspectRatioOnly(enabled: Boolean) {
        _uiState.update { it.copy(aspectRatioOnly = enabled) }
        applyDisplayList()
    }

    /** 彩度スライダー(0.0〜0.35)。ドラッグ中に毎フレーム再フィルタが走らないようデバウンスする。 */
    fun setSaturationTolerance(value: Float) {
        val clamped = value.coerceIn(0f, 0.35f)
        _uiState.update { it.copy(saturationTolerance = clamped) }
        debounceApply()
    }

    /** 明度スライダー(0.0〜0.35)。ドラッグ中に毎フレーム再フィルタが走らないようデバウンスする。 */
    fun setBrightnessTolerance(value: Float) {
        val clamped = value.coerceIn(0f, 0.35f)
        _uiState.update { it.copy(brightnessTolerance = clamped) }
        debounceApply()
    }

    /** スタイル一致度スライダー(RGB5色、0.0〜100.0)。ドラッグ中に毎フレーム再フィルタが走らないようデバウンスする。 */
    fun setStyleMatchThreshold(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        _uiState.update { it.copy(styleMatchThreshold = clamped) }
        debounceApply()
    }

    private fun debounceApply() {
        thresholdDebounceJob?.cancel()
        thresholdDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            applyDisplayList()
        }
    }

    /**
     * 「プリセット」ボタン。押すたびに OFF→A→B→C→OFF... とローテーションし、
     * 彩度・明度スライダーの値をその数値へ一気に動かす(ワンタップの近道)。
     * その後も彩度・明度スライダー自体は個別にドラッグして微調整できる。
     */
    fun cyclePresetStep() {
        val nextStep = (_uiState.value.colorPresetStep + 1) % 4
        val (sat, bri) = when (nextStep) {
            1 -> ColorGroupPreset.A.saturationTolerance to ColorGroupPreset.A.brightnessTolerance
            2 -> ColorGroupPreset.B.saturationTolerance to ColorGroupPreset.B.brightnessTolerance
            3 -> ColorGroupPreset.C.saturationTolerance to ColorGroupPreset.C.brightnessTolerance
            else -> 0f to 0f
        }
        _uiState.update { it.copy(colorPresetStep = nextStep, saturationTolerance = sat, brightnessTolerance = bri) }
        applyDisplayList()
    }

    // ------------------------------------------------------------------
    // 選択モード
    // ------------------------------------------------------------------

    fun startSelection(imageId: Long) {
        _uiState.update { it.copy(
            selectionMode = true,
            selectedIds = setOf(imageId),
            focusedImageId = imageId, // 直近タップした画像をフォーカス対象にする
            // ---- 新しい選択状態も更新（ラベルは現在の選択を維持する） ----
            isSelectionMode = true,
            selectedCount = 1
        ) }
    }

    fun toggleSelected(imageId: Long) {
        _uiState.update { state ->
            val newSet = state.selectedIds.toMutableSet()
            if (!newSet.add(imageId)) {
                // 選択を解除した場合
                newSet.remove(imageId)
                // 解除した画像が直近選択IDだった場合は、残っている選択画像のいずれか（またはnull）を起点に更新
                if (lastSelectedImageId == imageId) {
                    lastSelectedImageId = newSet.lastOrNull()
                }
            } else {
                // 新たに選択した画像を「範囲一括選択の起点A」として記憶
                lastSelectedImageId = imageId
            }
            val stillSelecting = newSet.isNotEmpty()
            state.copy(
                selectedIds = newSet,
                selectionMode = stillSelecting,
                focusedImageId = imageId, // 直近タップした画像をフォーカス対象にする
                // ---- 新しい選択状態も更新 ----
                isSelectionMode = stillSelecting,
                selectedCount = newSet.size
            )
        }
    }

    /**
     * 【フォーカス画像の更新】
     * 拡張選択メニュー上部のファイル情報欄に即座に反映させるため、
     * 画像タップ時にその画像のIDを記録します。
     */
    fun setFocusedImage(imageId: Long) {
        _uiState.update { it.copy(focusedImageId = imageId) }
    }

    /**
     * 【カテゴリーラベルタップによる選択モードの切り替え】
     * メニュー上部のカテゴリーラベルをタップした際に呼ばれます。
     * - すでに選択モード中なら、選択をすべてクリアして通常モードに戻します。
     * - 通常モードなら、最初は「0枚選択」の状態で選択モードを開始します（パターンB）。
     */
    fun toggleSelectionMode() {
        val currentIsSelecting = _uiState.value.isSelectionMode || _uiState.value.selectionMode
        if (currentIsSelecting) {
            // すでに選択モード中の場合は、選択解除して通常表示に戻す
            clearSelection()
        } else {
            // 通常モードの場合は、「0枚選択」の状態で選択モードに入る
            _uiState.update { state ->
                state.copy(
                    selectionMode = true,
                    isSelectionMode = true,
                    selectedIds = emptySet(),
                    selectedCount = 0
                )
            }
        }
    }

    /**
     * 長押しの入り口。
     * 【※1】選択モード中の場合：
     * 直近選択した画像Aから今回長押しした画像Bまでの範囲を一括選択（ソート表示順）します。
     * 【※2＋α】★拡張表示中（ハッシュ比較中）に画像を長押しすると、
     * その画像と同一類似枠に属するすべての画像の所属グループを一括抽出し、
     * グループ名順で枠色分けして比較表示します。
     */
    fun handleLongPress(imageId: Long) {
        setFocusedImage(imageId)
        val state = _uiState.value

        // 【※1：選択モード中の範囲一括選択】
        // 選択モードがONの場合、直前に選んだ画像Aから今回長押しした画像Bまでの間を一括選択する
        if (state.selectionMode) {
            val anchorId = lastSelectedImageId ?: state.selectedIds.firstOrNull()
            if (anchorId != null) {
                // 起点画像Aが存在する場合：A〜Bまでの画像をまとめて選択状態にする
                selectRange(anchorId = anchorId, targetId = imageId)
                // 今回長押しした画像Bを次回の範囲選択の新しい起点として更新
                lastSelectedImageId = imageId
            } else {
                // まだ1枚も選択されていない状態で長押しされた場合：長押しされた画像を1枚選択
                toggleSelected(imageId)
            }
            return
        }

        // ★拡張選択中（ハッシュ比較中）かつ、まだグループ比較モードに入っていない場合にグループ比較へ突入
        if (state.extensionSelectionActive && !state.isGroupComparisonMode && !state.isSubFolderGroupMode) {
            val targetImage = state.entries.firstOrNull { it.id == imageId }
            val targetDocId = targetImage?.parentFolderDocId
            val baseTree = rootTreeUri ?: currentFolderUri
            val isDifferentSubFolder = state.includeSubFolders && targetDocId != null && targetDocId != currentFolderDocId && baseTree != null

            if (isDifferentSubFolder && targetImage != null) {
                // 【※2：下層📁ON時の下層画像長押し】
                // 下層フォルダの画像が長押しされた場合、その下層フォルダへ移動してグループ表示に切り替え
                enterSubFolderGroupMode(targetImage)
            } else {
                // 通常のルートフォルダ（同一フォルダ内）でのグループ比較モード
                enterGroupComparisonMode(imageId)
            }
        }
    }

    /**
     * 【※2＋α：グループ比較モードの開始（しおりジャンプ連携）】
     * 長押しされた画像が含まれる「類似画像枠」の全画像を特定して選択状態にし、
     * グループ連番表示に切り替えて、しおりボタンでそれら画像間を順次ジャンプできるようにします。
     */
    private fun enterGroupComparisonMode(targetImageId: Long) {
        val currentState = _uiState.value
        if (!currentState.extensionSelectionActive || currentState.isGroupComparisonMode) return

        val targetIndex = currentState.entries.indexOfFirst { it.id == targetImageId }
        if (targetIndex < 0) return

        // ① 長押しされた画像を中心にして、前後に同じ枠色が連続している「同一類似ブロック」の仲間を確実に抽出
        val targetColor = currentState.extensionGroupColors[targetImageId]
        val similarImages = if (targetColor != null) {
            val block = mutableListOf<ImageItem>()
            var i = targetIndex
            while (i >= 0 && currentState.extensionGroupColors[currentState.entries[i].id] == targetColor) {
                block.add(0, currentState.entries[i])
                i--
            }
            i = targetIndex + 1
            while (i < currentState.entries.size && currentState.extensionGroupColors[currentState.entries[i].id] == targetColor) {
                block.add(currentState.entries[i])
                i++
            }
            block
        } else {
            listOf(currentState.entries[targetIndex])
        }

        if (similarImages.isEmpty()) return
        val similarIds = similarImages.map { it.id }.toSet()

        // ② 全画像からグループ連番画像を取得し、未リネームの類似画像も必ず合流させる
        val groupSeqImages = sortImageList(allImages, SortOption.GROUP_SEQ_ASC)
        val missingSimilarImages = similarImages.filter { sim -> groupSeqImages.none { it.id == sim.id } }
        val groupEntries = (groupSeqImages + missingSimilarImages).distinctBy { it.id }.ifEmpty {
            sortImageList(allImages, SortOption.NAME_ASC)
        }

        val groupColors = mutableMapOf<Long, Char>()
        val groupKeyIndexMap = mutableMapOf<String, Int>()
        for (img in groupEntries) {
            val info = RenameMoveHelper.parseRenamedFileInfo(img.displayName) ?: continue
            val groupIdx = groupKeyIndexMap.getOrPut(info.groupKey) { groupKeyIndexMap.size }
            val colorCategory = ('A'.code + (groupIdx % 26)).toChar()
            groupColors[img.id] = colorCategory
        }

        // ③ 直前のハッシュ比較一覧をバックアップし、グループ表示＋該当画像を選択状態に更新
        _uiState.update {
            it.copy(
                isGroupComparisonMode = true,
                comparisonBackupEntries = currentState.entries,
                comparisonBackupGroupColors = currentState.extensionGroupColors,
                comparisonBookmarkIds = similarIds, // 記憶した類似画像ID（選択が0枚になってもジャンプ可能にする用）
                entries = groupEntries,
                extensionGroupColors = groupColors,
                // 類似枠の仲間たちを選択状態にして光度40%オフ＆チェック付きにする
                selectedIds = similarIds,
                selectedCount = similarIds.size,
                selectionMode = true,
                isSelectionMode = true,
                currentJumpIndex = 1,
                matchedCount = groupEntries.size,
                snackbarMessage = "${similarIds.size}枚を選択しグループ表示へ移動しました（しおりでジャンプ可能）"
            )
        }

        // ④ 最初の1枚目の位置へ画面を自動スクロールし、しおりカーソルをセット
        val firstMatchIndex = groupEntries.indexOfFirst { it.id in similarIds }
        if (firstMatchIndex >= 0) {
            jumpCursorIndex = firstMatchIndex
            requestScroll(firstMatchIndex)
        } else {
            jumpCursorIndex = -1
        }
    }

    /**
     * 【※2＋α：グループ比較モードの終了（復帰）】
     * カテゴリーラベル長押しによって呼ばれ、バックアップから元の★ハッシュ比較一覧に戻します。
     * はづきさんのご要望どおり、しおりや選択状態も綺麗にリセットします。
     */
    fun exitGroupComparisonMode() {
        val currentState = _uiState.value
        if (!currentState.isGroupComparisonMode) return

        val backupEntries = currentState.comparisonBackupEntries ?: currentState.entries
        val backupColors = currentState.comparisonBackupGroupColors

        // しおりのカーソル位置をリセット
        jumpCursorIndex = -1

        _uiState.update {
            it.copy(
                isGroupComparisonMode = false,
                comparisonBackupEntries = null,
                comparisonBackupGroupColors = emptyMap(),
                comparisonBookmarkIds = emptySet(),
                entries = backupEntries,
                extensionGroupColors = backupColors,
                matchedCount = backupEntries.size,
                // しおり・選択状態を綺麗にリセット
                selectedIds = emptySet(),
                selectedCount = 0,
                selectionMode = false,
                isSelectionMode = false,
                currentJumpIndex = null,
                snackbarMessage = "ハッシュ比較一覧に復帰しました（しおり・選択リセット）"
            )
        }
    }

    /**
     * 【※2：下層フォルダへ移動してグループ表示を開始】
     * 「下層📁ON」の★拡張選択モード中に、下層フォルダに属する画像Cが長押しされた際に呼び出されます。
     * 1. 現在の★ハッシュ比較一覧とグループ枠色を一時保存（バックアップ）
     * 2. 親フォルダ情報を履歴スタックに退避（復帰時に元の親に戻れるようにする）
     * 3. 画像Cが存在する下層フォルダへ移動し、その直下の画像を読み込み
     * 4. 読み込み完了後、下層フォルダ内で「グループ連番順（枠色別）」でソートして表示
     * 5. 画像Cを選択＆フォーカス状態にしてその位置へスクロール
     * 6. メニューバーの「カテゴリーラベル表示」の文字色を #B60500 に変更（isSubFolderGroupMode = true）
     */
    private fun enterSubFolderGroupMode(targetImage: ImageItem) {
        val currentState = _uiState.value
        val targetDocId = targetImage.parentFolderDocId ?: return
        val baseTree = rootTreeUri ?: currentFolderUri ?: return
        val folderName = targetImage.parentFolderName ?: "フォルダ"

        // ① 現在の親フォルダを履歴スタックに記録（復帰時に親フォルダへ戻れるようにする）
        pushCurrentFolderToBackStack()

        // ② 現在のハッシュ一覧・枠色を一時バックアップし、下層フォルダ移動中フラグをON
        _uiState.update {
            it.copy(
                isSubFolderGroupMode = true,
                comparisonBackupEntries = currentState.entries,
                comparisonBackupGroupColors = currentState.extensionGroupColors,
                comparisonBookmarkIds = setOf(targetImage.id),
                isLoading = true,
                isStreaming = true,
                currentFolderLabel = folderName,
                folderDetailLabel = buildReadableFullPath(baseTree, targetDocId),
                folderTotalCount = 0
            )
        }

        currentFolderUri = baseTree
        currentFolderDocId = targetDocId

        // ③ 下層フォルダ直下の画像を読み込み、完了時にグループ連番順表示へ切り替え
        viewModelScope.launch {
            try {
                val fetchedImages = mutableListOf<ImageItem>()
                repository.loadFromTreeStreaming(
                    treeUri = baseTree,
                    includeSubFolders = false, // 下層フォルダ直下の画像のみ読み込む
                    folderDocId = targetDocId
                ).collect { progress ->
                    fetchedImages.clear()
                    fetchedImages.addAll(progress.images)
                    onBatchReceived(progress)
                }

                // 全画像からグループ連番ソートを適用（_nn_mm形式のファイルを枠色分け）
                val groupSeqImages = sortImageList(fetchedImages, SortOption.GROUP_SEQ_ASC)
                val finalEntries = groupSeqImages.ifEmpty { sortImageList(fetchedImages, SortOption.NAME_ASC) }

                // グループ枠線の色分け（A〜Z）を計算
                val groupColors = mutableMapOf<Long, Char>()
                val groupKeyIndexMap = mutableMapOf<String, Int>()
                for (img in finalEntries) {
                    val info = RenameMoveHelper.parseRenamedFileInfo(img.displayName) ?: continue
                    val groupIdx = groupKeyIndexMap.getOrPut(info.groupKey) { groupKeyIndexMap.size }
                    val colorCategory = ('A'.code + (groupIdx % 26)).toChar()
                    groupColors[img.id] = colorCategory
                }

                _uiState.update { s ->
                    s.copy(
                        isStreaming = false,
                        isLoading = false,
                        entries = finalEntries,
                        extensionGroupColors = groupColors,
                        // 画像Cを選択＆フォーカス状態にして強調
                        focusedImageId = targetImage.id,
                        selectedIds = setOf(targetImage.id),
                        selectedCount = 1,
                        selectionMode = true,
                        isSelectionMode = true,
                        currentJumpIndex = 1,
                        matchedCount = finalEntries.size,
                        snackbarMessage = "「$folderName」へ移動しグループ表示に切り替えました"
                    )
                }

                // 画像Cの位置へスクロール＆しおりカーソルを設定
                val targetIndex = finalEntries.indexOfFirst { it.id == targetImage.id }
                if (targetIndex >= 0) {
                    jumpCursorIndex = targetIndex
                    requestScroll(targetIndex)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isStreaming = false,
                        snackbarMessage = "下層フォルダの読み込みに失敗しました: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * 【※2：下層フォルダのグループ表示から元の★ハッシュ一覧へ復帰】
     * メニューバーのカテゴリーラベル長押しによって呼び出されます。
     * 親フォルダへ戻り、バックアップしていた★ハッシュ一覧・枠色を完全に復元します。
     * 下層フォルダ作業中に削除・移動されたファイルは、syncBackupAfterFileRemoval で自動同期済みのため安全です。
     */
    fun exitSubFolderGroupMode() {
        val currentState = _uiState.value
        if (!currentState.isSubFolderGroupMode) return

        if (folderBackStack.isEmpty()) {
            _uiState.update { it.copy(isSubFolderGroupMode = false) }
            return
        }

        // スタックから直前の親フォルダ情報を取り出す
        val parent = folderBackStack.removeAt(folderBackStack.lastIndex)
        val baseTree = parent.uri ?: rootTreeUri ?: currentFolderUri

        currentFolderUri = parent.uri
        currentFolderDocId = parent.docId
        currentZipDir = null

        val backupEntries = currentState.comparisonBackupEntries ?: emptyList()
        val backupColors = currentState.comparisonBackupGroupColors

        // しおり位置をリセット
        jumpCursorIndex = -1

        // 【全画像リストの同期】
        // 下層フォルダ読み込み時に書き換わっていた allImages を親フォルダの画像一覧へ完全同期。
        // これにより、その後のソート変更などで下層フォルダの画像が混ざる不整合を防ぎます。
        allImages = backupEntries

        _uiState.update {
            it.copy(
                canNavigateUp = folderBackStack.isNotEmpty(),
                isSubFolderGroupMode = false,
                comparisonBackupEntries = null,
                comparisonBackupGroupColors = emptyMap(),
                comparisonBookmarkIds = emptySet(),
                // フォルダ名と詳細パスを確実に親フォルダのものへ戻す
                currentFolderLabel = parent.label,
                folderDetailLabel = baseTree?.let { buildReadableFullPath(it, parent.docId) } ?: parent.label,
                entries = backupEntries,
                extensionGroupColors = backupColors,
                matchedCount = backupEntries.size,
                selectedIds = emptySet(),
                selectedCount = 0,
                selectionMode = false,
                isSelectionMode = false,
                currentJumpIndex = null,
                snackbarMessage = "元のハッシュ比較一覧に復帰しました"
            )
        }
    }

    /**
     * 【グループ比較モード・下層グループ表示中のファイル削除・移動に伴うバックアップ整合性同期】
     * ファイルが削除または別フォルダへ移動された際、
     * 裏に保持している「ハッシュ比較バックアップ一覧」からも該当画像を取り除き、
     * 復帰時に消えた画像が表示されてしまう不整合を完全に防ぎます。
     */
    fun syncBackupAfterFileRemoval(removedIds: Set<Long>) {
        if (removedIds.isEmpty()) return

        // 【下層ハッシュキャッシュの常時同期除外】
        // どんな画面（通常フォルダ、下層フォルダ、長押し潜り中など）で画像が削除されても、
        // キャッシュが存在するなら削除画像を即時除外し、後で★を押したときに消した画像が出ないよう完全同期！
        subFolderHashCache = subFolderHashCache?.let { c ->
            val newEntries = c.entries.filter { it.id !in removedIds }
            c.copy(
                entries = newEntries,
                groupColors = c.groupColors.filterKeys { it !in removedIds },
                matchedCount = newEntries.size
            )
        }

        // バックアップ一覧（潜り中・グループ比較中）の更新
        if (_uiState.value.isGroupComparisonMode || _uiState.value.isSubFolderGroupMode) {
            _uiState.update { s ->
                val updatedBackup = s.comparisonBackupEntries?.filter { it.id !in removedIds }
                val updatedColors = s.comparisonBackupGroupColors.filterKeys { it !in removedIds }
                val updatedBookmarks = s.comparisonBookmarkIds - removedIds
                s.copy(
                    comparisonBackupEntries = updatedBackup,
                    comparisonBackupGroupColors = updatedColors,
                    comparisonBookmarkIds = updatedBookmarks
                )
            }
        }
    }

    /**
     * 【カテゴリーラベル長押し時の処理】
     * 下層グループ表示中、またはグループ比較モード中なら、ハッシュ比較一覧へ復帰します。
     */
    fun handleLabelLongClick() {
        if (_uiState.value.isSubFolderGroupMode) {
            // 【※2】下層フォルダ移動中の場合：元の親フォルダ・ハッシュ比較一覧へ復帰
            exitSubFolderGroupMode()
        } else if (_uiState.value.isGroupComparisonMode) {
            // 【※2＋α】同一フォルダ内のグループ比較の場合：ハッシュ比較一覧へ復帰
            exitGroupComparisonMode()
        }
    }

    /** 現在の表示順(entries)を基準に、anchorIdとtargetIdの間にある画像をまとめて選択する。 */
    private fun selectRange(anchorId: Long, targetId: Long) {
        val entries = _uiState.value.entries
        val anchorIndex = entries.indexOfFirst { it.id == anchorId }
        val targetIndex = entries.indexOfFirst { it.id == targetId }
        if (anchorIndex < 0 || targetIndex < 0) {
            // 万一見つからなければ、安全のため通常の選択開始にフォールバックする
            startSelection(targetId)
            return
        }
        // ソート表示順で起点と終点の間のインデックス範囲を抽出
        val range = minOf(anchorIndex, targetIndex)..maxOf(anchorIndex, targetIndex)
        val rangeIds = range.map { entries[it].id }.toSet()
        _uiState.update { currentState ->
            // 既存の選択状態を保持しつつ、範囲内の画像を追加合流する
            val newSelectedIds = currentState.selectedIds + rangeIds
            currentState.copy(
                selectionMode = true,
                selectedIds = newSelectedIds,
                // ---- 新しい選択状態も更新（ラベルは現在の選択を維持する） ----
                isSelectionMode = true,
                selectedCount = newSelectedIds.size,
                focusedImageId = targetId
            )
        }
    }

    /**
     * 【選択画像のみ全解除（選択モードは維持）】
     * 「〇枚選択」ボタンを長押しした際に呼ばれます。
     * 選択モードは終了させず、選択した画像だけをすべて外して「0枚選択」の状態に戻します。
     */
    fun clearSelectionOnly() {
        lastSelectedImageId = null // 起点もクリア
        _uiState.update {
            it.copy(
                selectedIds = emptySet(),
                selectedCount = 0,
                currentJumpIndex = null
            )
        }
        jumpCursorIndex = -1
    }

    fun clearSelection() {
        lastSelectedImageId = null // 起点もクリア
        val wasAddMode = _uiState.value.addModeActive
        _uiState.update {
            it.copy(
                selectionMode = false,
                selectedIds = emptySet(),
                currentJumpIndex = null,
                addModeActive = false,
                addModeTargetKey = null,
                addModeCategory = null,
                // ---- 選択モード・リネーム機能の状態もクリア（ラベルは次回のために保持） ----
                isSelectionMode = false,
                selectedCount = 0
            )
        }
        jumpCursorIndex = -1
        // 「画像追加」モード中に選択解除(=キャンセル)された場合は、グループ内画面に戻す
        if (wasAddMode) {
            _uiState.update { it.copy(screenMode = ScreenMode.GROUP_DETAIL) }
        }
    }

    /**
     * 「選択へ」ボタン。押すたびに、今の一覧表示順(entries)の中で選択されている画像を
     * 先頭から順に巡回する(テキスト検索の「次を検索」と同じ考え方)。末尾まで行くと先頭に戻る。
     * 【※2＋α連携】選択が0枚の場合でも、記憶した類似画像（comparisonBookmarkIds）があればそこへジャンプする。
     */
    fun jumpToNextSelected() {
        val state = _uiState.value
        val targetIds = if (state.selectedIds.isNotEmpty()) state.selectedIds else state.comparisonBookmarkIds
        if (targetIds.isEmpty()) return
        val entries = state.entries
        val matchIndices = entries.indices.filter { entries[it].id in targetIds }
        if (matchIndices.isEmpty()) return
        
        val currentMatchPos = matchIndices.indexOf(jumpCursorIndex)
        val nextMatchPos = (currentMatchPos + 1) % matchIndices.size
        
        jumpCursorIndex = matchIndices[nextMatchPos]
        _uiState.update { it.copy(currentJumpIndex = nextMatchPos + 1) }
        requestScroll(jumpCursorIndex)
    }

    /**
     * 「選択へ」ボタンの長押し。選択されている画像のうち、
     * 一覧の表示順で一番最初にあるものへジャンプする。
     */
    fun jumpToFirstSelected() {
        val state = _uiState.value
        val targetIds = if (state.selectedIds.isNotEmpty()) state.selectedIds else state.comparisonBookmarkIds
        if (targetIds.isEmpty()) return
        val entries = state.entries
        val firstMatchIndex = entries.indexOfFirst { it.id in targetIds }
        if (firstMatchIndex >= 0) {
            jumpCursorIndex = firstMatchIndex
            _uiState.update { it.copy(currentJumpIndex = 1) }
            requestScroll(firstMatchIndex)
        }
    }

    /**
     * 【選択画像の取得】
     * 現在ユーザーが選択（チェック）している ImageItem の一覧を取得します。
     * 1. まず現在画面に表示されている一覧（_uiState.value.entries）からIDが一致するものを探します。
     *    （★拡張選択中・ハッシュ値一覧表示中など、絞り込み画面での選択を最優先で確実に取得するため）
     * 2. 画面一覧に見つからない画像があれば、フォルダ全体の生リスト（allImages）からも補完して取得します。
     */
    private fun selectedImages(): List<ImageItem> {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return emptyList()
        val currentEntries = _uiState.value.entries
        val fromEntries = currentEntries.filter { it.id in ids }
        // 選択されたIDがすべて画面一覧から見つかった場合はそれを返す
        if (fromEntries.size == ids.size) {
            return fromEntries
        }
        // 画面一覧にないIDが含まれる場合は、allImages からも探して合流（重複IDは除外）
        val foundIds = fromEntries.map { it.id }.toSet()
        val missingIds = ids - foundIds
        val fromAll = allImages.filter { it.id in missingIds }
        return fromEntries + fromAll
    }

    /**
     * 読込/再読込のたびに選択状態の整合性を取る。
     * ・pendingSelectByName が設定されている場合(直前にリネームを行った場合)は、
     *   IDの一致に頼らず「リネーム後に付くはずの新ファイル名」との一致で選択を再割り当てする。
     * ・それ以外は、既に存在しない画像ID(移動・削除で消えたもの)を選択から取り除くだけに留める
     *   (該当しないIDが混ざっていても表示上は無害だが、選択件数の表示がずれるのを防ぐため)。
     */
    private fun reconcileSelection() {
        val nameTargets = pendingSelectByName
        if (nameTargets != null) {
            pendingSelectByName = null
            val matchedIds = allImages.filter { it.displayName in nameTargets }.map { it.id }.toSet()
            _uiState.update { it.copy(selectedIds = matchedIds, selectionMode = matchedIds.isNotEmpty()) }
            return
        }
        val state = _uiState.value
        if (state.selectedIds.isEmpty()) return
        val validIds = allImages.map { it.id }.toSet()
        val filtered = state.selectedIds.filter { it in validIds }.toSet()
        if (filtered != state.selectedIds) {
            _uiState.update { it.copy(selectedIds = filtered, selectionMode = filtered.isNotEmpty()) }
        }
    }

    // ------------------------------------------------------------------
    // 選択モード時の操作: 移動・削除・リネーム・ZIP化
    // ------------------------------------------------------------------

    /**
     * 選択画像を Download/_Moved_ (または開いているSAFフォルダ内の_Moved_)へ移動する。
     *
     * 【重要】MediaStoreの createWriteRequest は MediaStore の URI にしか使えない。
     * SAFで選択したフォルダの画像はDocumentsContract系のURIのため、そのまま渡すと
     * IllegalArgumentExceptionで即クラッシュする(移動/削除/リネームが繰り返し落ちる不具合の原因)。
     * そのため、現在開いているフォルダの種類によって処理を分岐する。
     */
    /**
     * @param visibleIndex 実行直前に一覧で見えていた先頭位置(呼び出し側のLazyGridStateから渡す)。
     *   移動後の再読込でも、この位置になるべく近い表示を維持するために使う。
     *   移動した画像自体は一覧から消えるため選択は自然に外れるが、明示的にclearSelectionは呼ばない
     *   (連続して別の画像を移動・リネームする際に選択し直す手間を減らすため)。
     */
    fun moveSelectedToMovedFolder(visibleIndex: Int) {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "移動対象が選択されていません") }
            return
        }
        val zipDir = currentZipDir
        val treeUri = currentFolderUri
        when {
            zipDir != null -> viewModelScope.launch {
                val moved = fileOps.moveToMovedFolderLocal(targets)
                removeImagesFromAllGroups(moved.map { it.id })
                syncBackupAfterFileRemoval(moved.map { it.id }.toSet())
                _uiState.update { it.copy(snackbarMessage = "${moved.size}件を_Moved_に移動しました") }
                reloadCurrentFolder(visibleIndex)
            }
            treeUri != null -> viewModelScope.launch {
                val moved = fileOps.moveToMovedFolderSaf(treeUri, targets)
                removeImagesFromAllGroups(moved.map { it.id })
                syncBackupAfterFileRemoval(moved.map { it.id }.toSet())
                _uiState.update { it.copy(snackbarMessage = "${moved.size}件を_Moved_に移動しました") }
                reloadCurrentFolder(visibleIndex)
            }
            else -> {
                // 既定のDownload/未整理フォルダ(MediaStore経由)の場合のみ、システムの同意ダイアログが必要
                pendingAction = PendingMediaAction.Move(targets, visibleIndex)
                _intentSenderRequest.value = fileOps.createWriteRequest(targets)
            }
        }
    }

    /**
     * 【選択画像の削除処理】
     * 選択された画像を安全に削除します。
     * ★拡張選択モード（ハッシュ値比較一覧画面）表示中の場合は、
     * 18,800枚などの全件再読込・再ハッシュ計算を一切走らせず、
     * 現在画面に出ている一覧（entries）と生リスト（allImages）から削除した画像だけを即座に除外（同期）します。
     *
     * @param visibleIndex 実行直前に一覧で見えていた先頭位置(削除後の再読込でこの位置付近を維持する)
     */
    fun deleteSelected(visibleIndex: Int) {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "削除対象が選択されていません") }
            return
        }
        val targetIds = targets.map { it.id }.toSet()
        val zipDir = currentZipDir
        val treeUri = currentFolderUri
        val currentState = _uiState.value
        // ★拡張選択モード（ハッシュ値比較一覧など）表示中かどうか（下層フォルダ潜り中は除外）
        val isExtensionActive = currentState.extensionSelectionActive && !currentState.isSubFolderGroupMode

        when {
            zipDir != null -> viewModelScope.launch {
                val count = fileOps.deleteImagesLocal(targets)
                removeImagesFromAllGroups(targets.map { it.id })
                syncBackupAfterFileRemoval(targetIds)
                clearSelectionOnly() // 選択チェックのみクリアし、選択モード自体は維持

                if (isExtensionActive) {
                    // 【★ハッシュ値一覧表示中の高速同期】
                    // 全件再読込・再ハッシュ計算をスキップし、現在の一覧から削除した画像だけを即座に除外
                    allImages = allImages.filter { it.id !in targetIds }
                    // キャッシュからも削除画像を除外して同期維持
                    subFolderHashCache = subFolderHashCache?.let { c ->
                        val newEntries = c.entries.filter { it.id !in targetIds }
                        c.copy(
                            entries = newEntries,
                            groupColors = c.groupColors.filterKeys { it !in targetIds },
                            matchedCount = newEntries.size
                        )
                    }
                    _uiState.update { s ->
                        val updatedEntries = s.entries.filter { it.id !in targetIds }
                        val updatedColors = s.extensionGroupColors.filterKeys { it !in targetIds }
                        s.copy(
                            entries = updatedEntries,
                            extensionGroupColors = updatedColors,
                            matchedCount = updatedEntries.size,
                            totalImageCount = (s.totalImageCount - count).coerceAtLeast(0),
                            snackbarMessage = "${count}件を削除しました"
                        )
                    }
                } else {
                    // 通常のフォルダ表示時は従来通り再読込
                    _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
                    reloadCurrentFolder(visibleIndex)
                }
            }
            treeUri != null -> viewModelScope.launch {
                val count = fileOps.deleteImagesSaf(targets)
                removeImagesFromAllGroups(targets.map { it.id })
                syncBackupAfterFileRemoval(targetIds)
                clearSelectionOnly() // 選択チェックのみクリアし、選択モード自体は維持

                if (isExtensionActive) {
                    // 【★ハッシュ値一覧表示中の高速同期】
                    // 全件再読込・再ハッシュ計算をスキップし、現在の一覧から削除した画像だけを即座に除外
                    allImages = allImages.filter { it.id !in targetIds }
                    // キャッシュからも削除画像を除外して同期維持
                    subFolderHashCache = subFolderHashCache?.let { c ->
                        val newEntries = c.entries.filter { it.id !in targetIds }
                        c.copy(
                            entries = newEntries,
                            groupColors = c.groupColors.filterKeys { it !in targetIds },
                            matchedCount = newEntries.size
                        )
                    }
                    _uiState.update { s ->
                        val updatedEntries = s.entries.filter { it.id !in targetIds }
                        val updatedColors = s.extensionGroupColors.filterKeys { it !in targetIds }
                        s.copy(
                            entries = updatedEntries,
                            extensionGroupColors = updatedColors,
                            matchedCount = updatedEntries.size,
                            totalImageCount = (s.totalImageCount - count).coerceAtLeast(0),
                            snackbarMessage = "${count}件を削除しました"
                        )
                    }
                } else {
                    // 通常のフォルダ表示時は従来通り再読込
                    _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
                    reloadCurrentFolder(visibleIndex)
                }
            }
            else -> {
                pendingAction = PendingMediaAction.Delete(targets, visibleIndex)
                _intentSenderRequest.value = fileOps.createDeleteRequest(targets)
            }
        }
    }

    /**
     * 現在の表示(ソート)順のうち、選択された画像だけを対象にリネームする。
     * リネーム後も選択状態を維持するため、「リネームで付くはずの新ファイル名」をあらかじめ計算しておき、
     * 再読込後にその名前を持つ画像を選び直す(pendingSelectByName経由、reconcileSelectionで消費される)。
     * @param visibleIndex 実行直前に一覧で見えていた先頭位置。
     */
    fun renameSelectedSequentially(visibleIndex: Int) {
        val currentOrder = _uiState.value.entries
        val selectedIds = _uiState.value.selectedIds
        val orderedTargets = currentOrder.filter { it.id in selectedIds }
        if (orderedTargets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "リネーム対象が選択されていません") }
            return
        }
        val prefix = RenameUtil.buildTimestampPrefix()
        val expectedNewNames = orderedTargets.mapIndexed { index, item ->
            RenameUtil.buildFileName(prefix, index, item.extension)
        }.toSet()

        val zipDir = currentZipDir
        val treeUri = currentFolderUri
        when {
            zipDir != null -> viewModelScope.launch {
                pendingSelectByName = expectedNewNames
                val count = fileOps.renameSequentiallyLocal(orderedTargets, prefix)
                _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
                reloadCurrentFolder(visibleIndex)
            }
            treeUri != null -> viewModelScope.launch {
                pendingSelectByName = expectedNewNames
                val count = fileOps.renameSequentiallySaf(orderedTargets, prefix)
                _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
                reloadCurrentFolder(visibleIndex)
            }
            else -> {
                pendingSelectByName = expectedNewNames
                pendingAction = PendingMediaAction.Rename(orderedTargets, visibleIndex, prefix)
                _intentSenderRequest.value = fileOps.createWriteRequest(orderedTargets)
            }
        }
    }

    /** Compose側でIntentSenderのlaunchが済んだら呼ぶ(二重発行防止) */
    fun onIntentSenderHandled() {
        _intentSenderRequest.value = null
    }

    /**
     * システムの同意ダイアログの結果を受け取り、許可されていれば保留中の操作を実行する。
     * 削除(createDeleteRequest)は許可された時点でシステム側が削除まで実行済みなので、
     * ここでは再読込のみ行う。移動・リネームは許可後にこちらでMediaStore更新を行う。
     */
    fun onPermissionResult(granted: Boolean) {
        val action = pendingAction
        pendingAction = null
        if (!granted) {
            // 処理自体が行われていないため、選択・表示位置はそのまま維持する
            pendingSelectByName = null
            _uiState.update { it.copy(snackbarMessage = "許可されなかったため処理を中止しました") }
            return
        }
        viewModelScope.launch {
            when (action) {
                is PendingMediaAction.Delete -> {
                    val targetIds = action.targets.map { it.id }.toSet()
                    val count = action.targets.size
                    val isExtensionActive = _uiState.value.extensionSelectionActive && !_uiState.value.isSubFolderGroupMode

                    // グループからも削除対象の画像を除去する
                    removeImagesFromAllGroups(action.targets.map { it.id })
                    syncBackupAfterFileRemoval(targetIds)
                    clearSelectionOnly()

                    if (isExtensionActive) {
                        // 【★ハッシュ値一覧表示中の高速同期】
                        allImages = allImages.filter { it.id !in targetIds }
                        // キャッシュからも削除画像を除外して同期維持
                        subFolderHashCache = subFolderHashCache?.let { c ->
                            val newEntries = c.entries.filter { it.id !in targetIds }
                            c.copy(
                                entries = newEntries,
                                groupColors = c.groupColors.filterKeys { it !in targetIds },
                                matchedCount = newEntries.size
                            )
                        }
                        _uiState.update { s ->
                            val updatedEntries = s.entries.filter { it.id !in targetIds }
                            val updatedColors = s.extensionGroupColors.filterKeys { it !in targetIds }
                            s.copy(
                                entries = updatedEntries,
                                extensionGroupColors = updatedColors,
                                matchedCount = updatedEntries.size,
                                totalImageCount = (s.totalImageCount - count).coerceAtLeast(0),
                                snackbarMessage = "${count}件を削除しました"
                            )
                        }
                    } else {
                        _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
                        reloadCurrentFolder(action.restoreScrollIndex)
                    }
                }
                is PendingMediaAction.Move -> {
                    val moved = fileOps.moveToMovedFolder(action.targets)
                    // 移動(_Movedへ)した画像も、現在の管理からは外れるためグループから除去する
                    removeImagesFromAllGroups(moved.map { it.id })
                    _uiState.update { it.copy(snackbarMessage = "${moved.size}件を Download/_Moved_ に移動しました") }
                    reloadCurrentFolder(action.restoreScrollIndex)
                }
                is PendingMediaAction.Rename -> {
                    val count = fileOps.renameSequentially(action.orderedTargets, action.prefix)
                    _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
                    reloadCurrentFolder(action.restoreScrollIndex)
                }
                null -> Unit
            }
        }
    }

    /**
     * 選択画像をZIPアーカイブ化する。保存先は「移動」と同じ場所(既定フォルダなら Download/_Moved_、
     * SAF/ZIPフォルダならそのフォルダ内の _Moved_ )。フォルダの中身自体は変わらないため再読込は行わず、
     * 選択・一覧の表示位置もそのまま維持される。
     */
    fun zipSelected() {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "ZIP対象が選択されていません") }
            return
        }
        viewModelScope.launch {
            val zipDir = currentZipDir
            val treeUri = currentFolderUri
            // 「年月日時分_01.zip」形式。保存先に同名があれば "_02" ... と自動で繰り上げる
            val name = fileOps.buildUniqueZipFileName(targets, zipDir, treeUri)
            val success = when {
                zipDir != null -> fileOps.zipImagesToMovedLocal(targets, name) != null
                treeUri != null -> fileOps.zipImagesToMovedSaf(treeUri, targets, name) != null
                else -> fileOps.zipImages(targets, name) != null
            }
            _uiState.update {
                it.copy(snackbarMessage = if (success) "_Moved_ にZIPを作成しました: $name" else "ZIP作成に失敗しました")
            }
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ------------------------------------------------------------------
    // フルスクリーン表示
    // ------------------------------------------------------------------

    fun openFullscreen(index: Int) {
        _uiState.update { it.copy(fullscreenIndex = index) }
    }

    fun closeFullscreen() {
        _uiState.update { it.copy(fullscreenIndex = null) }
    }

    // ------------------------------------------------------------------
    // スライドショー
    // ------------------------------------------------------------------

    fun toggleSlideshow(visibleIndex: Int = 0) {
        if (_uiState.value.slideshowActive) {
            stopSlideshow()
        } else {
            startSlideshow(visibleIndex)
        }
    }

    /**
     * @param visibleIndex 一覧表示で現在見えている先頭の画像のインデックス(呼び出し側のLazyGridStateから渡す)。
     * 選択中の画像がある場合は、表示順のままその画像だけを抜き出してスライドショーの対象にする
     * (以前は開始位置だけ選択を反映し、次の画像からは未選択のものも含めて全件をループしていた)。
     */
    private fun startSlideshow(visibleIndex: Int) {
        val state = _uiState.value
        val entries = state.entries
        if (entries.isEmpty()) return

        val targetEntries = if (state.selectedIds.isNotEmpty()) {
            entries.filter { it.id in state.selectedIds }
        } else {
            entries
        }
        if (targetEntries.isEmpty()) return

        // 選択がある場合は対象リストの先頭(=選択の中で最も表示順が早いもの)から、
        // 選択が無い場合は一覧で見えている位置から開始する
        val startIndex = if (state.selectedIds.isNotEmpty()) {
            0
        } else {
            visibleIndex.coerceIn(0, targetEntries.size - 1)
        }

        _uiState.update {
            it.copy(
                slideshowActive = true,
                slideshowPaused = false, // 開始時は必ず再生状態にする
                slideshowIndex = startIndex,
                slideshowEntries = targetEntries
            )
        }
        runSlideshowLoop()
    }

    /**
     * スライドショーの再生/一時停止を切り替える。
     */
    fun toggleSlideshowPause() {
        val newState = !_uiState.value.slideshowPaused
        _uiState.update { it.copy(slideshowPaused = newState) }
        // 停止から再生に切り替えた場合は、即座にループを再開させる
        if (!newState) runSlideshowLoop()
    }

    /**
     * 手動でページをめくった際に、現在のインデックスを同期する。
     * 同時に、自動再生のタイマーをリセットして「そこからまた指定秒数待つ」ようにする。
     */
    fun updateSlideshowIndex(index: Int) {
        val count = _uiState.value.slideshowEntries.size
        if (count == 0) return
        _uiState.update { it.copy(slideshowIndex = index % count) }
        // 手動操作されたらタイマーをリセット(操作した瞬間からカウントし直し)
        if (_uiState.value.slideshowActive && !_uiState.value.slideshowPaused) {
            runSlideshowLoop()
        }
    }

    private fun runSlideshowLoop() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            while (_uiState.value.slideshowActive) {
                // 一時停止中は何もしない(whileループ自体は維持し、再生再開を待つ)
                if (_uiState.value.slideshowPaused) {
                    kotlinx.coroutines.delay(500) // 停止中は負荷をかけず少し待機
                    continue
                }
                
                kotlinx.coroutines.delay((_uiState.value.slideshowInterval.seconds * 1000).toLong())
                
                // 遅延中に一時停止された場合は、進めない
                if (!_uiState.value.slideshowPaused) {
                    advanceSlideshow()
                }
            }
        }
    }

    fun advanceSlideshow() {
        // 全件(entries)ではなく、開始時に確定した対象リスト(slideshowEntries)の件数でループさせる
        val count = _uiState.value.slideshowEntries.size
        if (count == 0) return
        _uiState.update { it.copy(slideshowIndex = (it.slideshowIndex + 1) % count) }
    }

    fun setSlideshowInterval(interval: SlideshowInterval) {
        _uiState.update { it.copy(slideshowInterval = interval) }
        if (_uiState.value.slideshowActive) runSlideshowLoop() // 間隔変更を即反映
    }

    /** 分類一覧でカテゴリの代表画像を長押し → そのカテゴリ全体(例: C_01→C_02→C_03)を通しで再生 */
    fun startCategorySlideshow(category: Char) {
        val groups = _uiState.value.classificationGroups
            .filter { it.category == category }
            .sortedBy { it.sequence }
        val idsInOrder = groups.flatMap { it.imageIds }
        val entries = idsInOrder.mapNotNull { id -> allImages.firstOrNull { it.id == id } }
        if (entries.isEmpty()) return
        _uiState.update { 
            it.copy(
                slideshowActive = true, 
                slideshowPaused = false,
                slideshowIndex = 0, 
                slideshowEntries = entries
            ) 
        }
        runSlideshowLoop()
    }

    /** グループ内画面のスライドショーボタン → そのグループ単体の画像だけ再生 */
    fun startGroupSlideshow() {
        val entries = _uiState.value.groupDetailEntries
        if (entries.isEmpty()) return
        _uiState.update { 
            it.copy(
                slideshowActive = true, 
                slideshowPaused = false,
                slideshowIndex = 0, 
                slideshowEntries = entries
            ) 
        }
        runSlideshowLoop()
    }

    // ------------------------------------------------------------------
    // 手動グルーピング(分類)機能
    // ------------------------------------------------------------------

    /** classificationGroups が変わるたびに呼ぶ。保存 + 派生状態(groupedImageIds / classificationTiles)の再構築を行う。 */
    private fun rebuildClassificationDerivedState(groups: List<ClassificationGroup>) {
        classificationStore.saveGroups(groups)

        val groupedIndex = mutableMapOf<Long, Char>()
        groups.forEach { g -> g.imageIds.forEach { id -> groupedIndex[id] = g.category } }

        // カテゴリごとに島状にまとめつつ、カテゴリの並び順は「アルファベット順(A→Z)」で固定する。
        // (以前はソート設定に従って代表画像で並び替えていたが、直感的な探しやすさを優先してアルファベット順に変更)
        val byCategory = groups.groupBy { it.category }
        val categoryOrder = byCategory.keys.sorted()

        val tiles = categoryOrder.flatMap { category ->
            byCategory[category].orEmpty().sortedBy { it.sequence }.map { g ->
                val rep = g.effectiveRepresentativeId?.let { id -> allImages.firstOrNull { it.id == id } }
                ClassificationTile(group = g, representative = rep)
            }
        }

        _uiState.update {
            it.copy(
                classificationGroups = groups,
                groupedImageIds = groupedIndex,
                classificationTiles = tiles
            )
        }
    }

    /**
     * allImagesが更新されるたび(reloadCurrentFolder完了時など)に呼ぶ。
     * 表示の整合性を取るために派生状態(groupedImageIds / classificationTiles)を再構築する。
     *
     * 注意: 以前は「今開いているフォルダに画像がない場合はグループから削除する」という処理をしていたが、
     * それだとフォルダを切り替えるたびにデータが消失してしまうため、現在は削除せず維持するようにしている。
     */
    private fun reconcileClassification() {
        val current = _uiState.value.classificationGroups
        if (current.isEmpty()) return
        
        // 画像の存在チェックによる削除は行わず、常に現在のグループ状態で表示用データを再構成する
        rebuildClassificationDerivedState(current)
    }

    /** 画面上部の「画像一覧⇔分類一覧」ボタン(選択モード中でない時の通常動作) */
    fun toggleScreenMode() {
        val state = _uiState.value
        if (state.selectionMode) return // 選択モード中は「分類登録」ボタンとして扱う(TopBar側で分岐)
        _uiState.update {
            it.copy(screenMode = if (state.screenMode == ScreenMode.GALLERY) ScreenMode.CLASSIFICATION_LIST else ScreenMode.GALLERY)
        }
    }

    /** カテゴリ`category`における「次の連番:既存テーマ名」のプレビュー文字列(例: "C_04:神社")を返す。ダイアログのプルダウン表示に使う。 */
    fun previewForCategory(category: Char): String {
        val groups = _uiState.value.classificationGroups.filter { it.category == category }
        val nextSeq = (groups.maxOfOrNull { it.sequence } ?: 0) + 1
        val key = "${category}_${nextSeq.toString().padStart(2, '0')}"
        val themeName = groups.lastOrNull { it.name.isNotBlank() }?.name
        return if (themeName != null) "$key:$themeName" else key
    }

    /**
     * 選択モード中の上部バーボタン。「分類登録」ダイアログを開く。
     * (画像追加モード中は openAddModeConfirmDialogは無く、別途 confirmAddToGroup() を直接呼ぶ動線になる)
     */
    fun openNameDialogForNewGroup() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _uiState.update {
            it.copy(
                nameDialogVisible = true,
                nameDialogEditingKey = null,
                nameDialogPendingImageIds = ids
            )
        }
    }

    /** 分類一覧でグループ名を長押し→リネームダイアログを開く */
    fun openNameDialogForRename(groupKey: String) {
        val group = _uiState.value.classificationGroups.firstOrNull { it.key == groupKey } ?: return
        _uiState.update {
            it.copy(
                nameDialogVisible = true,
                nameDialogEditingKey = groupKey,
                nameDialogPendingImageIds = emptyList()
            )
        }
    }

    fun dismissNameDialog() {
        _uiState.update {
            it.copy(nameDialogVisible = false, nameDialogEditingKey = null, nameDialogPendingImageIds = emptyList())
        }
    }

    /**
     * 名前ダイアログのOK。
     * ・新規作成(nameDialogEditingKey == null): 選んだ画像で新しいグループを作る
     * ・リネーム(nameDialogEditingKey != null): 既存グループの名前(＋必要ならカテゴリ)を変更する
     */
    fun confirmNameDialog(category: Char, name: String) {
        val state = _uiState.value
        val editingKey = state.nameDialogEditingKey
        val groups = state.classificationGroups.toMutableList()

        if (editingKey == null) {
            // 新規作成: 既にどれかのグループに入っている画像は除外する(1画像1グループの原則)
            val alreadyGrouped = state.groupedImageIds.keys
            val targetIds = state.nameDialogPendingImageIds.filter { it !in alreadyGrouped }
            if (targetIds.isEmpty()) {
                dismissNameDialog()
                return
            }
            val nextSeq = (groups.filter { it.category == category }.maxOfOrNull { it.sequence } ?: 0) + 1
            groups.add(ClassificationGroup(category = category, sequence = nextSeq, name = name, imageIds = targetIds))
            rebuildClassificationDerivedState(groups)
            dismissNameDialog()
            clearSelection()
        } else {
            val index = groups.indexOfFirst { it.key == editingKey }
            if (index < 0) {
                dismissNameDialog()
                return
            }
            val old = groups[index]
            if (old.category == category) {
                groups[index] = old.copy(name = name)
            } else {
                // カテゴリ自体を変更する場合は、新しいカテゴリの連番を振り直す
                val nextSeq = (groups.filter { it.category == category }.maxOfOrNull { it.sequence } ?: 0) + 1
                groups[index] = old.copy(category = category, sequence = nextSeq, name = name)
            }
            rebuildClassificationDerivedState(groups)
            dismissNameDialog()
            // グループ内画面を見ている最中にリネームした場合、activeGroupKeyを新しいキーに追随させる
            if (state.activeGroupKey == editingKey) {
                openGroupDetail(groups[index].key)
            }
        }
    }

    /** 分類一覧でタグをタップ → グループ内画面へ */
    fun openGroupDetail(groupKey: String) {
        val group = _uiState.value.classificationGroups.firstOrNull { it.key == groupKey } ?: return
        val sorted = sortImageList(allImages.filter { it.id in group.imageIds }, group.sortOption)
        _uiState.update {
            it.copy(
                screenMode = ScreenMode.GROUP_DETAIL,
                activeGroupKey = groupKey,
                groupDetailEntries = sorted,
                groupDetailSelectedIds = emptySet()
            )
        }
    }

    /** グループ内画面の「戻る」。分類一覧に戻る。 */
    fun closeGroupDetail() {
        _uiState.update {
            it.copy(screenMode = ScreenMode.CLASSIFICATION_LIST, activeGroupKey = null, groupDetailSelectedIds = emptySet())
        }
    }

    /** グループ内画面専用のソート変更(グループごとに個別記憶する)。 */
    fun setGroupDetailSortOption(option: SortOption) {
        val key = _uiState.value.activeGroupKey ?: return
        val groups = _uiState.value.classificationGroups.toMutableList()
        val index = groups.indexOfFirst { it.key == key }
        if (index < 0) return
        groups[index] = groups[index].copy(sortOption = option)
        rebuildClassificationDerivedState(groups)
        val sorted = sortImageList(allImages.filter { it.id in groups[index].imageIds }, option)
        _uiState.update { it.copy(groupDetailEntries = sorted) }
    }

    /** グループ内画面で画像を長押し/タップして「削除対象」として選ぶ(通常一覧の選択とは別管理)。 */
    fun toggleGroupDetailSelected(imageId: Long) {
        _uiState.update { state ->
            val newSet = state.groupDetailSelectedIds.toMutableSet()
            if (!newSet.add(imageId)) newSet.remove(imageId)
            state.copy(groupDetailSelectedIds = newSet)
        }
    }

    fun clearGroupDetailSelection() {
        _uiState.update { it.copy(groupDetailSelectedIds = emptySet()) }
    }

    /**
     * 「サムネ指定」。選んだ画像を、このグループの分類一覧での代表画像(サムネイル)にする。
     * (imageIdsの並び順自体は変えず、representativeIdだけを差し替える。
     *  グループから外れた場合は自動的に1枚目へフォールバックする -> ClassificationGroup.effectiveRepresentativeId)
     */
    fun setGroupThumbnail(imageId: Long) {
        val state = _uiState.value
        val key = state.activeGroupKey ?: return
        val groups = state.classificationGroups.toMutableList()
        val index = groups.indexOfFirst { it.key == key }
        if (index < 0) return
        if (imageId !in groups[index].imageIds) return
        groups[index] = groups[index].copy(representativeId = imageId)
        rebuildClassificationDerivedState(groups)
        _uiState.update { it.copy(groupDetailSelectedIds = emptySet()) }
    }

    /**
     * 「画像削除」ボタン。選んだ画像をグループから外すだけ(端末上のファイルは消えない)。
     * 外した結果グループが空になったら、そのグループ自体を削除する。
     */
    fun removeSelectedFromGroup() {
        val state = _uiState.value
        val key = state.activeGroupKey ?: return
        val removeIds = state.groupDetailSelectedIds
        if (removeIds.isEmpty()) return

        val groups = state.classificationGroups.toMutableList()
        val index = groups.indexOfFirst { it.key == key }
        if (index < 0) return
        val updated = groups[index].copy(imageIds = groups[index].imageIds.filter { it !in removeIds })

        if (updated.imageIds.isEmpty()) {
            groups.removeAt(index)
            rebuildClassificationDerivedState(groups)
            closeGroupDetail()
        } else {
            groups[index] = updated
            rebuildClassificationDerivedState(groups)
            val sorted = sortImageList(allImages.filter { it.id in updated.imageIds }, updated.sortOption)
            _uiState.update { it.copy(groupDetailEntries = sorted, groupDetailSelectedIds = emptySet()) }
        }
    }

    /**
     * 「画像追加」ボタン。通常の画像一覧に切り替え、既存の選択モードの仕組み(長押し範囲選択等)を
     * そのまま流用して画像を選んでもらう。ImageGrid側は groupedImageIds/addModeCategory を見て、
     * 他カテゴリの画像を暗く表示・選択不可にする。
     */
    fun enterAddMode() {
        val key = _uiState.value.activeGroupKey ?: return
        val group = _uiState.value.classificationGroups.firstOrNull { it.key == key } ?: return
        _uiState.update {
            it.copy(
                screenMode = ScreenMode.GALLERY,
                selectionMode = true,
                selectedIds = emptySet(),
                addModeActive = true,
                addModeTargetKey = key,
                addModeCategory = group.category
            )
        }
    }

    /**
     * 画像追加モードでの「追加確定」(TopBarの「分類登録」ボタンが、addModeActive中はこちらに差し替わる)。
     * 選んだ画像を対象グループに追加する。同カテゴリの別グループから移した場合は、そのグループの画像から
     * 取り除く(=グループ統合)。移した結果、元グループが空になれば自動削除する(欠番は許容/Q13)。
     * 連番は「操作元(追加ボタンを押した側=対象グループ)」のものをそのまま維持する。
     */
    fun confirmAddToGroup() {
        val state = _uiState.value
        val targetKey = state.addModeTargetKey ?: return
        val category = state.addModeCategory ?: return
        val selected = state.selectedIds
        if (selected.isEmpty()) {
            clearSelection()
            return
        }

        val groups = state.classificationGroups.toMutableList()
        val targetIndex = groups.indexOfFirst { it.key == targetKey }
        if (targetIndex < 0) {
            clearSelection()
            return
        }

        // 選んだ画像を、まず「他のグループ(同カテゴリの別グループ)」から取り除く
        for (i in groups.indices) {
            if (i == targetIndex) continue
            if (groups[i].category != category) continue
            val remaining = groups[i].imageIds.filter { it !in selected }
            groups[i] = groups[i].copy(imageIds = remaining)
        }
        // 空になった他グループは削除(欠番は許容する)
        val cleanedOthers = groups.filterIndexed { i, g -> i == targetIndex || g.imageIds.isNotEmpty() }
        val newTargetIndex = cleanedOthers.indexOfFirst { it.key == targetKey }

        // 対象グループに追加(重複は無視。既存の並び順の後ろに追加する)
        val target = cleanedOthers[newTargetIndex]
        val mergedIds = (target.imageIds + selected).distinct()
        val finalGroups = cleanedOthers.toMutableList()
        finalGroups[newTargetIndex] = target.copy(imageIds = mergedIds)

        rebuildClassificationDerivedState(finalGroups)

        val sorted = sortImageList(allImages.filter { it.id in mergedIds }, target.sortOption)
        _uiState.update {
            it.copy(
                screenMode = ScreenMode.GROUP_DETAIL,
                activeGroupKey = targetKey,
                groupDetailEntries = sorted,
                groupDetailSelectedIds = emptySet(),
                selectionMode = false,
                selectedIds = emptySet(),
                addModeActive = false,
                addModeTargetKey = null,
                addModeCategory = null
            )
        }
    }

    /**
     * 指定された画像ID群を、すべての分類グループから一括で取り除く。
     * 画像が実際に削除/移動された際に呼ぶことで、グループ情報の整合性を保つ。
     */
    private fun removeImagesFromAllGroups(imageIds: List<Long>) {
        val idSet = imageIds.toSet()
        val current = _uiState.value.classificationGroups
        val updated = current.map { g ->
            g.copy(imageIds = g.imageIds.filter { it !in idSet })
        }.filter { it.imageIds.isNotEmpty() }
        
        if (updated != current) {
            rebuildClassificationDerivedState(updated)
        }
    }

    private fun stopSlideshow() {
        slideshowJob?.cancel()
        val state = _uiState.value
        val targets = state.slideshowEntries

        // slideshowIndexは「対象リスト(targets)」上の位置であり、一覧グリッド(entries)上の位置とは
        // 選択再生時にズレることがあるため、いま表示中だった画像のIDを介して
        // 一覧グリッド側での実際の位置に変換してからスクロールさせる。
        val stoppedImageId = targets.getOrNull(state.slideshowIndex.mod(targets.size.coerceAtLeast(1)))?.id
        val gridIndex = stoppedImageId
            ?.let { id -> state.entries.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0

        // フルスクリーン表示は開かず、一覧側をこの位置までスクロールさせて「続きから見られる」ようにする
        _uiState.update { it.copy(slideshowActive = false, pendingScrollRequest = ScrollRequest(gridIndex)) }
    }

    /** 一覧のスクロール追従が完了したら呼ぶ(一度だけ実行させるため) */
    fun consumePendingScroll() {
        _uiState.update { it.copy(pendingScrollRequest = null) }
    }

    /**
     * 【ラベルリスト読み込み】
     * アセットフォルダの「labels.txt」から、AI認識カテゴリーリストを読み込みます。
     * 
     * labels.txt の各行の形式例:
     * - "0 01_水着_anime" ➔ 先頭の「0 」は分類用ID、後ろの「01_水着_anime」が本来のラベル名
     * - "01_水着_anime"   ➔ IDがなく直接ラベル名のみが書かれている場合
     * 
     * 先頭のID番号（0, 1, 2...）は切り離し、本来のラベル名のみを抽出して返します。
     */
    private fun loadLabels(): List<String> {
        return try {
            val context = getApplication<Application>()
            val inputStream = context.assets.open("labels.txt")
            val content = inputStream.bufferedReader().use { it.readText() }
            content.split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    // =========================================================
                    // 空白文字（半角スペースやタブ）で最大2つに分割
                    // 例: "0 01_水着_anime" ➔ parts[0]="0", parts[1]="01_水着_anime"
                    // =========================================================
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size >= 2) {
                        // 最初の部分が数値（インデックス番号: 0, 1, 2...）かどうか判定
                        val isIndexNumber = parts[0].toIntOrNull() != null
                        if (isIndexNumber) {
                            // 先頭がインデックス番号なら、後ろの「本来のラベル名」のみを取り出す
                            parts[1].trim()
                        } else {
                            // 先頭が数値でない場合は、行全体をそのままラベル名として採用
                            line
                        }
                    } else {
                        // スペースで区切られていない行は、行全体をそのままラベル名として採用
                        line
                    }
                }
        } catch (e: Exception) {
            // ファイルが見つからない、または読み込み失敗時は空リスト
            emptyList()
        }
    }

    /**
     * 【選択モード開始】
     * 画像が複数選択された時に呼ぶ。選択モードを ON にしてメニューを表示します。
     */
    fun startSelectionMode(selectedImageIds: Set<Long>, defaultLabel: String = "") {
        _uiState.update { 
            it.copy(
                isSelectionMode = true,
                selectedCount = selectedImageIds.size,
                currentSelectedLabel = defaultLabel
            )
        }
    }

    /**
     * 【現在選択中のラベルを更新（長押し時）】
     * プルダウンメニューからラベルが長押しされた時に呼び、作業ラベルを変更します。
     */
    fun setCurrentSelectedLabel(label: String, showFeedback: Boolean = true) {
        _uiState.update {
            it.copy(
                currentSelectedLabel = label,
                snackbarMessage = if (showFeedback) "ラベルを「$label」に設定しました" else it.snackbarMessage
            )
        }
    }

    /**
     * 【直下のラベルフォルダへ移動（通常タップ時、存在する場合のみ）】
     * ラベル選択プルダウンで通常タップされたときに呼ばれる。
     * 現在のフォルダの直下に、指定ラベル（または空白除去後の名前）のフォルダが存在する場合、
     * そのフォルダへカレントフォルダを切り替えて開きます。存在しない場合は通知します。
     */
    fun navigateToSubFolderIfExists(label: String) {
        val sanitized = RenameMoveHelper.sanitizeForFilename(label)
        val treeUri = currentFolderUri

        // 通常のフォルダ（SAF）を開いている場合
        if (treeUri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val baseTree = rootTreeUri ?: treeUri
                    val currentDocId = currentFolderDocId ?: DocumentsContract.getTreeDocumentId(baseTree)
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(baseTree, currentDocId)

                    // 元のラベル名、空白全除去後の名前、またはsanitize後の名前で直下のサブフォルダを検索
                    val noSpaceLabel = label.replace(" ", "")
                    val subFolderUri = fileOps.findSubFolderUriSaf(resolver, baseTree, parentDocUri, label)
                        ?: fileOps.findSubFolderUriSaf(resolver, baseTree, parentDocUri, noSpaceLabel)
                        ?: fileOps.findSubFolderUriSaf(resolver, baseTree, parentDocUri, sanitized)

                    if (subFolderUri != null) {
                        // 見つかったサブフォルダのDocIdを取得し、マスターキー(baseTree)の権限傘下のまま開く
                        val subDocId = DocumentsContract.getDocumentId(subFolderUri)
                        withContext(Dispatchers.Main) {
                            // 【親フォルダ履歴退避】移動する前に、現在の親フォルダ情報をスタックに積む
                            pushCurrentFolderToBackStack()
                            // マスターキー(baseTree)を維持し、サブフォルダのDocIdを指定して開く（権限エラーを完全防止）
                            // 【重要】下層フォルダへ潜る移動の際は、大元の親フォルダ（「未整理」など）の履歴を
                            // 上書きしてしまわないように、必ず recordHistory = false を指定します。
                            openFolder(
                                treeUri = baseTree,
                                label = label,
                                recordHistory = false,
                                clearBackStack = false,
                                targetDocId = subDocId
                            )
                        }
                    } else {
                        // 直下に該当フォルダがない場合は、移動せずに案内を表示
                        _uiState.update {
                            it.copy(snackbarMessage = "直下に「$label」フォルダはありません")
                        }
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(snackbarMessage = "フォルダ移動に失敗しました: ${e.message}")
                    }
                }
            }
        } else {
            // フォルダが開かれていない場合
            _uiState.update {
                it.copy(snackbarMessage = "直下に「$label」フォルダはありません")
            }
        }
    }

    /**
     * 【一階層上の親フォルダに戻る（「..⤴」ボタン押下時）】
     * ラベル選択プルダウンの最上部に追加された「..⤴」ボタンをタップした時に呼び出されます。
     * スタックに退避されている直前の親フォルダ情報を取り出して移動します。
     * 起点フォルダまで戻りスタックが空になったら、自動的に「..⤴」ボタンはグレーアウト（無効）になります。
     */
    fun navigateUpFolder() {
        if (folderBackStack.isEmpty()) return

        // 【※2】下層フォルダ移動中グループ表示中の場合、exitSubFolderGroupMode で安全に親フォルダとハッシュ一覧へ復帰
        if (_uiState.value.isSubFolderGroupMode) {
            exitSubFolderGroupMode()
            return
        }

        // スタックの末尾（直前の親フォルダ）を取り出す
        val parent = folderBackStack.removeAt(folderBackStack.lastIndex)

        // まだ戻れる階層があるかどうかでボタンの活性状態（グレーアウト）を更新
        _uiState.update { it.copy(canNavigateUp = folderBackStack.isNotEmpty()) }

        // 取り出した親フォルダへ移動（スタックはクリアしない、親のDocIdを指定して復帰）
        if (parent.uri != null) {
            openFolder(
                treeUri = parent.uri,
                label = parent.label,
                recordHistory = false,
                clearBackStack = false,
                targetDocId = parent.docId
            )
        } else {
            loadDocumentsFolder(clearBackStack = false)
        }
    }

    /**
     * 【連番→📁[ラベル]フォルダへ移動】
     * 選択された画像を、指定されたラベル名のサブフォルダへ連番リネームして移動します。
     * 
     * ■ 命名規則（RenameMoveHelper準拠）:
     *   [ラベル名]_[グループ番号2文字]_[画像番号2文字].[拡張子]
     *   例: 01犬_00_00.jpg, 01犬_00_01.jpg
     * 
     * ■ 処理の流れ:
     * 1. 選択中の画像を取得（空なら通知して終了）
     * 2. ラベルの空白を除去して安全なフォルダ名・ファイル名にする（例:「01 犬」➔「01犬」）
     * 3. 対象フォルダ内に既に存在するファイルを調べ、次の空きグループ番号（00〜ZZ）を自動決定
     * 4. 選択順に画像番号（00〜ZZ）を付与してリネーム＆移動
     * 5. 完了後に画面の一覧を自動再読み込みして最新状態に更新
     */
    fun executeRenameMove(label: String) {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "移動対象の画像が選択されていません") }
            return
        }
        if (targets.size > com.hazuki.imageorganizer.data.RenameMoveHelper.MAX_SEQUENCE) {
            _uiState.update { it.copy(snackbarMessage = "一度に処理できる上限（${com.hazuki.imageorganizer.data.RenameMoveHelper.MAX_SEQUENCE}枚）を超えています") }
            return
        }

        // ラベル名の正規化（スペース削除 ＋ 禁則文字を「_」に置換）
        val rawLabel = label.ifBlank { "未分類" }
        val sanitizedLabel = com.hazuki.imageorganizer.data.RenameMoveHelper.sanitizeForFilename(rawLabel.replace(" ", ""))

        val zipDir = currentZipDir
        val treeUri = currentFolderUri

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var successCount = 0
                when {
                    // ---- ① SAFフォルダ（ユーザーがフォルダ選択で開いたフォルダ）の場合 ----
                    treeUri != null -> {
                        val resolver = getApplication<android.app.Application>().contentResolver
                        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                        val parentDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

                        // 1. ラベル名のサブフォルダを取得または新規作成
                        val targetFolderUri = fileOps.getOrCreateSubFolder(resolver, treeUri, parentDocUri, sanitizedLabel)

                        // 2. 【統合スキャン】現在のフォルダ ＋ 直下のラベルフォルダの両方をスキャンして次のグループ番号を決定
                        val currentFolderNames = allImages.map { it.displayName }
                        val subFolderNames = fileOps.queryFolderChildNamesSaf(treeUri, targetFolderUri)
                        val combinedNames = currentFolderNames + subFolderNames
                        val groupIndex = com.hazuki.imageorganizer.data.RenameMoveHelper.findNextGroupIndexFromNames(combinedNames, sanitizedLabel)
                        val groupCode = com.hazuki.imageorganizer.data.RenameMoveHelper.toSeqCode(groupIndex)

                        // 3. 安全第一のコピー処理（全件成功するまで元ファイルは消さない）
                        val copiedNewUris = mutableListOf<android.net.Uri>()
                        var failedIndex = -1

                        for ((index, item) in targets.withIndex()) {
                            val imageCode = com.hazuki.imageorganizer.data.RenameMoveHelper.toSeqCode(index + 1) // 1枚目=01, 5枚目=05
                            val ext = item.extension
                            val destFileName = if (ext.isNotBlank()) {
                                "${sanitizedLabel}_${groupCode}_${imageCode}.$ext"
                            } else {
                                "${sanitizedLabel}_${groupCode}_${imageCode}"
                            }

                            // ① 移動先フォルダ内に、リネーム後の新ファイルを作成
                            val newDocUri = try {
                                android.provider.DocumentsContract.createDocument(
                                    resolver, targetFolderUri, item.mimeType, destFileName
                                )
                            } catch (e: Exception) {
                                null
                            }

                            if (newDocUri == null) {
                                failedIndex = index + 1
                                break
                            }

                            // ② データを新しいファイルへストリームコピー
                            val copyOk = try {
                                resolver.openInputStream(item.uri)?.use { input ->
                                    resolver.openOutputStream(newDocUri)?.use { output ->
                                        input.copyTo(output)
                                        true
                                    }
                                } ?: false
                            } catch (e: Exception) {
                                false
                            }

                            if (!copyOk) {
                                // コピー失敗時は不完全な新ファイルを削除
                                try { android.provider.DocumentsContract.deleteDocument(resolver, newDocUri) } catch (e: Exception) {}
                                failedIndex = index + 1
                                break
                            }

                            copiedNewUris.add(newDocUri)
                        }

                        // 4. 【ロールバック処理】途中で1枚でも失敗した場合は作成した新ファイルを全て削除
                        if (failedIndex != -1) {
                            for (uri in copiedNewUris) {
                                try { android.provider.DocumentsContract.deleteDocument(resolver, uri) } catch (e: Exception) {}
                            }
                            _uiState.update { 
                                it.copy(snackbarMessage = "エラー: ${targets.size}枚中 ${failedIndex}枚目の移動に失敗したため処理を中断し、元の状態に戻しました") 
                            }
                            return@launch
                        }

                        // 5. 【全件成功時のみ元ファイルを削除】
                        for (item in targets) {
                            try {
                                android.provider.DocumentsContract.deleteDocument(resolver, item.uri)
                                successCount++
                            } catch (e: Exception) {
                                // 個別の削除失敗があっても継続
                            }
                        }
                    }

                    // ---- ② ローカルフォルダ（ZIP展開時など）の場合 ----
                    zipDir != null -> {
                        val sourceFiles = targets.mapNotNull { item ->
                            item.uri.path?.let { java.io.File(it) }
                        }
                        val parentFolder = sourceFiles.firstOrNull()?.parentFile ?: zipDir
                        val result = com.hazuki.imageorganizer.data.RenameMoveHelper.execute(
                            sourceFiles = sourceFiles,
                            destFolderRoot = parentFolder,
                            label = sanitizedLabel,
                            mode = com.hazuki.imageorganizer.data.RenameMoveHelper.ExecuteMode.MOVE
                        )
                        when (result) {
                            is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.Success -> {
                                successCount = result.count
                            }
                            is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.Failure -> {
                                _uiState.update { 
                                    it.copy(snackbarMessage = "エラー: ${result.total}枚中 ${result.succeededCount + 1}枚目で失敗したため処理を中断し、元の状態に戻しました") 
                                }
                                return@launch
                            }
                            is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.TooMany -> {
                                _uiState.update { it.copy(snackbarMessage = "上限超過: 一度に処理できる上限を超えています") }
                                return@launch
                            }
                        }
                    }

                    // ---- ③ 既定フォルダ（Download/未整理 など MediaStore経由）の場合 ----
                    else -> {
                        _uiState.update { it.copy(snackbarMessage = "この操作を行うには、上部の📁アイコンから対象フォルダを選択して開いてください") }
                        return@launch
                    }
                }

                // 選択解除＆グループ所属の解除
                removeImagesFromAllGroups(targets.map { it.id })
                syncBackupAfterFileRemoval(targets.map { it.id }.toSet())
                clearSelection()

                // 結果通知＆フォルダの再読み込みで画面を最新化
                _uiState.update { it.copy(snackbarMessage = "${successCount}枚を「$sanitizedLabel」フォルダに連番移動しました") }
                reloadCurrentFolder(0)

            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "エラーが発生しました: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * 【その場で連番リネーム（フォルダ移動なし）】
     * 選択された画像を、現在のフォルダ内で「ラベル_グループ_連番」の形式に名前を変更します。
     * 途中で失敗した場合は自動的に元の名前にロールバック（復元）します。
     */
    fun executeRenameOnly(label: String) {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "リネーム対象の画像が選択されていません") }
            return
        }
        if (targets.size > com.hazuki.imageorganizer.data.RenameMoveHelper.MAX_SEQUENCE) {
            _uiState.update { it.copy(snackbarMessage = "一度に処理できる上限（${com.hazuki.imageorganizer.data.RenameMoveHelper.MAX_SEQUENCE}枚）を超えています") }
            return
        }

        // ラベル名の正規化（スペース削除 ＋ 禁則文字を「_」に置換）
        val rawLabel = label.ifBlank { "未分類" }
        val sanitizedLabel = com.hazuki.imageorganizer.data.RenameMoveHelper.sanitizeForFilename(rawLabel.replace(" ", ""))

        val zipDir = currentZipDir
        val treeUri = currentFolderUri

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var successCount = 0
                val expectedNewNames = mutableSetOf<String>()

                when {
                    // ---- ① SAFフォルダの場合 ----
                    treeUri != null -> {
                        val resolver = getApplication<android.app.Application>().contentResolver
                        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                        val parentDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

                        // 【統合スキャン】現在のフォルダ ＋ 直下にラベルフォルダがあればその中のファイル名も合算
                        val currentFolderNames = allImages.map { it.displayName }
                        val subFolderUri = fileOps.findSubFolderUriSaf(resolver, treeUri, parentDocUri, sanitizedLabel)
                        val subFolderNames = if (subFolderUri != null) {
                            fileOps.queryFolderChildNamesSaf(treeUri, subFolderUri)
                        } else {
                            emptyList()
                        }
                        val combinedNames = currentFolderNames + subFolderNames
                        val groupIndex = com.hazuki.imageorganizer.data.RenameMoveHelper.findNextGroupIndexFromNames(combinedNames, sanitizedLabel)
                        val groupCode = com.hazuki.imageorganizer.data.RenameMoveHelper.toSeqCode(groupIndex)

                        // ロールバック用：(アイテム, 元のファイル名)
                        val renamedItems = mutableListOf<Pair<ImageItem, String>>()
                        var failedIndex = -1

                        for ((index, item) in targets.withIndex()) {
                            val imageCode = com.hazuki.imageorganizer.data.RenameMoveHelper.toSeqCode(index + 1) // 1枚目=01, 5枚目=05
                            val ext = item.extension
                            val newName = if (ext.isNotBlank()) {
                                "${sanitizedLabel}_${groupCode}_${imageCode}.$ext"
                            } else {
                                "${sanitizedLabel}_${groupCode}_${imageCode}"
                            }

                            // 既に目的の名前と同じなら、OSにリネーム命令を出さない（OSの(1)付与誤作動を防止）
                            if (item.displayName == newName) {
                                renamedItems.add(item to item.displayName)
                                expectedNewNames.add(newName)
                                successCount++
                                continue
                            }

                            try {
                                android.provider.DocumentsContract.renameDocument(resolver, item.uri, newName)
                                renamedItems.add(item to item.displayName)
                                expectedNewNames.add(newName)
                                successCount++
                            } catch (e: Exception) {
                                failedIndex = index + 1
                                break
                            }
                        }

                        // 途中で失敗した場合：それまでリネームしたファイルを元の名前に戻す（ロールバック）
                        if (failedIndex != -1) {
                            renamedItems.asReversed().forEach { (origItem, origName) ->
                                try {
                                    android.provider.DocumentsContract.renameDocument(resolver, origItem.uri, origName)
                                } catch (e: Exception) {}
                            }
                            _uiState.update { 
                                it.copy(snackbarMessage = "エラー: ${targets.size}枚中 ${failedIndex}枚目のリネームに失敗したため処理を中断し、元の名前に戻しました") 
                            }
                            return@launch
                        }
                    }

                    // ---- ② ローカルフォルダの場合 ----
                    zipDir != null -> {
                        val sourceFiles = targets.mapNotNull { item ->
                            item.uri.path?.let { java.io.File(it) }
                        }
                        val result = com.hazuki.imageorganizer.data.RenameMoveHelper.renameInPlace(sourceFiles, sanitizedLabel)
                        when (result) {
                            is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.Success -> {
                                successCount = result.count
                            }
                            is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.Failure -> {
                                _uiState.update { 
                                    it.copy(snackbarMessage = "エラー: ${result.total}枚中 ${result.succeededCount + 1}枚目で失敗したため処理を中断し、元の名前に戻しました") 
                                }
                                return@launch
                            }
                            is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.TooMany -> {
                                _uiState.update { it.copy(snackbarMessage = "上限超過: 一度に処理できる上限を超えています") }
                                return@launch
                            }
                        }
                    }

                    // ---- ③ 既定フォルダの場合 ----
                    else -> {
                        _uiState.update { it.copy(snackbarMessage = "この操作を行うには、上部の📁アイコンから対象フォルダを選択して開いてください") }
                        return@launch
                    }
                }

                // リネーム後も選択を維持するため、新ファイル名をセット
                if (expectedNewNames.isNotEmpty()) {
                    pendingSelectByName = expectedNewNames
                }

                _uiState.update { it.copy(snackbarMessage = "${successCount}枚を「${sanitizedLabel}_連番」にリネームしました") }
                reloadCurrentFolder(0)

            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "リネーム中にエラーが発生しました: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * 【選択画像の削除実行】
     * 既存の deleteSelected() を安全に呼び出します。
     */
    fun executeDeleteSelected() {
        deleteSelected(visibleIndex = 0)
    }

    /**
     * 【フォルダ内の一括ラベル変更 ＆ フォルダ自体のリネーム】
     * 現在開いているフォルダ内の対象連番ファイル名の「ラベル」部分を「新ラベル」に一括置換し、
     * さらに下層フォルダを開いている場合はフォルダ自体の名前も新ラベル名に安全にリネームします。
     */
    fun executeRenameGroupLabel(newLabel: String) {
        val rawLabel = newLabel.ifBlank { "未分類" }
        val sanitizedNewLabel = com.hazuki.imageorganizer.data.RenameMoveHelper.sanitizeForFilename(rawLabel.replace(" ", ""))

        val zipDir = currentZipDir
        val treeUri = currentFolderUri

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var successCount = 0
                var folderRenamed = false
                var newTreeUri: android.net.Uri? = null

                when {
                    // ---- ① SAFフォルダ（端末本体やSDカードのフォルダ）の場合 ----
                    treeUri != null -> {
                        val resolver = getApplication<android.app.Application>().contentResolver
                        // 全ファイルの中から、RenameMoveHelperの形式（ラベル_XX_XX.拡張子）に一致するものを抽出
                        val parseTargets = allImages.mapNotNull { item ->
                            val parsed = com.hazuki.imageorganizer.data.RenameMoveHelper.parseSeqName(item.displayName)
                            if (parsed != null) Pair(item, parsed) else null
                        }

                        // 1. 各対象ファイルのラベル名を一括リネーム
                        for ((item, parsed) in parseTargets) {
                            try {
                                val groupCode = com.hazuki.imageorganizer.data.RenameMoveHelper.toSeqCode(parsed.groupIndex)
                                val imageCode = com.hazuki.imageorganizer.data.RenameMoveHelper.toSeqCode(parsed.imageIndex)
                                val ext = item.extension
                                val newName = if (ext.isNotBlank()) {
                                    "${sanitizedNewLabel}_${groupCode}_${imageCode}.$ext"
                                } else {
                                    "${sanitizedNewLabel}_${groupCode}_${imageCode}"
                                }

                                // 既に目的の名前と同じなら、OSにリネーム命令を出さない（OSの(1)付与誤作動を完全に防止）
                                if (item.displayName == newName) {
                                    successCount++
                                    continue
                                }

                                android.provider.DocumentsContract.renameDocument(resolver, item.uri, newName)
                                successCount++
                            } catch (e: Exception) {
                                // 個別のファイルリネーム失敗はスキップして継続
                            }
                        }

                        // 2. フォルダそのものの名前変更（安全ガード付き）
                        try {
                            val baseTree = rootTreeUri ?: treeUri
                            val treeDocId = currentFolderDocId ?: android.provider.DocumentsContract.getTreeDocumentId(baseTree)
                            val colonIndex = treeDocId.indexOf(':')
                            val relativePath = if (colonIndex >= 0) treeDocId.substring(colonIndex + 1) else ""
                            
                            // 起点・標準システムフォルダ（ルートやDownload/DCIM直下など）は誤変更を防ぐため除外
                            val isSystemOrRoot = relativePath.isBlank() ||
                                relativePath.equals("Download", ignoreCase = true) ||
                                relativePath.equals("DCIM", ignoreCase = true) ||
                                relativePath.equals("Pictures", ignoreCase = true) ||
                                relativePath.equals("Documents", ignoreCase = true)

                            // システムルート以外のフォルダ（サブフォルダ等）の場合にフォルダ自体をリネーム
                            if (!isSystemOrRoot) {
                                // 【安全ガード】フォルダ名が既に新しい名前と同じなら、OSにリネーム命令を出さず成功扱いとする（「(1)」付与誤作動を完全に防止）
                                val currentFolderName = relativePath.substringAfterLast('/')
                                if (currentFolderName == sanitizedNewLabel) {
                                    folderRenamed = true
                                } else {
                                    val folderDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(baseTree, treeDocId)
                                    val renamedDocUri = android.provider.DocumentsContract.renameDocument(resolver, folderDocUri, sanitizedNewLabel)
                                    if (renamedDocUri != null) {
                                        val newDocId = android.provider.DocumentsContract.getDocumentId(renamedDocUri)
                                        currentFolderDocId = newDocId
                                        folderRenamed = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // フォルダ名のリネームに非対応のプロバイダ等の場合は安全のため例外をキャッチして継続
                        }
                    }

                    // ---- ② ローカルフォルダ（ZIP展開時など）の場合 ----
                    zipDir != null -> {
                        val oldLabel = _uiState.value.currentSelectedLabel.replace(" ", "")
                        val result = com.hazuki.imageorganizer.data.RenameMoveHelper.renameGroupLabel(zipDir, oldLabel, sanitizedNewLabel)
                        if (result is com.hazuki.imageorganizer.data.RenameMoveHelper.Result.Success) {
                            successCount = result.count
                        }

                        // ローカルフォルダ自体の名前変更
                        try {
                            // 【安全ガード】フォルダ名が既に新しいラベル名と同じなら、リネーム命令を出さず即時成功扱いとする（誤作動防止）
                            if (zipDir.name == sanitizedNewLabel) {
                                folderRenamed = true
                            } else {
                                val parent = zipDir.parentFile
                                if (parent != null) {
                                    val newDir = java.io.File(parent, sanitizedNewLabel)
                                    if (zipDir.renameTo(newDir)) {
                                        currentZipDir = newDir
                                        folderRenamed = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // 失敗時はスキップして継続
                        }
                    }

                    else -> {
                        _uiState.update { it.copy(snackbarMessage = "この操作を行うには、上部の📁アイコンから対象フォルダを選択して開いてください") }
                        return@launch
                    }
                }

                // 実行結果メッセージの組み立て
                val resultMessage = if (folderRenamed) {
                    "${successCount}件のファイルとフォルダ名を「$sanitizedNewLabel」に変更しました"
                } else {
                    "${successCount}件のラベル名を「$sanitizedNewLabel」に一括変更しました"
                }

                // UIスレッドで選択状態の解除とフォルダの同期更新を行う
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    clearSelection()
                    _uiState.update { it.copy(snackbarMessage = resultMessage) }

                    val baseTree = rootTreeUri ?: treeUri
                    if (folderRenamed && baseTree != null) {
                        // フォルダ名が変わった場合：起点マスターキーの権限を維持したまま新DocIdで開き直す（権限エラーゼロ）
                        // 【履歴保護】現在地がルート（親）フォルダの場合のみ履歴を更新し、下層フォルダの場合は履歴を上書きしない
                        val isRootFolder = (currentFolderDocId == null || currentFolderDocId == android.provider.DocumentsContract.getTreeDocumentId(baseTree))
                        openFolder(
                            treeUri = baseTree,
                            label = sanitizedNewLabel,
                            recordHistory = isRootFolder,
                            clearBackStack = false,
                            targetDocId = currentFolderDocId
                        )
                    } else if (folderRenamed && zipDir != null) {
                        // ZIPモードでローカルフォルダ名が変わった場合
                        _uiState.update { it.copy(currentFolderLabel = sanitizedNewLabel) }
                        reloadCurrentFolder(0)
                    } else {
                        // フォルダ自体のリネームが行われなかった場合（ファイルのみ変更）
                        reloadCurrentFolder(0)
                    }
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = "一括変更中にエラーが発生しました: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * 【選択画像のコピー】
     * ユーザーが選択した別フォルダへ、選択中の画像をそのままの名前でコピーします。
     * 
     * @param destTreeUri コピー先のフォルダUri（SAF）
     */
    /**
     * 【※3：選択画像を親フォルダへ移動（安全第一のCopy-then-Delete方式）】
     * 「連番→📁[ラベル名]」の移動処理と同様に、
     * 1. 移動先（親フォルダ）へ全ファイルを先にコピー作成
     * 2. 1枚でも失敗した場合は作成した新ファイルを全て削除（ロールバック）して中断
     * 3. 全件のコピーが完全に成功した場合のみ、現在のフォルダから元ファイルを削除
     * という手順を踏むことで、データの消失を絶対に防ぐ安全設計で移動を行います。
     */
    fun executeMoveSelectedToParent() {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "移動対象の画像が選択されていません") }
            return
        }

        // 親フォルダに戻れる状態（folderBackStackに親情報がある）でなければ実行しない
        if (folderBackStack.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "親フォルダの情報が見つかりません") }
            return
        }

        // スタックの一番上（直前の親フォルダ情報）を参照（取り出しはせず情報だけ取得）
        val parent = folderBackStack.lastOrNull()
        val baseTree = parent?.uri ?: rootTreeUri ?: currentFolderUri
        if (baseTree == null) {
            _uiState.update { it.copy(snackbarMessage = "親フォルダへのアクセス権限情報が見つかりません") }
            return
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = getApplication<android.app.Application>().contentResolver
            try {
                // 親フォルダのDocumentIdから親フォルダのDocumentUriを組み立て
                val parentDocId = parent?.docId ?: android.provider.DocumentsContract.getTreeDocumentId(baseTree)
                val parentFolderDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(baseTree, parentDocId)

                // 1. 安全第一のコピー処理（全件成功するまで元ファイルは消さない）
                val copiedNewUris = mutableListOf<android.net.Uri>()
                var failedIndex = -1

                for ((index, item) in targets.withIndex()) {
                    // 親フォルダ内に同じファイル名で新規ファイルを作成
                    val newDocUri = try {
                        android.provider.DocumentsContract.createDocument(
                            resolver, parentFolderDocUri, item.mimeType, item.displayName
                        )
                    } catch (e: Exception) {
                        null
                    }

                    if (newDocUri == null) {
                        failedIndex = index + 1
                        break
                    }

                    // データを新しいファイルへストリームコピー
                    val copyOk = try {
                        resolver.openInputStream(item.uri)?.use { input ->
                            resolver.openOutputStream(newDocUri)?.use { output ->
                                input.copyTo(output)
                                true
                            }
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }

                    if (!copyOk) {
                        // コピー失敗時は不完全な新ファイルを削除
                        try {
                            android.provider.DocumentsContract.deleteDocument(resolver, newDocUri)
                        } catch (e: Exception) {
                            // 無視
                        }
                        failedIndex = index + 1
                        break
                    }

                    copiedNewUris.add(newDocUri)
                }

                // 2. 【ロールバック処理】途中で1枚でも失敗した場合は作成した新ファイルを全て削除して処理を中断
                if (failedIndex != -1) {
                    for (uri in copiedNewUris) {
                        try {
                            android.provider.DocumentsContract.deleteDocument(resolver, uri)
                        } catch (e: Exception) {
                            // 無視
                        }
                    }
                    _uiState.update {
                        it.copy(snackbarMessage = "エラー: ${targets.size}枚中 ${failedIndex}枚目の移動に失敗したため処理を中断し、元の状態に戻しました")
                    }
                    return@launch
                }

                // 3. 【全件成功時のみ元ファイルを削除】
                var successCount = 0
                for (item in targets) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(resolver, item.uri)
                        successCount++
                    } catch (e: Exception) {
                        // 個別の削除失敗があっても継続
                    }
                }

                // 4. 移動完了後の後片付け：グループ登録から除外し、選択解除して一覧を最新化
                syncBackupAfterFileRemoval(targets.map { it.id }.toSet())
                removeImagesFromAllGroups(targets.map { it.id })
                clearSelection()
                _uiState.update {
                    it.copy(snackbarMessage = "${successCount}枚の画像を親フォルダへ移動しました")
                }
                // 現在のフォルダを再読み込みして移動したファイルを画面から反映
                reloadCurrentFolder(0)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(snackbarMessage = "親フォルダへの移動中にエラーが発生しました: ${e.localizedMessage}")
                }
            }
        }
    }
}
