package com.hazuki.imageorganizer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.hazuki.imageorganizer.ui.components.FullscreenViewer
import com.hazuki.imageorganizer.ui.components.ImageGrid
import com.hazuki.imageorganizer.ui.components.OrganizerBottomBar
import com.hazuki.imageorganizer.ui.components.OrganizerTopBar
import com.hazuki.imageorganizer.ui.components.SlideshowOverlay
import com.hazuki.imageorganizer.ui.components.SortBottomSheet
import com.hazuki.imageorganizer.ui.theme.WashiBackground
import com.hazuki.imageorganizer.viewmodel.ImageOrganizerViewModel

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

    // 大量枚数の読み込み中は時間がかかることがあるため、画面が消灯しないようにする
    val view = LocalView.current
    DisposableEffect(state.isStreaming) {
        view.keepScreenOn = state.isStreaming
        onDispose { view.keepScreenOn = false }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WashiBackground(modifier = Modifier.fillMaxSize())

        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                OrganizerTopBar(
                    folderLabel = state.currentFolderLabel,
                    imageCount = state.totalImageCount,
                    isLoading = state.isLoading,
                    onOpenFolder = { folderPickerLauncher.launch(null) },
                    sameImageOnly = state.sameImageOnly,
                    onToggleSameImageOnly = { viewModel.toggleSameImageOnly() },
                    groupThreshold = state.groupThreshold,
                    onThresholdChange = { viewModel.setGroupThreshold(it) },
                    slideshowActive = state.slideshowActive,
                    onToggleSlideshow = { viewModel.toggleSlideshow() },
                    thumbnailSize = state.thumbnailSize,
                    onToggleThumbnailSize = {
                        viewModel.setThumbnailSize(
                            if (state.thumbnailSize == ThumbnailSize.SMALL) ThumbnailSize.LARGE else ThumbnailSize.SMALL
                        )
                    }
                )
            },
            bottomBar = {
                OrganizerBottomBar(
                    selectionMode = state.selectionMode,
                    selectedCount = state.selectedIds.size,
                    sortEnabled = !state.sameImageOnly,
                    currentSortLabel = state.sortOption.label,
                    onSortClick = { viewModel.toggleSortSheet(true) },
                    onSelectClick = { /* 選択モードは長押しで開始する仕様のため、案内のみ */ },
                    onRenameClick = { /* 通常時のリネームは「選択」してから使う操作のため未選択時は無効表示でも良い */ },
                    onExtensionClick = { /* 将来の拡張機能用の予約枠 */ },
                    onMoveClick = { viewModel.moveSelectedToMovedFolder() },
                    onZipClick = { viewModel.zipSelected() },
                    onDeleteClick = { viewModel.deleteSelected() },
                    onRenameSelectedClick = { viewModel.renameSelectedSequentially() },
                    onClearSelectionClick = { viewModel.clearSelection() }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (state.entries.isEmpty()) {
                        EmptyFolderMessage(
                            onOpenFolder = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        ImageGrid(
                            entries = state.entries,
                            thumbnailSize = state.thumbnailSize,
                            selectedIds = state.selectedIds,
                            selectionMode = state.selectionMode,
                            onTap = { index ->
                                if (state.selectionMode) {
                                    val id = entryImageId(state.entries, index)
                                    if (id != null) viewModel.toggleSelected(id)
                                } else {
                                    viewModel.openFullscreen(index)
                                }
                            },
                            onLongPress = { index ->
                                val id = entryImageId(state.entries, index)
                                if (id != null) viewModel.startSelection(id)
                            },
                            onGroupCheckboxTap = { groupId -> viewModel.selectGroup(groupId) },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                    }
                }
            }
        }

        if (state.sortSheetVisible) {
            SortBottomSheet(
                currentSort = state.sortOption,
                sheetState = sheetState,
                onSelect = { viewModel.setSortOption(it) },
                onDismiss = { viewModel.toggleSortSheet(false) }
            )
        }

        state.fullscreenIndex?.let { idx ->
            FullscreenViewer(
                entries = state.entries,
                startIndex = idx,
                onDismiss = { viewModel.closeFullscreen() }
            )
        }

        if (state.slideshowActive) {
            SlideshowOverlay(
                entries = state.entries,
                currentIndex = state.slideshowIndex,
                interval = state.slideshowInterval,
                onTapAdvance = { viewModel.advanceSlideshow() },
                onIntervalSelected = { viewModel.setSlideshowInterval(it) },
                onStop = { viewModel.toggleSlideshow() }
            )
        }
    }
}

private fun entryImageId(entries: List<com.hazuki.imageorganizer.data.DisplayEntry>, index: Int): Long? {
    if (index !in entries.indices) return null
    return when (val e = entries[index]) {
        is com.hazuki.imageorganizer.data.DisplayEntry.Single -> e.image.id
        is com.hazuki.imageorganizer.data.DisplayEntry.Grouped -> e.image.id
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

