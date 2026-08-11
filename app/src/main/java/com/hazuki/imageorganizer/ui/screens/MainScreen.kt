package com.hazuki.imageorganizer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.components.ClassificationNameDialog
import com.hazuki.imageorganizer.ui.components.FullscreenViewer
import com.hazuki.imageorganizer.ui.components.ImageGrid
import com.hazuki.imageorganizer.ui.components.OrganizerBottomBar
import com.hazuki.imageorganizer.ui.components.OrganizerTopBar
import com.hazuki.imageorganizer.ui.components.SlideshowOverlay
import com.hazuki.imageorganizer.ui.components.SortBottomSheet
import com.hazuki.imageorganizer.ui.theme.WashiBackground
import com.hazuki.imageorganizer.viewmodel.ImageOrganizerViewModel
import com.hazuki.imageorganizer.viewmodel.ScreenMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ImageOrganizerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "選択したフォルダ"
            viewModel.openFolder(uri, label)
        }
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 一部の提供元(一部のクラウドストレージ等)は永続許可に対応していないことがあるが、
                // 今回のセッションでは開けるので処理は続行する
            }
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "選択したZIP"
            viewModel.openZipFile(uri, label)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.loadDocumentsFolder()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeSnackbar()
        }
    }

    // 大量枚数の読み込み中・拡張選択の絞り込み計算中・スライドショー中は時間がかかる/連続視聴中なため、画面が消灯しないようにする
    val view = LocalView.current
    DisposableEffect(state.isStreaming, state.isComparing, state.slideshowActive) {
        view.keepScreenOn = state.isStreaming || state.isComparing || state.slideshowActive
        onDispose { view.keepScreenOn = false }
    }

    // 一覧のスクロール位置を保持し、スライドショー開始位置・終了後の追従、フォルダ再読込後の位置復元に使う
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(state.pendingScrollRequest) {
        state.pendingScrollRequest?.let { req ->
            val target = req.index.coerceIn(0, (state.entries.size - 1).coerceAtLeast(0))
            gridState.scrollToItem(target)
            viewModel.consumePendingScroll()
        }
    }

    // 移動・削除・リネーム前にシステムの同意ダイアログを表示するためのランチャー
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onPermissionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val intentSenderRequest by viewModel.intentSenderRequest.collectAsStateWithLifecycle()
    LaunchedEffect(intentSenderRequest) {
        intentSenderRequest?.let { sender ->
            mediaPermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
            viewModel.onIntentSenderHandled()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WashiBackground(modifier = Modifier.fillMaxSize())

        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // グループ内画面(GROUP_DETAIL)は専用のヘッダーを持つため、通常の上部バーは表示しない
                if (state.screenMode != ScreenMode.GROUP_DETAIL) {
                OrganizerTopBar(
                    folderLabel = state.currentFolderLabel,
                    isZipMode = state.isZipMode,
                    folderDetailLabel = state.folderDetailLabel,
                    imageCount = state.totalImageCount,
                    folderTotalCount = state.folderTotalCount,
                    isLoading = state.isLoading,
                    isStreaming = state.isStreaming,
                    isComparing = state.isComparing,
                    onOpenFolder = { folderPickerLauncher.launch(null) },
                    onOpenZip = { zipPickerLauncher.launch(arrayOf("application/zip")) },
                    recentEntries = state.recentEntries,
                    onSelectRecent = { entry -> viewModel.openRecentEntry(entry) },
                    onSelectDefaultFolder = { viewModel.loadDocumentsFolder() },
                    extensionSelectionActive = state.extensionSelectionActive,
                    onToggleExtensionSelection = { viewModel.toggleExtensionSelection() },
                    matchedCount = state.matchedCount,
                    saturationTolerance = state.saturationTolerance,
                    onSaturationChange = { viewModel.setSaturationTolerance(it) },
                    brightnessTolerance = state.brightnessTolerance,
                    onBrightnessChange = { viewModel.setBrightnessTolerance(it) },
                    colorPresetStep = state.colorPresetStep,
                    onCyclePreset = { viewModel.cyclePresetStep() },
                    hashMatchEnabled = state.hashMatchEnabled,
                    onToggleHashMatch = { viewModel.toggleHashMatch(it) },
                    aspectRatioOnly = state.aspectRatioOnly,
                    onToggleAspectRatioOnly = { viewModel.toggleAspectRatioOnly(it) },
                    styleMatchThreshold = state.styleMatchThreshold,
                    onStyleMatchThresholdChange = { viewModel.setStyleMatchThreshold(it) },
                    slideshowActive = state.slideshowActive,
                    onToggleSlideshow = { viewModel.toggleSlideshow(gridState.firstVisibleItemIndex) },
                    thumbnailSize = state.thumbnailSize,
                    onToggleThumbnailSize = {
                        viewModel.setThumbnailSize(
                            if (state.thumbnailSize == ThumbnailSize.SMALL) ThumbnailSize.LARGE else ThumbnailSize.SMALL
                        )
                    },
                    isClassificationListMode = state.screenMode == ScreenMode.CLASSIFICATION_LIST,
                    selectionMode = state.selectionMode,
                    addModeActive = state.addModeActive,
                    onClassificationButtonClick = {
                        when {
                            state.addModeActive -> viewModel.confirmAddToGroup()
                            state.selectionMode -> viewModel.openNameDialogForNewGroup()
                            else -> viewModel.toggleScreenMode()
                        }
                    }
                )
                }
            },
            bottomBar = {
                // 分類一覧・グループ内画面では、通常の移動/削除などの下部バーは表示しない
                // (分類一覧はタップして中に入るだけ、グループ内画面は専用のバーを内包しているため)
                if (state.screenMode == ScreenMode.GALLERY) {
                OrganizerBottomBar(
                    selectionMode = state.selectionMode,
                    selectedCount = state.selectedIds.size,
                    sortEnabled = true,
                    actionsEnabled = !state.isLoading && !state.isStreaming,
                    currentSortLabel = state.sortOption.label,
                    onSortClick = { viewModel.toggleSortSheet(true) },
                    onSelectClick = { /* 選択モードは長押しで開始する仕様のため、案内のみ */ },
                    onRenameClick = { /* 通常時のリネームは「選択」してから使う操作のため未選択時は無効表示でも良い */ },
                    onMoveClick = { viewModel.moveSelectedToMovedFolder(gridState.firstVisibleItemIndex) },
                    onZipClick = { viewModel.zipSelected() },
                    onDeleteClick = { viewModel.deleteSelected(gridState.firstVisibleItemIndex) },
                    onRenameSelectedClick = { viewModel.renameSelectedSequentially(gridState.firstVisibleItemIndex) },
                    onJumpToSelectedClick = { viewModel.jumpToNextSelected() },
                    onJumpToSelectedLongClick = { viewModel.jumpToFirstSelected() },
                    currentJumpIndex = state.currentJumpIndex,
                    onClearSelectionClick = { viewModel.clearSelection() },
                    addModeActive = state.addModeActive
                )
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (state.screenMode) {
                        ScreenMode.GALLERY -> {
                            if (state.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            } else if (state.entries.isEmpty()) {
                                EmptyFolderMessage(
                                    onOpenFolder = { folderPickerLauncher.launch(null) },
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                // 選択モード中(通常の分類登録、または画像追加モード)は、
                                // 既に他のグループに入っている画像を暗く表示・選択不可にする(要件定義Q2)。
                                // 画像追加モード中は、対象と同じカテゴリの画像だけは明るく表示・選択可能にする(グループ統合)。
                                val dimmedIds = if (state.selectionMode) {
                                    state.groupedImageIds
                                        .filterKeys { id ->
                                            !(state.addModeActive && state.groupedImageIds[id] == state.addModeCategory)
                                        }
                                        .keys
                                } else {
                                    emptySet()
                                }
                                ImageGrid(
                                    entries = state.entries,
                                    thumbnailSize = state.thumbnailSize,
                                    selectedIds = state.selectedIds,
                                    selectionMode = state.selectionMode,
                                    gridState = gridState,
                                    onTap = { index ->
                                        val id = state.entries.getOrNull(index)?.id
                                        if (state.selectionMode) {
                                            if (id != null) viewModel.toggleSelected(id)
                                        } else {
                                            viewModel.openFullscreen(index)
                                        }
                                    },
                                    onLongPress = { index ->
                                        val id = state.entries.getOrNull(index)?.id
                                        if (id != null) viewModel.handleLongPress(id)
                                    },
                                    groupedImageIds = state.groupedImageIds,
                                    dimmedIds = dimmedIds,
                                    modifier = Modifier.fillMaxSize().padding(padding)
                                )
                            }
                        }
                        ScreenMode.CLASSIFICATION_LIST -> {
                            ClassificationListScreen(
                                tiles = state.classificationTiles,
                                thumbnailSize = state.thumbnailSize,
                                onTileTap = { key -> viewModel.openGroupDetail(key) },
                                onTileLongPress = { category -> viewModel.startCategorySlideshow(category) },
                                onNameLongPress = { key -> viewModel.openNameDialogForRename(key) },
                                modifier = Modifier.fillMaxSize().padding(padding)
                            )
                        }
                        ScreenMode.GROUP_DETAIL -> {
                            val group = state.classificationGroups.firstOrNull { it.key == state.activeGroupKey }
                            GroupDetailScreen(
                                groupDisplayName = group?.displayName ?: "",
                                entries = state.groupDetailEntries,
                                thumbnailSize = state.thumbnailSize,
                                selectedIds = state.groupDetailSelectedIds,
                                siblingTiles = state.classificationTiles.filter { it.group.category == group?.category },
                                currentGroupKey = state.activeGroupKey ?: "",
                                onBack = { viewModel.closeGroupDetail() },
                                onTapImage = { index -> viewModel.openFullscreen(index) },
                                onToggleSelected = { id -> viewModel.toggleGroupDetailSelected(id) },
                                onSortClick = { viewModel.toggleSortSheet(true) },
                                onAddImagesClick = { viewModel.enterAddMode() },
                                onDeleteSelectedClick = { viewModel.removeSelectedFromGroup() },
                                onSlideshowClick = { viewModel.startGroupSlideshow() },
                                onSetThumbnailClick = { id -> viewModel.setGroupThumbnail(id) },
                                onPageSettled = { key -> viewModel.openGroupDetail(key) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        if (state.sortSheetVisible) {
            SortBottomSheet(
                currentSort = if (state.screenMode == ScreenMode.GROUP_DETAIL) {
                    state.classificationGroups.firstOrNull { it.key == state.activeGroupKey }?.sortOption ?: state.sortOption
                } else {
                    state.sortOption
                },
                sheetState = sheetState,
                onSelect = {
                    if (state.screenMode == ScreenMode.GROUP_DETAIL) {
                        viewModel.setGroupDetailSortOption(it)
                    } else {
                        viewModel.setSortOption(it)
                    }
                    viewModel.toggleSortSheet(false)
                },
                onDismiss = { viewModel.toggleSortSheet(false) }
            )
        }

        state.fullscreenIndex?.let { idx ->
            // グループ内画面ではそのグループの画像リスト、それ以外は通常の一覧を対象にする
            val fullscreenSource = if (state.screenMode == ScreenMode.GROUP_DETAIL) state.groupDetailEntries else state.entries
            FullscreenViewer(
                entries = fullscreenSource,
                startIndex = idx,
                onDismiss = { viewModel.closeFullscreen() }
            )
        }

        if (state.slideshowActive) {
            SlideshowOverlay(
                entries = state.slideshowEntries,
                currentIndex = state.slideshowIndex,
                interval = state.slideshowInterval,
                onTapAdvance = { viewModel.advanceSlideshow() },
                onIntervalSelected = { viewModel.setSlideshowInterval(it) },
                onStop = { viewModel.toggleSlideshow(gridState.firstVisibleItemIndex) }
            )
        }

        // 分類登録・リネーム共用ダイアログ
        if (state.nameDialogVisible) {
            val editingKey = state.nameDialogEditingKey
            val editingGroup = editingKey?.let { key -> state.classificationGroups.firstOrNull { it.key == key } }
            ClassificationNameDialog(
                title = if (editingKey == null) "分類登録" else "グループ名の変更",
                initialCategory = editingGroup?.category ?: 'A',
                initialName = editingGroup?.name ?: "",
                previewFor = { category -> viewModel.previewForCategory(category) },
                onConfirm = { category, name -> viewModel.confirmNameDialog(category, name) },
                onDismiss = { viewModel.dismissNameDialog() }
            )
        }
    }
}

@Composable
private fun EmptyFolderMessage(
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Text(
            text = "画像が見つかりませんでした",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.material3.Text(
            text = "ファイルマネージャーなどで直接コピーした画像は、\n" +
                "端末の写真データベースにまだ登録されていないことがあります。\n" +
                "下のボタンからDocumentsフォルダを直接選択すると、\n" +
                "登録状況に関係なくすぐに読み込めます。",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark.copy(alpha = 0.8f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onOpenFolder) {
            androidx.compose.material3.Text("フォルダを選択する")
        }
    }
}
