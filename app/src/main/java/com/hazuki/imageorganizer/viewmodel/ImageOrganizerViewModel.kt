package com.hazuki.imageorganizer.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hazuki.imageorganizer.data.DisplayEntry
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.ImageRepository
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.util.FileOperations
import com.hazuki.imageorganizer.util.ImageGrouping
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ImageOrganizerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImageRepository(application)
    private val fileOps = FileOperations(application)

    private val _uiState = MutableStateFlow(OrganizerUiState())
    val uiState: StateFlow<OrganizerUiState> = _uiState.asStateFlow()

    // ソート済みの生画像リスト(グループ化前の状態を保持しておく)
    private var allImages: List<ImageItem> = emptyList()

    private var groupingJob: Job? = null
    private var slideshowJob: Job? = null

    // 現在開いているフォルダのソース(再読込時にどちらを呼び直すか判定するため)
    private var currentFolderUri: Uri? = null

    init {
        loadDocumentsFolder()
    }

    // ------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------

    fun loadDocumentsFolder() {
        currentFolderUri = null
        _uiState.update { it.copy(isLoading = true, isStreaming = true, currentFolderLabel = "Pictures/未整理") }
        allImages = emptyList()
        viewModelScope.launch {
            repository.loadDefaultFolderStreaming()
                .onCompletion { _uiState.update { s -> s.copy(isStreaming = false) } }
                .collect { batch ->
                    allImages = batch
                    _uiState.update { it.copy(totalImageCount = batch.size, isLoading = false) }
                    applySortAndGroup()
                }
        }
    }

    fun openFolder(treeUri: Uri, label: String) {
        currentFolderUri = treeUri
        _uiState.update { it.copy(isLoading = true, isStreaming = true, currentFolderLabel = label) }
        allImages = emptyList()
        viewModelScope.launch {
            repository.loadFromTreeStreaming(treeUri)
                .onCompletion { _uiState.update { s -> s.copy(isStreaming = false) } }
                .collect { batch ->
                    allImages = batch
                    _uiState.update { it.copy(totalImageCount = batch.size, isLoading = false) }
                    applySortAndGroup()
                }
        }
    }

    /** 現在開いているフォルダを、そのソースのまま再読込する(移動/削除/リネーム後の更新用) */
    private fun reloadCurrentFolder() {
        val uri = currentFolderUri
        if (uri != null) {
            openFolder(uri, _uiState.value.currentFolderLabel)
        } else {
            loadDocumentsFolder()
        }
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

        if (!state.sameImageOnly) {
            _uiState.update { it.copy(entries = sorted.map { img -> DisplayEntry.Single(img) }) }
            return
        }

        // グループ表示ON: ハッシュ計算 → グルーピング → 表示順に色を交互に割り当て
        groupingJob?.cancel()
        groupingJob = viewModelScope.launch {
            val hashed = repository.computeHashes(sorted)
            val groups = ImageGrouping.group(hashed, _uiState.value.groupThreshold)

            // 画像id -> groupId の逆引き
            val imageIdToGroup = mutableMapOf<Long, Int>()
            groups.forEach { (groupId, items) -> items.forEach { imageIdToGroup[it.id] = groupId } }

            var lastGroupSeen: Int? = null
            var colorToggle = 0
            val entries = hashed.map { img ->
                val gid = imageIdToGroup[img.id]
                if (gid == null) {
                    DisplayEntry.Single(img)
                } else {
                    if (gid != lastGroupSeen) {
                        colorToggle = 1 - colorToggle
                        lastGroupSeen = gid
                    }
                    DisplayEntry.Grouped(img, gid, colorToggle)
                }
            }
            _uiState.update { it.copy(entries = entries) }
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
            applySortAndGroup() // リアルタイム再グループ化
        }
    }

    fun setThumbnailSize(size: ThumbnailSize) {
        _uiState.update { it.copy(thumbnailSize = size) }
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

    fun moveSelectedToMovedFolder() {
        val targets = selectedImages()
        viewModelScope.launch {
            val moved = fileOps.moveToMovedFolder(targets)
            clearSelection()
            _uiState.update { it.copy(snackbarMessage = "${moved.size}件を Download/_Moved_ に移動しました") }
            reloadCurrentFolder()
        }
    }

    fun deleteSelected() {
        val targets = selectedImages()
        viewModelScope.launch {
            val count = fileOps.deleteImages(targets)
            clearSelection()
            _uiState.update { it.copy(snackbarMessage = "${count}件を削除しました") }
            reloadCurrentFolder()
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
        viewModelScope.launch {
            val count = fileOps.renameSequentially(orderedTargets)
            clearSelection()
            _uiState.update { it.copy(snackbarMessage = "${count}件をリネームしました") }
            reloadCurrentFolder()
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

    fun toggleSlideshow() {
        if (_uiState.value.slideshowActive) {
            stopSlideshow()
        } else {
            startSlideshow()
        }
    }

    private fun startSlideshow() {
        val count = _uiState.value.entries.size
        if (count == 0) return
        _uiState.update { it.copy(slideshowActive = true, slideshowIndex = 0) }
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
        _uiState.update { it.copy(slideshowActive = false, fullscreenIndex = stoppedIndex) }
    }
}
