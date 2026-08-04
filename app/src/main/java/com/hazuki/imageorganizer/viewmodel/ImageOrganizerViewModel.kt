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
import com.hazuki.imageorganizer.util.FileOperations
import com.hazuki.imageorganizer.util.ImageGrouping
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private sealed class PendingMediaAction {
    data class Delete(val targets: List<ImageItem>) : PendingMediaAction()
    data class Move(val targets: List<ImageItem>) : PendingMediaAction()
    data class Rename(val orderedTargets: List<ImageItem>) : PendingMediaAction()
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

    fun loadDocumentsFolder() {
        currentFolderUri = null
        currentZipDir = null
        _uiState.update { it.copy(isLoading = true, isStreaming = true, currentFolderLabel = "Download/未整理", folderTotalCount = 0) }
        allImages = emptyList()
        viewModelScope.launch {
            repository.loadDefaultFolderStreaming()
                .onCompletion { _uiState.update { s -> s.copy(isStreaming = false) }; applySortAndGroup() }
                .collect { progress -> onBatchReceived(progress) }
        }
    }

    fun openFolder(treeUri: Uri, label: String, recordHistory: Boolean = true) {
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
                    .onCompletion { _uiState.update { s -> s.copy(isStreaming = false) }; applySortAndGroup() }
                    .collect { progress -> onBatchReceived(progress) }
            } catch (e: SecurityException) {
                // 前回のアクセス権限が失効している場合(端末再起動などでURI許可が切れた場合)は既定フォルダへフォールバック
                _uiState.update { it.copy(snackbarMessage = "以前のフォルダにアクセスできませんでした") }
                loadDocumentsFolder()
            }
        }
    }

    /** ZIP書庫を選択した際の読込。アプリキャッシュへ展開してから通常フォルダと同様に扱う。 */
    fun openZipFile(zipUri: Uri, label: String, recordHistory: Boolean = true) {
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
                    .onCompletion { _uiState.update { s -> s.copy(isStreaming = false) }; applySortAndGroup() }
                    .collect { progress -> onBatchReceived(progress) }
            } catch (e: SecurityException) {
                _uiState.update { it.copy(snackbarMessage = "以前のZIPファイルにアクセスできませんでした") }
                loadDocumentsFolder()
            }
        }
    }

    /** 現在開いているフォルダを、そのソースのまま再読込する(移動/削除/リネーム後の更新用) */
    private fun reloadCurrentFolder() {
        val zipDir = currentZipDir
        val uri = currentFolderUri
        when {
            zipDir != null -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isStreaming = true) }
                    repository.loadFromLocalDirectoryStreaming(zipDir)
                        .onCompletion { _uiState.update { s -> s.copy(isStreaming = false) }; applySortAndGroup() }
                        .collect { progress -> onBatchReceived(progress) }
                }
            }
            uri != null -> openFolder(uri, _uiState.value.currentFolderLabel)
            else -> loadDocumentsFolder()
        }
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
        val state = _uiState.value
        val sorted = sortedImages(state.sortOption)

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
    fun moveSelectedToMovedFolder() {
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
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${moved.size}件を_Moved_に移動しました") }
                reloadCurrentFolder()
            }
            treeUri != null -> viewModelScope.launch {
                val moved = fileOps.moveToMovedFolderSaf(treeUri, targets)
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${moved.size}件を_Moved_に移動しました") }
                reloadCurrentFolder()
            }
            else -> {
                // 既定のDownload/未整理フォルダ(MediaStore経由)の場合のみ、システムの同意ダイアログが必要
                pendingAction = PendingMediaAction.Move(targets)
                _intentSenderRequest.value = fileOps.createWriteRequest(targets)
            }
        }
    }

    fun deleteSelected() {
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
                reloadCurrentFolder()
            }
            treeUri != null -> viewModelScope.launch {
                val count = fileOps.deleteImagesSaf(targets)
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
                reloadCurrentFolder()
            }
            else -> {
                pendingAction = PendingMediaAction.Delete(targets)
                _intentSenderRequest.value = fileOps.createDeleteRequest(targets)
            }
        }
    }

    /** 現在の表示(ソート)順のうち、選択された画像だけを対象にリネームする */
    fun renameSelectedSequentially() {
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
        val zipDir = currentZipDir
        val treeUri = currentFolderUri
        when {
            zipDir != null -> viewModelScope.launch {
                val count = fileOps.renameSequentiallyLocal(orderedTargets)
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
                reloadCurrentFolder()
            }
            treeUri != null -> viewModelScope.launch {
                val count = fileOps.renameSequentiallySaf(orderedTargets)
                clearSelection()
                _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
                reloadCurrentFolder()
            }
            else -> {
                pendingAction = PendingMediaAction.Rename(orderedTargets)
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
            _uiState.update { it.copy(snackbarMessage = "許可されなかったため処理を中止しました") }
            clearSelection()
            return
        }
        viewModelScope.launch {
            when (action) {
                is PendingMediaAction.Delete -> {
                    clearSelection()
                    _uiState.update { it.copy(snackbarMessage = "${action.targets.size}件を削除しました") }
                    reloadCurrentFolder()
                }
                is PendingMediaAction.Move -> {
                    val moved = fileOps.moveToMovedFolder(action.targets)
                    clearSelection()
                    _uiState.update { it.copy(snackbarMessage = "${moved.size}件を Download/_Moved_ に移動しました") }
                    reloadCurrentFolder()
                }
                is PendingMediaAction.Rename -> {
                    val count = fileOps.renameSequentially(action.orderedTargets)
                    clearSelection()
                    _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
                    reloadCurrentFolder()
                }
                null -> Unit
            }
        }
    }

    fun zipSelected() {
        val targets = selectedImages()
        viewModelScope.launch {
            val name = "images_${System.currentTimeMillis()}.zip"
            val uri = fileOps.zipImages(targets, name)
            clearSelection()
            _uiState.update {
                it.copy(snackbarMessage = if (uri != null) "ZIPを作成しました: $name" else "ZIP作成に失敗しました")
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
        _uiState.update { it.copy(slideshowActive = false, pendingScrollToIndex = stoppedIndex) }
    }

    /** 一覧のスクロール追従が完了したら呼ぶ(一度だけ実行させるため) */
    fun consumePendingScroll() {
        _uiState.update { it.copy(pendingScrollToIndex = null) }
    }
}
