package com.hazuki.imageorganizer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
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
import com.hazuki.imageorganizer.ui.components.OrganizerTopBar
import com.hazuki.imageorganizer.ui.components.SlideshowOverlay
import com.hazuki.imageorganizer.ui.components.SortBottomSheet
import com.hazuki.imageorganizer.ui.theme.WashiBackground
import com.hazuki.imageorganizer.viewmodel.ImageOrganizerViewModel
import com.hazuki.imageorganizer.viewmodel.ScreenMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

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
            try {
                // =========================================================================
                // 【永続権限の取得】
                // 読み取り（READ）と書き込み・変更（WRITE）の両方の権限を永続化します。
                // これにより、アプリを再起動したり、サブフォルダ内のファイルをリネーム・移動したりする際も
                // 権限確認が再要求されるのを防ぎます。
                // =========================================================================
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 一部のストレージ提供元が永続化に非対応の場合でも、現在のセッションでの操作を継続
            }
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(
            containerColor = Color.Black,
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
                    focusedFileInfo = state.focusedFileInfo,
                    // 下層フォルダ読み込み状態とトグル操作
                    includeSubFolders = state.includeSubFolders,
                    onToggleIncludeSubFolders = { viewModel.toggleIncludeSubFolders() },
                    hashMatchEnabled = state.hashMatchEnabled,
                    onToggleHashMatch = { viewModel.toggleHashMatch(it) },
                    hashMatchThreshold = state.hashMatchThreshold,
                    onHashMatchThresholdChange = { viewModel.setHashMatchThreshold(it) },
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
                    },
                    // ---- ソート機能（直下プルダウンメニューから即時選択） ----
                    sortOption = state.sortOption,
                    onSortOptionSelected = { selectedSort ->
                        viewModel.setSortOption(selectedSort)
                    },
                    displayLabel = state.currentSelectedLabel,
                    isGroupComparisonMode = state.isGroupComparisonMode,
                    isSubFolderGroupMode = state.isSubFolderGroupMode, // 【※2】下層フォルダ移動中グループ表示フラグ
                    // 【カテゴリーラベルタップによる選択モードON/OFF切り替え】
                    onLabelSelectClick = { viewModel.toggleSelectionMode() },
                    // 【カテゴリーラベル長押しによるグループ比較解除・ハッシュ一覧復帰】
                    onLabelSelectLongClick = { viewModel.handleLabelLongClick() },
                    // 【ラベル選択ダイアログ実装】
                    // タグアイコン（Label）をタップでドロップダウンメニュー表示
                    // ・通常タップ: ViewModel に通知して現在選択中ラベルを更新
                    // ・長押し: 現在のフォルダ直下にそのラベルフォルダが存在すれば即座に移動
                    onLabelSelected = { selectedLabel ->
                        viewModel.setCurrentSelectedLabel(selectedLabel)
                    },
                    onLabelSubFolderNavigate = { targetLabel ->
                        viewModel.navigateToSubFolderIfExists(targetLabel)
                    },
                    // ---- フォルダ階層移動（親フォルダへ戻る「..⤴」機能） ----
                    canNavigateUp = state.canNavigateUp,
                    onNavigateUp = {
                        // ラベル選択プルダウンの「..⤴」ボタンタップ時、直前の親フォルダへ戻る
                        viewModel.navigateUpFolder()
                    },
                    // ---- 選択モード・リネーム機能 ----
                    selectedCount = state.selectedCount,
                    currentLabel = state.currentSelectedLabel,
                    labels = state.labels,
                    isSelectionMode = state.isSelectionMode,
                    // 【〇枚選択ボタン長押し】選択画像のみ全解除（選択モードは維持）
                    onClearSelection = { viewModel.clearSelectionOnly() },
                    // ---- しおり機能 ----
                    currentJumpIndex = state.currentJumpIndex,
                    comparisonBookmarkCount = state.comparisonBookmarkIds.size,
                    onJumpToSelected = { viewModel.jumpToNextSelected() },
                    onJumpToSelectedLongClick = { viewModel.jumpToFirstSelected() },
                    onRenameMove = {
                        // 【連番→📁[ラベル]】選択された画像を「ラベル」フォルダへ連番移動
                        // 例:「01 犬」が選ばれていれば「01犬」フォルダを作成して「01犬_00_00.jpg」のように連番移動
                        viewModel.executeRenameMove(state.currentSelectedLabel)
                    },
                    onRenameOnly = {
                        // 【Rename→[ラベル]連番】移動はせず、現在のフォルダ内で連番リネーム
                        // 例:「01犬_00_00.jpg」のように同じフォルダ内で名前を変更
                        viewModel.executeRenameOnly(state.currentSelectedLabel)
                    },
                    // 【※3】親フォルダへ移動（安全第一のCopy-then-Delete方式で実行）
                    onMoveToParentRequested = {
                        viewModel.executeMoveSelectedToParent()
                    },
                    onDeleteConfirmed = {
                        // 【削除】選択された画像を削除（確認ダイアログで「削除する」が押された後に実行）
                        viewModel.executeDeleteSelected()
                    },
                    onDefragGroupNumbers = {
                        // 【[ラベル] GP番号整理】フォルダ内の対象ファイルのグループ番号を01から連続するように整理
                        viewModel.executeDefragGroupNumbers(state.currentSelectedLabel)
                    },
                    onRenameGroupLabel = {
                        // 【[ラベル]📁一括変更】フォルダ内の対象ファイル名のラベルを一括置換
                        viewModel.executeRenameGroupLabel(state.currentSelectedLabel)
                    }
                )
                }
            },
            // ---- ボトムバー廃止 ----
            // (トップバーのメニューに統合されたため)
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (state.screenMode) {
                        ScreenMode.GALLERY -> {
                            if (state.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            } else if (state.totalImageCount == 0) {
                                // フォルダ内の総画像数が0枚（本当に空のフォルダ）の場合のみ、
                                // 初期画面として「画像が見つかりませんでした（フォルダを選択する）」を表示
                                EmptyFolderMessage(
                                    onOpenFolder = { folderPickerLauncher.launch(null) },
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else if (state.entries.isEmpty()) {
                                // フォルダ内に画像はあるが、検索・ソート等の条件で一時的に表示件数が0件になった場合は、
                                // 作業が中断されないよう「フォルダを選択する」ボタンは出さず、シンプルな案内文のみを表示
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "該当する画像がありません",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = FujiPrimaryDark.copy(alpha = 0.7f)
                                    )
                                }
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
                                        val item = state.entries.getOrNull(index)
                                        val id = item?.id
                                        if (id != null) viewModel.setFocusedImage(id)
                                        if (state.selectionMode) {
                                            if (id != null) viewModel.toggleSelected(id)
                                        } else if (state.sortOption == com.hazuki.imageorganizer.data.SortOption.GROUP_CATALOG_DESC && item != null) {
                                            // 【カタログ表示時のタップ: パターンA】そのグループの画像だけを全画面ビューワーで閲覧
                                            viewModel.openCatalogGroupFullscreen(item)
                                        } else {
                                            viewModel.openFullscreen(index)
                                        }
                                    },
                                    onLongPress = { index ->
                                        val id = state.entries.getOrNull(index)?.id
                                        if (id != null) {
                                            viewModel.setFocusedImage(id)
                                            viewModel.handleLongPress(id)
                                        }
                                    },
                                    // 拡張選択モード中、または「🏷️ グループ連番（枠色別）↓」ソート中は、計算された枠線色(A〜Z)を適用してグループを見分けやすくする
                                    groupedImageIds = if (state.extensionSelectionActive || state.sortOption == com.hazuki.imageorganizer.data.SortOption.GROUP_SEQ_ASC || state.sortOption == com.hazuki.imageorganizer.data.SortOption.MISMATCHED_GROUP_SEQ_ASC) {
                                        state.extensionGroupColors
                                    } else {
                                        state.groupedImageIds
                                    },
                                    dimmedIds = dimmedIds,
                                    // 【起点フォルダ名】ルート直下画像（○）と下層フォルダ画像（📁）を見分けるために渡す
                                    // パス形式(例: "Download/未整理")の場合でも末尾のフォルダ名("未整理")を抽出
                                    currentFolderName = state.currentFolderLabel.substringAfterLast('/'),
                                    // 【カタログ表示用】画像ごとのグループ総枚数を渡してバッジ表示
                                    catalogGroupCounts = state.catalogGroupCounts,
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

                    // ---- ハッシュ値計算中の進捗オーバーレイ（読み込みパーセント・枚数表示） ----
                    if (state.hashProgressText != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                            shape = RoundedCornerShape(20.dp),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding() + 8.dp)
                                .padding(horizontal = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.5.dp,
                                    color = FujiPrimary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = state.hashProgressText ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FujiPrimaryDark,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- GP番号整理（デフラグリナンバー）実行中の進捗ダイアログ ＆ スリープ防止 ----
        val isDefragging = state.defragProgressText != null
        // 整理中はスマホの画面自動消灯（スリープ）を防止
        LocalView.current.keepScreenOn = isDefragging

        if (isDefragging) {
            AlertDialog(
                onDismissRequest = { /* 処理中は画面外タップで閉じないよう保護 */ },
                confirmButton = {}, // 処理中につきボタンなし
                title = {
                    Text(
                        text = "グループ番号を整理中...",
                        fontWeight = FontWeight.Bold,
                        color = FujiPrimaryDark
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(
                            text = state.defragProgressText ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FujiPrimaryDark,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        val progress = state.defragProgressRatio ?: 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = FujiPrimary,
                            trackColor = FujiPrimary.copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "※安全に2段階リネームを行っています。完了するまでアプリを閉じずにお待ちください。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            )
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
            // カタロググループ閲覧時はそのグループ画像リスト、グループ内画面ではそのグループの画像リスト、それ以外は通常の一覧を対象にする
            val fullscreenSource = state.fullscreenCustomEntries
                ?: if (state.screenMode == ScreenMode.GROUP_DETAIL) state.groupDetailEntries else state.entries
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
                paused = state.slideshowPaused,
                onPauseToggle = { viewModel.toggleSlideshowPause() },
                onPageSelected = { viewModel.updateSlideshowIndex(it) },
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
