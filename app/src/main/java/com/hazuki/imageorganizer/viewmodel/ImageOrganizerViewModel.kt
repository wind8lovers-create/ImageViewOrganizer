package com.hazuki.imageorganizer.viewmodel

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hazuki.imageorganizer.data.DisplayEntry
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.ImageRepository
import com.hazuki.imageorganizer.data.LoadProgress
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.RecentEntryType
import com.hazuki.imageorganizer.data.RecentFoldersStore
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.util.ColorGroupPreset
import com.hazuki.imageorganizer.util.ColorPalette
import com.hazuki.imageorganizer.util.FileOperations
import com.hazuki.imageorganizer.util.ImageGrouping
import com.hazuki.imageorganizer.util.RenameUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private sealed class PendingMediaAction {
    data class Delete(val targets: List<ImageItem>, val restoreScrollIndex: Int) : PendingMediaAction()
    data class Move(val targets: List<ImageItem>, val restoreScrollIndex: Int) : PendingMediaAction()
    data class Rename(val orderedTargets: List<ImageItem>, val restoreScrollIndex: Int, val prefix: String) : PendingMediaAction()
}

class ImageOrganizerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImageRepository(application)
    private val fileOps = FileOperations(application)
    private val recentStore = RecentFoldersStore(application)

    private val _uiState = MutableStateFlow(OrganizerUiState())
    val uiState: StateFlow<OrganizerUiState> = _uiState.asStateFlow()

    // ソート済みの生画像リスト(グループ化前の状態を保持しておく)
    private var allImages: List<ImageItem> = emptyList()

    private var groupingJob: Job? = null
    private var slideshowJob: Job? = null
    private var thresholdDebounceJob: Job? = null

    private var pendingAction: PendingMediaAction? = null
    private val _intentSenderRequest = MutableStateFlow<IntentSender?>(null)
    val intentSenderRequest: StateFlow<IntentSender?> = _intentSenderRequest.asStateFlow()

    // 現在開いているフォルダのソース(再読込時にどちらを呼び直すか判定するため)
    private var currentFolderUri: Uri? = null
    private var currentZipDir: java.io.File? = null

    /**
     * リネーム直後、次のreloadCurrentFolder()完了時にこの名前群と一致する画像を選択し直すための一時保存。
     * SAF/ローカルではリネームによって画像のID自体が変わりうる(URIやパスが変わるため)ので、
     * IDの一致に頼らず「リネームで付くはずの新ファイル名」との一致で選択を復元する。
     */
    private var pendingSelectByName: Set<String>? = null

    init {
        _uiState.update { it.copy(recentEntries = recentStore.getHistory()) }
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

    /**
     * @param scrollTarget 読込完了後に一覧をスクロールさせる位置。
     *   フォルダを新しく開く操作(起動時の自動復元・フォルダ選択・履歴選択)では既定値の0(先頭)のままでよい。
     *   移動/削除/リネーム後の再読込(reloadCurrentFolder経由)では、実行前の表示位置を渡すことで
     *   一覧の見ていた位置をなるべく維持する。
     */
    fun loadDocumentsFolder(scrollTarget: Int = 0) {
        currentFolderUri = null
        currentZipDir = null
        _uiState.update { it.copy(isLoading = true, isStreaming = true, currentFolderLabel = "Download/未整理", folderTotalCount = 0) }
        allImages = emptyList()
        viewModelScope.launch {
            repository.loadDefaultFolderStreaming()
                .onCompletion {
                    _uiState.update { s -> s.copy(isStreaming = false) }
                    applySortAndGroup()
                    requestScroll(scrollTarget)
                }
                .collect { progress -> onBatchReceived(progress) }
        }
    }

    fun openFolder(treeUri: Uri, label: String, recordHistory: Boolean = true, scrollTarget: Int = 0) {
        currentFolderUri = treeUri
        currentZipDir = null
        _uiState.update { it.copy(isLoading = true, isStreaming = true, currentFolderLabel = label, folderTotalCount = 0) }
        allImages = emptyList()
        if (recordHistory) {
            recentStore.recordOpened(RecentEntry(RecentEntryType.FOLDER, treeUri.toString(), label, System.currentTimeMillis()))
            _uiState.update { it.copy(recentEntries = recentStore.getHistory()) }
        }
        viewModelScope.launch {
            try {
                repository.loadFromTreeStreaming(treeUri)
                    .onCompletion {
                        _uiState.update { s -> s.copy(isStreaming = false) }
                        applySortAndGroup()
                        requestScroll(scrollTarget)
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
        currentFolderUri = null
        _uiState.update { it.copy(isLoading = true, isStreaming = true, currentFolderLabel = "$label (ZIP)", folderTotalCount = 0) }
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
                        applySortAndGroup()
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
    private fun reloadCurrentFolder(scrollTarget: Int) {
        val zipDir = currentZipDir
        val uri = currentFolderUri
        when {
            zipDir != null -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isStreaming = true) }
                    repository.loadFromLocalDirectoryStreaming(zipDir)
                        .onCompletion {
                            _uiState.update { s -> s.copy(isStreaming = false) }
                            applySortAndGroup()
                            requestScroll(scrollTarget)
                        }
                        .collect { progress -> onBatchReceived(progress) }
                }
            }
            uri != null -> openFolder(uri, _uiState.value.currentFolderLabel, recordHistory = false, scrollTarget = scrollTarget)
            else -> loadDocumentsFolder(scrollTarget = scrollTarget)
        }
    }

    /** 一覧をこの位置までスクロールさせるよう、Compose側(MainScreen)に一時的な指示を出す */
    private fun requestScroll(index: Int) {
        _uiState.update { it.copy(pendingScrollRequest = ScrollRequest(index.coerceAtLeast(0))) }
    }

    /**
     * ストリーミング中の各バッチ受信時に呼ぶ。読み込み中は「読み込み済み/総数」の表示更新だけ行い、
     * 重いグループ化(ハッシュ計算)は走らせない(読み込み完了後にonCompletionで1回だけ実行する)。
     * これにより、以前は「バッチが来るたびにグループ化を中断→やり直し」となっていた問題を解消し、
     * 大量枚数のフォルダでも「グループ: 0件」のまま止まって見える現象を防ぐ。
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
        // 読み込み中は軽量なシングル表示のみ更新する(entriesを空のままにして
        // 「画像が見つかりませんでした」の誤表示が出ないようにするため)。
        // 重いグループ化(ハッシュ計算)は読み込み完了(onCompletion)まで待ってから1回だけ実行する。
        val sorted = sortedImages(_uiState.value.sortOption)
        _uiState.update { it.copy(entries = sorted.map { img -> DisplayEntry.Single(img) }) }
    }

    // ------------------------------------------------------------------
    // ソート / グループ化
    // ------------------------------------------------------------------

    private fun sortedImages(sortOption: SortOption): List<ImageItem> {
        return when (sortOption) {
            SortOption.NAME_ASC -> allImages.sortedBy { it.displayName.lowercase() }
            SortOption.NAME_DESC -> allImages.sortedByDescending { it.displayName.lowercase() }
            SortOption.SIZE_ASC -> allImages.sortedBy { it.sizeBytes }
            SortOption.SIZE_DESC -> allImages.sortedByDescending { it.sizeBytes }
            SortOption.DATE_ASC -> allImages.sortedBy { it.dateModifiedEpochSec }
            SortOption.DATE_DESC -> allImages.sortedByDescending { it.dateModifiedEpochSec }
            SortOption.TAKEN_ASC -> allImages.sortedBy { it.effectiveTakenEpochMillis }
            SortOption.TAKEN_DESC -> allImages.sortedByDescending { it.effectiveTakenEpochMillis }
            SortOption.TYPE_ASC -> allImages.sortedBy { it.extension }
            SortOption.TYPE_DESC -> allImages.sortedByDescending { it.extension }
        }
    }

    private fun applySortAndGroup() {
        reconcileSelection()
        val state = _uiState.value
        val sorted = sortedImages(state.sortOption)

        if (state.extensionActive) {
            applyExtensionFilter(sorted, state)
            return
        }

        val colorOnlyMode = !state.sameImageOnly && state.colorPreset != null

        if (!state.sameImageOnly && !colorOnlyMode) {
            // 「同画像のみ表示」OFF かつプリセットも未選択: 通常のソート表示のみ
            groupingJob?.cancel()
            _uiState.update { it.copy(entries = sorted.map { img -> DisplayEntry.Single(img) }, isGrouping = false, groupCount = 0) }
            return
        }

        // 以下、グループ化が必要なケース:
        //  ・「同画像のみ表示」ON: ハッシュの近さ(+任意でプリセットの彩度・明度)でグルーピング
        //  ・「同画像のみ表示」OFF かつプリセット選択中: プリセットの彩度・明度のみでグルーピング(ハッシュ不使用)
        groupingJob?.cancel()
        groupingJob = viewModelScope.launch {
            _uiState.update { it.copy(isGrouping = true) } // 画面消灯防止(ハッシュ計算中も画面がつくようにする)
            // 彩度・明度は知覚ハッシュと同じ処理(computeHashAndColor)で一緒に計算されるため、
            // 色味のみのモードでも同じ関数を呼ぶ(ハッシュ自体はこのモードでは使わない)。
            val hashed = repository.computeHashes(sorted)
            val preset = _uiState.value.colorPreset

            val groups = if (colorOnlyMode) {
                val p = preset ?: ColorGroupPreset.B
                ImageGrouping.groupByColor(
                    hashed,
                    saturationTolerance = p.saturationTolerance,
                    brightnessTolerance = p.brightnessTolerance
                )
            } else {
                ImageGrouping.group(
                    hashed,
                    _uiState.value.groupThreshold,
                    saturationTolerance = preset?.saturationTolerance,
                    brightnessTolerance = preset?.brightnessTolerance
                )
            }

            // 画像id -> groupId の逆引き
            val imageIdToGroup = mutableMapOf<Long, Int>()
            groups.forEach { (groupId, items) -> items.forEach { imageIdToGroup[it.id] = groupId } }

            // 重要: グループのメンバーは元のソート順ではバラバラの位置に散らばっているため、
            // そのままでは各画像が孤立した1枚だけの枠になってしまい、グループ化の効果が見た目に出ない。
            // ここで、各グループの初出位置にメンバー全員をまとめて並べ替える。
            val emittedGroups = mutableSetOf<Int>()
            val reordered = mutableListOf<DisplayEntry>()
            var colorToggle = 0
            val hideSingles = _uiState.value.hideSinglesWhenGrouped

            for (img in hashed) {
                val gid = imageIdToGroup[img.id]
                if (gid == null) {
                    if (!hideSingles) reordered += DisplayEntry.Single(img)
                } else if (gid !in emittedGroups) {
                    emittedGroups += gid
                    val members = hashed.filter { imageIdToGroup[it.id] == gid }
                    members.forEach { m -> reordered += DisplayEntry.Grouped(m, gid, colorToggle) }
                    colorToggle = 1 - colorToggle
                }
                // 既に出力済みのグループのメンバーはここでスキップ(初出時にまとめて出力済みのため)
            }

            _uiState.update { it.copy(entries = reordered, groupCount = groups.size, isGrouping = false) }
        }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option, sortSheetVisible = false) }
        if (option == SortOption.TAKEN_ASC || option == SortOption.TAKEN_DESC) {
            // 撮影日ソートの時だけEXIFを読みに行く(他のソートでは不要な重い処理を避ける)
            viewModelScope.launch {
                allImages = repository.ensureDateTaken(allImages)
                applySortAndGroup()
            }
        } else {
            applySortAndGroup()
        }
    }

    fun toggleSortSheet(visible: Boolean) {
        if (_uiState.value.sameImageOnly) return // ON中はソート無効
        _uiState.update { it.copy(sortSheetVisible = visible) }
    }

    fun toggleSameImageOnly() {
        val newValue = !_uiState.value.sameImageOnly
        _uiState.update { it.copy(sameImageOnly = newValue, sortSheetVisible = false) }
        applySortAndGroup()
    }

    fun setGroupThreshold(value: Int) {
        val clamped = value.coerceIn(ImageGrouping.THRESHOLD_RANGE.first, ImageGrouping.THRESHOLD_RANGE.last)
        _uiState.update { it.copy(groupThreshold = clamped) }
        if (_uiState.value.sameImageOnly) {
            // スライダーのドラッグ中に毎フレーム重い再グループ化が走らないよう、300ms のデバウンスをかける
            thresholdDebounceJob?.cancel()
            thresholdDebounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                applySortAndGroup()
            }
        }
    }

    /** グループに属さない単独画像を隠して、グループのみ表示する(設定の効果を確認しやすくするため) */
    fun toggleHideSingles() {
        _uiState.update { it.copy(hideSinglesWhenGrouped = !it.hideSinglesWhenGrouped) }
        val state = _uiState.value
        if (state.sameImageOnly || (state.colorPreset != null)) {
            applySortAndGroup()
        }
    }

    fun setThumbnailSize(size: ThumbnailSize) {
        _uiState.update { it.copy(thumbnailSize = size) }
    }

    /** 彩度・明度プリセット(A/B/C)を選択/解除する。もう一度同じものを押すと解除(彩度・明度は判定に使わない)。
     *  「同画像のみ表示」がOFFの場合でも、プリセット選択中は彩度・明度のみでグルーピングする。 */
    fun setColorPreset(preset: ColorGroupPreset) {
        _uiState.update { it.copy(colorPreset = if (it.colorPreset == preset) null else preset) }
        applySortAndGroup()
    }

    // ------------------------------------------------------------------
    // 選択モード
    // ------------------------------------------------------------------

    fun startSelection(imageId: Long) {
        _uiState.update { it.copy(selectionMode = true, selectedIds = setOf(imageId)) }
    }

    fun toggleSelected(imageId: Long) {
        _uiState.update { state ->
            val newSet = state.selectedIds.toMutableSet()
            if (!newSet.add(imageId)) newSet.remove(imageId)
            val stillSelecting = newSet.isNotEmpty()
            state.copy(selectedIds = newSet, selectionMode = stillSelecting)
        }
    }

    fun selectGroup(groupId: Int) {
        val idsInGroup = _uiState.value.entries
            .filterIsInstance<DisplayEntry.Grouped>()
            .filter { it.groupId == groupId }
            .map { it.image.id }
        _uiState.update { state ->
            val newSet = state.selectedIds.toMutableSet().apply { addAll(idsInGroup) }
            state.copy(selectionMode = true, selectedIds = newSet)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    private fun selectedImages(): List<ImageItem> {
        val ids = _uiState.value.selectedIds
        return allImages.filter { it.id in ids }
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

    /**
     * 拡張機能(スタイル一致度検索)のフィルタを一覧に反映する。
     * 基準画像との類似度は、保存済みの代表色パレット同士の距離計算のみで判定するため、
     * スライダー操作のたびに呼ばれても画像本体の再デコードは発生しない
     * (パレット自体は repository.computeHashes() が既存の仕組みと同様にキャッシュしており、
     *  未計算の画像がある場合のみそこでバックグラウンド計算される)。
     */
    private fun applyExtensionFilter(sorted: List<ImageItem>, state: OrganizerUiState) {
        groupingJob?.cancel()
        val origin = allImages.firstOrNull { it.id == state.originImageId }
        if (origin == null) {
            // 基準画像が移動・削除等で無くなっていた場合は、フィルタを解除して通常表示に戻す
            _uiState.update {
                it.copy(
                    extensionActive = false,
                    extensionSheetVisible = false,
                    originImageId = null,
                    entries = sorted.map { img -> DisplayEntry.Single(img) },
                    isGrouping = false,
                    snackbarMessage = "基準画像が見つからないため、拡張機能を解除しました"
                )
            }
            return
        }
        groupingJob = viewModelScope.launch {
            _uiState.update { it.copy(isGrouping = true) }
            // 代表色パレット・アスペクト比が未計算の画像だけ、ここでバックグラウンド計算する
            val analyzed = repository.computeHashes(sorted)
            val current = _uiState.value
            val filtered = analyzed.filter { candidate ->
                if (candidate.id == origin.id) return@filter true // 基準画像自身は常に表示する
                val aspectOk = !current.aspectRatioOnly || run {
                    val oa = origin.aspectRatio
                    val ca = candidate.aspectRatio
                    oa != null && ca != null && ColorPalette.aspectRatioMatches(oa, ca)
                }
                val styleOk = current.styleMatchThreshold <= 0 || run {
                    val op = origin.colorPalette
                    val cp = candidate.colorPalette
                    op != null && cp != null && ColorPalette.similarity(op, cp) * 100f >= current.styleMatchThreshold
                }
                aspectOk && styleOk
            }
            _uiState.update {
                it.copy(
                    entries = filtered.map { img -> DisplayEntry.Single(img) },
                    isGrouping = false,
                    groupCount = filtered.size
                )
            }
        }
    }

    /**
     * 「拡張」ボタン押下時の入り口。選択中の画像を基準(origin)にして設定パネルを開く。
     * 選択が1枚ならその画像自身、2枚以上なら(ソート表示上での)最初の画像を基準にする。
     */
    fun openExtensionPanel() {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "まず、似た画像を探したい画像を選択してください") }
            return
        }
        val currentOrder = _uiState.value.entries.mapNotNull { entry ->
            when (entry) {
                is DisplayEntry.Single -> entry.image
                is DisplayEntry.Grouped -> entry.image
            }
        }
        val origin = currentOrder.firstOrNull { it.id in selectedIds } ?: selectedImages().firstOrNull()
        if (origin == null) {
            _uiState.update { it.copy(snackbarMessage = "基準にする画像が見つかりませんでした") }
            return
        }
        _uiState.update {
            it.copy(
                extensionSheetVisible = true,
                extensionActive = true,
                originImageId = origin.id,
                aspectRatioOnly = false,
                styleMatchThreshold = 0
            )
        }
        applySortAndGroup()
    }

    /** 設定パネル(ボトムシート)を閉じる。フィルタ自体は維持する(再度「拡張」を押すか解除操作でOFFにする) */
    fun closeExtensionSheet() {
        _uiState.update { it.copy(extensionSheetVisible = false) }
    }

    fun toggleAspectRatioOnly(enabled: Boolean) {
        _uiState.update { it.copy(aspectRatioOnly = enabled) }
        applySortAndGroup()
    }

    /** スタイル一致度スライダー(0〜100)。ドラッグ中に毎フレーム再フィルタが走らないようデバウンスする。 */
    fun setStyleMatchThreshold(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _uiState.update { it.copy(styleMatchThreshold = clamped) }
        thresholdDebounceJob?.cancel()
        thresholdDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            applySortAndGroup()
        }
    }

    /** 拡張機能のフィルタを解除し、通常の一覧表示に戻す */
    fun disableExtensionFilter() {
        _uiState.update {
            it.copy(
                extensionActive = false,
                extensionSheetVisible = false,
                originImageId = null,
                aspectRatioOnly = false,
                styleMatchThreshold = 0
            )
        }
        applySortAndGroup()
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
                _uiState.update { it.copy(snackbarMessage = "${moved.size}件を_Moved_に移動しました") }
                reloadCurrentFolder(visibleIndex)
            }
            treeUri != null -> viewModelScope.launch {
                val moved = fileOps.moveToMovedFolderSaf(treeUri, targets)
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

    /** @param visibleIndex 実行直前に一覧で見えていた先頭位置(削除後の再読込でこの位置付近を維持する) */
    fun deleteSelected(visibleIndex: Int) {
        val targets = selectedImages()
        if (targets.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "削除対象が選択されていません") }
            return
        }
        val zipDir = currentZipDir
        val treeUri = currentFolderUri
        when {
            zipDir != null -> viewModelScope.launch {
                val count = fileOps.deleteImagesLocal(targets)
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
                reloadCurrentFolder(visibleIndex)
            }
            treeUri != null -> viewModelScope.launch {
                val count = fileOps.deleteImagesSaf(targets)
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
                reloadCurrentFolder(visibleIndex)
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
        val currentOrder = _uiState.value.entries.mapNotNull { entry ->
            when (entry) {
                is DisplayEntry.Single -> entry.image
                is DisplayEntry.Grouped -> entry.image
            }
        }
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
                    clearSelection()
                    _uiState.update { it.copy(snackbarMessage = "${action.targets.size}件を削除しました") }
                    reloadCurrentFolder(action.restoreScrollIndex)
                }
                is PendingMediaAction.Move -> {
                    val moved = fileOps.moveToMovedFolder(action.targets)
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
            val name = "images_${System.currentTimeMillis()}.zip"
            val zipDir = currentZipDir
            val treeUri = currentFolderUri
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
     * 選択中の画像がある場合は、選択の中で最も表示順が早いものを優先して開始位置にする。
     */
    private fun startSlideshow(visibleIndex: Int) {
        val state = _uiState.value
        val entries = state.entries
        if (entries.isEmpty()) return

        val selectedStartIndex = if (state.selectedIds.isNotEmpty()) {
            entries.indexOfFirst { entry ->
                val id = when (entry) {
                    is DisplayEntry.Single -> entry.image.id
                    is DisplayEntry.Grouped -> entry.image.id
                }
                id in state.selectedIds
            }.takeIf { it >= 0 }
        } else null

        val startIndex = (selectedStartIndex ?: visibleIndex).coerceIn(0, entries.size - 1)

        _uiState.update { it.copy(slideshowActive = true, slideshowIndex = startIndex) }
        runSlideshowLoop()
    }

    private fun runSlideshowLoop() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            while (_uiState.value.slideshowActive) {
                kotlinx.coroutines.delay(_uiState.value.slideshowInterval.seconds * 1000L)
                advanceSlideshow()
            }
        }
    }

    fun advanceSlideshow() {
        val count = _uiState.value.entries.size
        if (count == 0) return
        _uiState.update { it.copy(slideshowIndex = (it.slideshowIndex + 1) % count) }
    }

    fun setSlideshowInterval(interval: SlideshowInterval) {
        _uiState.update { it.copy(slideshowInterval = interval) }
        if (_uiState.value.slideshowActive) runSlideshowLoop() // 間隔変更を即反映
    }

    private fun stopSlideshow() {
        slideshowJob?.cancel()
        val stoppedIndex = _uiState.value.slideshowIndex
        // フルスクリーン表示は開かず、一覧側をこの位置までスクロールさせて「続きから見られる」ようにする
        _uiState.update { it.copy(slideshowActive = false, pendingScrollRequest = ScrollRequest(stoppedIndex)) }
    }

    /** 一覧のスクロール追従が完了したら呼ぶ(一度だけ実行させるため) */
    fun consumePendingScroll() {
        _uiState.update { it.copy(pendingScrollRequest = null) }
    }
}
