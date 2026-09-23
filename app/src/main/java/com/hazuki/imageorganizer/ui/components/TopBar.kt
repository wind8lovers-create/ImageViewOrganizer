package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

/**
 * =====================================================================
 * 【新しいトップバー】
 * レイアウト：左側アイコン | 中央テキスト | 右側アイコン
 *
 * 【左側】
 * フォルダ名 | 📁 | 📦 | 🕐 | ≡(ソート) | ★(拡張選択)
 *
 * 【中央】
 * -ラベル名- | 〇枚選択(選択モード時) | □▷(ラベル選択)
 *
 * 【右側】
 * 🔖(しおり) | ▶(スライドショー) | 2.0s(時間) | ⊞(グリッド)
 * =====================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrganizerTopBar(
    // ---- 基本情報 ----
    folderLabel: String,
    isZipMode: Boolean,
    folderDetailLabel: String = "",
    imageCount: Int,
    folderTotalCount: Int = 0,
    isLoading: Boolean,
    isStreaming: Boolean,
    isComparing: Boolean = false,

    // ---- フォルダ操作 ----
    onOpenFolder: () -> Unit,
    onOpenZip: () -> Unit,
    recentEntries: List<RecentEntry>,
    onSelectRecent: (RecentEntry) -> Unit,
    onSelectDefaultFolder: () -> Unit = {},

    // ---- 拡張選択フィルター ----
    extensionSelectionActive: Boolean,
    onToggleExtensionSelection: () -> Unit,
    matchedCount: Int,
    focusedFileInfo: com.hazuki.imageorganizer.viewmodel.FocusedFileInfo? = null,
    saturationTolerance: Float = 0f,
    onSaturationChange: (Float) -> Unit = {},
    brightnessTolerance: Float = 0f,
    onBrightnessChange: (Float) -> Unit = {},
    colorPresetStep: Int = 0,
    onCyclePreset: () -> Unit = {},
    // 下層フォルダも含めて読み込むかどうかのフラグ（デフォルト: false = 現在のフォルダ直下のみ読み込み）
    includeSubFolders: Boolean = false,
    // 下層フォルダ読み込みのON/OFF切り替えコールバック
    onToggleIncludeSubFolders: () -> Unit = {},
    hashMatchEnabled: Boolean,
    onToggleHashMatch: (Boolean) -> Unit,
    // ハッシュ値の許容閾値（1〜40、デフォルト10）。スライダーで調整可能
    hashMatchThreshold: Int = 10,
    onHashMatchThresholdChange: (Int) -> Unit = {},
    aspectRatioOnly: Boolean = false,
    onToggleAspectRatioOnly: (Boolean) -> Unit = {},
    styleMatchThreshold: Float,
    onStyleMatchThresholdChange: (Float) -> Unit,

    // ---- スライドショー・グリッド ----
    slideshowActive: Boolean,
    onToggleSlideshow: () -> Unit,
    thumbnailSize: ThumbnailSize,
    onToggleThumbnailSize: () -> Unit,

    // ---- 分類機能 ----
    isClassificationListMode: Boolean,
    selectionMode: Boolean,
    addModeActive: Boolean,
    onClassificationButtonClick: () -> Unit,

    // ---- ソート機能 ----
    sortOption: SortOption,
    onSortClick: () -> Unit = {},
    onSortOptionSelected: (SortOption) -> Unit = {},

    // ---- ラベル関連（新規） ----
    displayLabel: String = "",
    isGroupComparisonMode: Boolean = false, // 【※2＋α】グループ比較モード中かどうか
    isSubFolderGroupMode: Boolean = false,  // 【※2】下層フォルダ移動グループ表示中かどうか（赤字 #B60500）
    onLabelSelectClick: () -> Unit = {},
    onLabelSelectLongClick: () -> Unit = {}, // 【※2＋α】グループ比較からハッシュ一覧への復帰長押し
    onLabelSelected: (String) -> Unit = {},  // ラベル選択ダイアログからの長押し選択結果を受け取るコールバック
    onLabelSubFolderNavigate: (String) -> Unit = {}, // ラベル選択ダイアログからの通常タップ（サブフォルダ移動）を受け取るコールバック
    canNavigateUp: Boolean = false, // 上の階層へ戻れるかどうか（「..⤴」ボタンのグレーアウト判定）
    onNavigateUp: () -> Unit = {},  // 【「..⤴」タップ時】親フォルダへ戻るコールバック

    // ---- 選択モード・リネーム機能 ----
    selectedCount: Int = 0,
    currentLabel: String = "",
    labels: List<String> = emptyList(),
    isSelectionMode: Boolean = false,
    onClearSelection: () -> Unit = {},
    onRenameMove: () -> Unit = {},
    onRenameOnly: () -> Unit = {},
    // 【※3】従来の「コピー」は削除、「移動」は「親フォルダへ移動」に変更
    onMoveToParentRequested: () -> Unit = {},
    onDeleteConfirmed: () -> Unit = {},
    onDefragGroupNumbers: () -> Unit = {}, // 【新規】フォルダ内のグループ番号整理（デフラグリナンバー）
    onRenameGroupLabel: () -> Unit = {},

    // ---- しおり機能（「選択へ」）----
    currentJumpIndex: Int? = null,
    comparisonBookmarkCount: Int = 0, // 【※2＋α】記憶した類似画像枚数
    onJumpToSelected: () -> Unit = {},
    onJumpToSelectedLongClick: () -> Unit = {},

    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // システムバーの高さ分のスペースを作る
        Box(modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .windowInsetsTopHeight(WindowInsets.statusBars)
        )

        // フォルダ履歴メニューの開閉状態
        var showHistoryMenu by remember { mutableStateOf(false) }

        // 親フォルダパスを除去し、現在いるフォルダのフルネームを取得（例: "Download/未整理" ➔ "未整理"）
        val currentFolderName = folderLabel.substringAfterLast('/').ifBlank { folderLabel }

        // トップバーのメインコンテンツ（横スクロール可能で、端末幅に収まらないアイコンも快適にスライドして操作可能）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FujiPrimary)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // =================================================================
            // 【左側】フォルダ名 + フォルダ操作アイコン
            // =================================================================
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // フォルダ名（現在のフォルダ名フルネーム）
                Text(
                    text = currentFolderName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 4.dp)
                )

                // 📁 フォルダを開く
                TooltipIconButton(
                    icon = Icons.Default.Folder,
                    tooltip = "フォルダを開く",
                    onClick = onOpenFolder,
                    contentDescription = "フォルダを開く"
                )

                // 📦 書庫を開く
                TooltipIconButton(
                    icon = Icons.Default.FolderOpen,
                    tooltip = "書庫を開く",
                    onClick = onOpenZip,
                    contentDescription = "書庫を開く"
                )

                // 🕐 フォルダ履歴（ドロップダウンメニュー付き）
                Box {
                    TooltipIconButton(
                        icon = Icons.Default.History,
                        tooltip = "フォルダ履歴",
                        onClick = { showHistoryMenu = !showHistoryMenu },
                        contentDescription = "フォルダ履歴"
                    )
                    DropdownMenu(
                        expanded = showHistoryMenu,
                        onDismissRequest = { showHistoryMenu = false }
                    ) {
                        if (recentEntries.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("履歴はありません", color = Color.Gray) },
                                onClick = { showHistoryMenu = false }
                            )
                        } else {
                            recentEntries.forEach { entry ->
                                DropdownMenuItem(
                                    text = {
                                        val entryName = entry.label.substringAfterLast('/').ifBlank { entry.label }
                                        Text(text = entryName, maxLines = 1)
                                    },
                                    onClick = {
                                        showHistoryMenu = false
                                        onSelectRecent(entry)
                                    }
                                )
                            }
                        }
                    }
                }

                // ≡ ソート（直下プルダウンメニュー）
                var showSortMenu by remember { mutableStateOf(false) }

                Box {
                    TooltipIconButton(
                        icon = Icons.Default.Sort,
                        tooltip = "ソート: ${sortOption.label}",
                        onClick = { showSortMenu = true },
                        contentDescription = "ソート"
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        // ユーザー選択可能なソート項目（visibleValues）のみをメニューに表示
                        SortOption.visibleValues.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        color = if (option == sortOption) FujiPrimaryDark else FujiPrimaryDark.copy(alpha = 0.85f),
                                        fontWeight = if (option == sortOption) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    showSortMenu = false
                                    onSortOptionSelected(option)
                                }
                            )
                        }
                    }
                }

                // ★ 拡張選択（新規アイコン化）
                TooltipIconButton(
                    icon = null,
                    iconText = "★",
                    tooltip = if (extensionSelectionActive) "拡張選択: ON (${matchedCount}件)" else "拡張選択: OFF",
                    onClick = onToggleExtensionSelection,
                    contentDescription = "拡張選択",
                    isActive = extensionSelectionActive
                )
            }

            // 区切りスペース
            Spacer(modifier = Modifier.width(12.dp))

            // =================================================================
            // 【中央】ラベル名 + 選択枚数 + ラベル選択
            // =================================================================
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // -ラベル名- （タップで選択モードON/OFF、長押しでグループ比較解除）
                // 通常時: 白 / 選択モード中: ピンク（#FF67C6） / グループ比較中・★ハッシュ値一覧中: ライトグリーン（#4FFF94） / 下層フォルダ移動中: 赤（#B60500）
                val labelText = if (displayLabel.isNotBlank()) "-$displayLabel-" else "-未選択-"
                val labelColor = when {
                    isSubFolderGroupMode -> Color(0xFFB60500)  // 【※2】下層フォルダ移動中（はづきさんご指定の深赤色: #B60500）
                    isSelectionMode -> Color(0xFFFF67C6)       // 【※1】選択モード中（ピンク紫）
                    isGroupComparisonMode -> Color(0xFF4FFF94) // 【※2＋α】グループ比較表示中
                    extensionSelectionActive -> Color(0xFF4FFF94) // ★ハッシュ値一覧表示中（ライトグリーン）
                    else -> Color.White                        // 通常時
                }
                Text(
                    text = labelText,
                    color = labelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    // ★ハッシュ値一覧中（拡張選択中）は常にアンダーバーを表示して通常一覧と明確に区別
                    // 選択モード中（ピンク紫）でもアンダーバーが残るため、ハッシュ一覧内での選択中であることが一目でわかります
                    textDecoration = if (extensionSelectionActive) TextDecoration.Underline else null,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onLabelSelectClick() },
                            onLongClick = { onLabelSelectLongClick() }
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )

                // 〇枚選択（テキスト表示、選択モード時のみ、プルダウンメニュー付き）
                if (isSelectionMode) {
                    SelectionMenuButton(
                        selectedCount = selectedCount,
                        currentLabel = currentLabel,
                        canNavigateUp = canNavigateUp, // 下層フォルダにいるかどうかの判定を渡す
                        onClearSelection = onClearSelection,
                        onRenameMove = onRenameMove,
                        onRenameOnly = onRenameOnly,
                        onMoveToParent = onMoveToParentRequested,
                        onDeleteConfirmed = onDeleteConfirmed,
                        onDefragGroupNumbers = onDefragGroupNumbers,
                        onRenameGroupLabel = onRenameGroupLabel
                    )
                }

                // □▷ ラベル名選択ボタン＋ドロップダウンダイアログ
                // ラベル一覧（約23種類）を縦リスト表示
                // ラベルをタップすると即座に選択＆ダイアログ自動クローズ
                if (labels.isNotEmpty()) {
                    LabelSelectDialog(
                        labels = labels,
                        currentLabel = displayLabel,
                        onLabelSelected = onLabelSelected,
                        onLabelClick = onLabelSubFolderNavigate,
                        canNavigateUp = canNavigateUp,
                        onNavigateUp = onNavigateUp
                    )
                } else {
                    // ラベルが読み込まれていない場合は、ボタンのみ表示（非活性・薄いグレー）
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("[", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Icon(
                            imageVector = Icons.Default.Label,
                            contentDescription = "ラベル選択（未読込）",
                            tint = Color.Gray,
                            modifier = Modifier.padding(horizontal = 1.dp).size(16.dp)
                        )
                        Text("→📁]", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 区切りスペース
            Spacer(modifier = Modifier.width(12.dp))

            // =================================================================
            // 【右側】しおり + スライドショー + グリッド
            // =================================================================
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // しおり（「選択へ」機能） [ 🔖 3/5⤴ ]
                // 手動選択がある場合、またはグループ比較中で記憶画像がある場合に有効化
                val effectiveCount = if (selectedCount > 0) selectedCount else if (isGroupComparisonMode) comparisonBookmarkCount else 0
                val hasSelection = effectiveCount > 0
                val bookmarkColor = if (hasSelection) Color.White else Color.Gray.copy(alpha = 0.5f)

                // 巡回カウント表示文字列（未ジャンプ時は 0/N、ジャンプ中は 現在位置/N）
                // 選択が0枚の時は文字を出さず [ 🔖 ⤴ ] のみ表示
                val countText = if (hasSelection) {
                    val currentPos = currentJumpIndex ?: 0
                    "$currentPos/$effectiveCount"
                } else {
                    ""
                }

                Row(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = hasSelection,
                            onClick = onJumpToSelected,
                            onLongClick = onJumpToSelectedLongClick
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "[",
                        color = bookmarkColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "しおり（選択へジャンプ）",
                        tint = bookmarkColor,
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .size(16.dp)
                    )
                    Text(
                        text = "${countText}⤴]",
                        color = bookmarkColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ▶ スライドショー
                TooltipIconButton(
                    icon = Icons.Default.PlayArrow,
                    tooltip = if (slideshowActive) "スライドショー: 再生中" else "スライドショー",
                    onClick = onToggleSlideshow,
                    contentDescription = "スライドショー",
                    isActive = slideshowActive
                )

                // 2.0s スライドショー時間（テキスト表示）
                if (slideshowActive) {
                    Text(
                        text = "2.0s",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // ⊞ グリッド切り替え
                TooltipIconButton(
                    icon = Icons.Default.GridView,
                    tooltip = "グリッド: ${thumbnailSize.label}",
                    onClick = onToggleThumbnailSize,
                    contentDescription = "グリッド表示切り替え"
                )
            }
        }

        // =========================================================================
        // 【1行目：常時表示】ファイル情報・下層フォルダ切り替えバー
        // ★ボタンのON/OFFに関係なく、常に画面上部に表示してファイル情報確認や
        // 下層フォルダ読み込みの切り替えを行えるようにします。
        // =========================================================================
        FocusedFileInfoBar(
            includeSubFolders = includeSubFolders,
            onToggleIncludeSubFolders = onToggleIncludeSubFolders,
            focusedFileInfo = focusedFileInfo
        )

        // =========================================================================
        // 【2行目：折りたたみ表示】拡張選択フィルターパネル
        // ★ボタンがONのときだけ展開し、ハッシュ値やRGBなどの絞り込みスライダーを表示します。
        // =========================================================================
        if (extensionSelectionActive) {
            ExtensionFilterPanel(
                matchedCount = matchedCount,
                hashMatchEnabled = hashMatchEnabled,
                onToggleHashMatch = onToggleHashMatch,
                hashMatchThreshold = hashMatchThreshold,
                onHashMatchThresholdChange = onHashMatchThresholdChange,
                aspectRatioOnly = aspectRatioOnly,
                onToggleAspectRatioOnly = onToggleAspectRatioOnly,
                styleMatchThreshold = styleMatchThreshold,
                onStyleMatchThresholdChange = onStyleMatchThresholdChange
            )
        }
    }
}

/**
 * 【Tooltip付きアイコンボタン】
 * 説明テキストをホバーで表示するアイコンボタン
 */
@Composable
fun TooltipIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconText: String? = null,
    tooltip: String = "",  // 使用しない（互換性のために残す）
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isEnabled: Boolean = true,
    isActive: Boolean = false,
    contentDescription: String = ""
) {
    IconButton(
        onClick = onClick,
        enabled = isEnabled,
        modifier = Modifier.size(36.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) Color.Yellow else if (isEnabled) Color.White else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        } else if (iconText != null) {
            Text(
                text = iconText,
                color = if (isEnabled) Color.White else Color.Gray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 【選択メニューボタン】
 * 「〇枚選択」テキストをタップでプルダウンメニュー表示
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SelectionMenuButton(
    selectedCount: Int,
    currentLabel: String,
    canNavigateUp: Boolean = false, // 【※3】現在下層フォルダにいるかどうか（親へ移動の有効/無効判定に使用）
    onClearSelection: () -> Unit,
    onRenameMove: () -> Unit,
    onRenameOnly: () -> Unit,
    onMoveToParent: () -> Unit,      // 【※3】親フォルダへ移動するコールバック
    onDeleteConfirmed: () -> Unit,
    onDefragGroupNumbers: () -> Unit = {}, // 【新規】フォルダ内のグループ番号整理（デフラグリナンバー）
    onRenameGroupLabel: () -> Unit
) {
    Box {
        var showMenu by remember { mutableStateOf(false) }
        var pendingAction by remember { mutableStateOf<String?>(null) }

        // 「〇枚選択」テキストボタン
        Text(
            text = "${selectedCount}枚選択",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .combinedClickable(
                    onClick = { showMenu = !showMenu },
                    onLongClick = onClearSelection
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        // プルダウンメニュー
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            // ⓵ 連番を付与して該当ラベルのフォルダへ移動
            DropdownMenuItem(
                text = { Text("[連番] →📁$currentLabel") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameMove"
                }
            )
            // ⓶ 現在のフォルダ内で連番リネーム（フォルダ移動なし）
            DropdownMenuItem(
                text = { Text("リネーム →$currentLabel [連番]") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameOnly"
                }
            )
            // 【※3：📁親フォルダへ移動】
            // 下層フォルダ内にいて（canNavigateUp == true）、かつ1枚以上画像が選択されている場合のみ有効。
            // ルートフォルダにいる時などはグレーアウト（非活性）にして誤操作を防ぎます。
            val canMoveToParent = canNavigateUp && selectedCount > 0
            DropdownMenuItem(
                text = {
                    Text(
                        "📁親フォルダへ移動",
                        color = if (canMoveToParent) Color.Unspecified else Color.Gray.copy(alpha = 0.5f)
                    )
                },
                enabled = canMoveToParent,
                onClick = {
                    showMenu = false
                    pendingAction = "moveToParent"
                }
            )
            DropdownMenuItem(
                text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                // 削除文字の前にゴミ箱アイコンを表示
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    showMenu = false
                    pendingAction = "delete"
                }
            )
            // 【新規】グループ番号整理（デフラグリナンバー）
            DropdownMenuItem(
                text = { Text("[$currentLabel] GP番号整理") },
                onClick = {
                    showMenu = false
                    pendingAction = "defragGroupNumbers"
                }
            )
            DropdownMenuItem(
                text = { Text("[$currentLabel]📁一括変更") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameGroup"
                }
            )
        }

        // 確認ダイアログ
        pendingAction?.let { action ->
            val (title, message, confirmText) = when (action) {
                "renameMove" -> Triple(
                    "確認",
                    "${selectedCount}枚を連番リネームして「$currentLabel」フォルダに移動します。よろしいですか？",
                    "実行"
                )
                "renameOnly" -> Triple(
                    "確認",
                    "${selectedCount}枚を「${currentLabel}_連番」の形式にリネームします（移動はしません）。よろしいですか？",
                    "実行"
                )
                "moveToParent" -> Triple(
                    "親フォルダへ移動の確認",
                    "${selectedCount}枚の画像を親フォルダへ移動します。よろしいですか？",
                    "移動する"
                )
                "delete" -> Triple(
                    "削除の確認",
                    "${selectedCount}枚を削除します。この操作は元に戻せません。よろしいですか？",
                    "削除する"
                )
                "renameGroup" -> Triple(
                    "確認",
                    "フォルダ内の対象ファイルとフォルダ名を「$currentLabel」に一括変更します。よろしいですか？",
                    "実行"
                )
                "defragGroupNumbers" -> Triple(
                    "グループ番号整理の確認",
                    "フォルダ内の対象ファイルのグループ番号を「01」から連続するように整理し、ラベル名を「$currentLabel」に統一します。よろしいですか？",
                    "実行"
                )
                else -> return@let
            }

            AlertDialog(
                onDismissRequest = { pendingAction = null },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        val action = pendingAction
                        pendingAction = null
                        when (action) {
                            "renameMove" -> onRenameMove()
                            "renameOnly" -> onRenameOnly()
                            "moveToParent" -> onMoveToParent()
                            "delete" -> onDeleteConfirmed()
                            "defragGroupNumbers" -> onDefragGroupNumbers()
                            "renameGroup" -> onRenameGroupLabel()
                        }
                    }) {
                        Text(confirmText)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingAction = null }) {
                        Text("キャンセル")
                    }
                }
            )
        }
    }
}

/**
 * 【拡張選択パネル】
 * 拡張選択 ON 時に表示される詳細フィルターパネル
 * 1行目: 下層📁切り替えボタン ＋ フォーカスファイル情報（[📁:〇〇〇] [🖼️:●●●] [🏷️:○/◎枚目]）
 * 2行目: ハッシュ値スイッチ ＋ 縦横比スイッチ ＋ RGB5色スライダー ＋ 一致件数表示
 */
/**
 * =========================================================================
 * 【1行目：常時表示】ファイル情報・下層フォルダ切り替えバー
 * =========================================================================
 * 画面上部に常に表示されるバーです。
 * - 左端：「下層📁:OFF / ON」切り替えボタン（タップで配下全フォルダ読み込み）
 * - 右側：現在フォーカス（タップ選択）されている画像のフォルダ名・ファイル名・グループ枚数情報
 * を横スクロールで一覧表示します。
 */
@Composable
fun FocusedFileInfoBar(
    // 下層フォルダも含めて読み込むかどうかのフラグ（OFF: 現在のフォルダ直下のみ / ON: 配下すべて）
    includeSubFolders: Boolean = false,
    // 下層フォルダ読み込み切り替えのタップ時処理
    onToggleIncludeSubFolders: () -> Unit = {},
    // 現在選択中のファイル情報（フォルダ名、ファイル名、枚数カウント等）
    focusedFileInfo: com.hazuki.imageorganizer.viewmodel.FocusedFileInfo? = null
) {
    // 背景色と余白を設定（トップバーと統一感のある半透明サーフェス色）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        // 横スクロール可能な行レイアウト
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- ① 一番左: 下層フォルダ読み込み切り替えボタン（下層📁:OFF / 下層📁:ON） ----
            // タップするだけで直下のみ表示とサブフォルダ一括表示を瞬時に切り替えられます
            FilterChip(
                selected = includeSubFolders,
                onClick = onToggleIncludeSubFolders,
                label = { Text(if (includeSubFolders) "下層📁:ON" else "下層📁:OFF") },
                modifier = Modifier.padding(end = 10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FujiPrimary,
                    selectedLabelColor = Color.White
                )
            )

            // ---- ② その右: フォーカスしているファイルの表示欄 ----
            if (focusedFileInfo != null) {
                // 【所属フォルダ名】 [📁：〇〇〇]
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = FujiPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = "フォルダ名",
                            tint = FujiPrimaryDark,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = focusedFileInfo.folderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = FujiPrimaryDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 【ファイル名】 [🖼️：●●●]
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = FujiPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = "ファイル名",
                            tint = FujiPrimaryDark,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = focusedFileInfo.fileName,
                            style = MaterialTheme.typography.labelMedium,
                            color = FujiPrimaryDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 【枚数情報】 [🏷️：○/◎枚目]
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = FujiPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = "枚数情報",
                            tint = FujiPrimaryDark,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = focusedFileInfo.countText,
                            style = MaterialTheme.typography.labelMedium,
                            color = FujiPrimaryDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // まだタップ・フォーカスされた画像がない場合の案内表示
                Text(
                    text = "画像をタップするとファイル情報を表示します",
                    style = MaterialTheme.typography.labelSmall,
                    color = FujiPrimaryDark.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/**
 * =========================================================================
 * 【2行目：折りたたみ表示】拡張選択フィルターパネル
 * =========================================================================
 * ★ボタンがONのときだけ表示されるコントロールバーです。
 * - ハッシュ値スイッチ＆スライダー（＋／−ボタン付き）
 * - RGB5色スライダー
 * - 一致件数の表示
 */
@Composable
fun ExtensionFilterPanel(
    matchedCount: Int,
    hashMatchEnabled: Boolean,
    onToggleHashMatch: (Boolean) -> Unit,
    // ハッシュ値の許容閾値（1〜17）。スライダーや＋／−ボタンで調整可能
    hashMatchThreshold: Int = 10,
    onHashMatchThresholdChange: (Int) -> Unit = {},
    aspectRatioOnly: Boolean = false,
    onToggleAspectRatioOnly: (Boolean) -> Unit = {},
    styleMatchThreshold: Float,
    onStyleMatchThresholdChange: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp, top = 0.dp)
    ) {
        // ハッシュ値（スイッチ＋130dpスライダー）・RGB5色・一致件数（横スクロール可能）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- ハッシュ値 ON/OFF スイッチ ----
            Text(
                text = "ハッシュ値",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark,
                fontWeight = FontWeight.Bold
            )
            Switch(
                checked = hashMatchEnabled,
                onCheckedChange = onToggleHashMatch,
                modifier = Modifier.padding(start = 4.dp, end = 6.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FujiPrimary,
                    checkedTrackColor = FujiPrimaryDark.copy(alpha = 0.5f)
                )
            )

            // ---- ハッシュ値 マイナス［−］ボタン（-1 微調整） ----
            IconButton(
                onClick = { onHashMatchThresholdChange((hashMatchThreshold - 1).coerceIn(1, 17)) },
                enabled = hashMatchEnabled && hashMatchThreshold > 1,
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    text = "−",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hashMatchEnabled && hashMatchThreshold > 1) FujiPrimaryDark else FujiPrimaryDark.copy(alpha = 0.3f)
                )
            }

            // ---- ハッシュ値 閾値スライダー（1〜17、130dp幅） ----
            // スイッチがONのときだけ操作可能。数値が小さいほど厳密一致、大きいほど大まか一致
            Slider(
                value = hashMatchThreshold.toFloat(),
                onValueChange = { onHashMatchThresholdChange(it.toInt().coerceIn(1, 17)) },
                valueRange = 1f..17f,
                enabled = hashMatchEnabled,
                modifier = Modifier
                    .width(130.dp)
                    .padding(horizontal = 2.dp),
                colors = SliderDefaults.colors(thumbColor = FujiPrimaryDark, activeTrackColor = FujiPrimary)
            )

            // ---- ハッシュ値 プラス［＋］ボタン（+1 微調整） ----
            IconButton(
                onClick = { onHashMatchThresholdChange((hashMatchThreshold + 1).coerceIn(1, 17)) },
                enabled = hashMatchEnabled && hashMatchThreshold < 17,
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    text = "＋",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hashMatchEnabled && hashMatchThreshold < 17) FujiPrimaryDark else FujiPrimaryDark.copy(alpha = 0.3f)
                )
            }

            Text(
                text = "$hashMatchThreshold",
                style = MaterialTheme.typography.labelSmall,
                color = if (hashMatchEnabled) FujiPrimaryDark else FujiPrimaryDark.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 2.dp, end = 12.dp)
            )

            // ---- RGB5色スライダー（180dp幅） ----
            Text(
                text = "RGB5色",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = mapThresholdToSlider(styleMatchThreshold),
                onValueChange = { onStyleMatchThresholdChange(mapSliderToThreshold(it)) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .width(180.dp)
                    .padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(thumbColor = FujiPrimaryDark, activeTrackColor = FujiPrimary)
            )
            Text(
                text = String.format(java.util.Locale.US, "%.0f%%", styleMatchThreshold),
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark
            )

            // ---- 一致件数表示 ----
            Text(
                text = "一致: ${matchedCount}件",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/** プリセットステップのラベル表記（0 / A / B / C） */
private fun presetStepLabel(step: Int): String = when (step) {
    1 -> "A"
    2 -> "B"
    3 -> "C"
    else -> "0"
}

/** 非線形スライダー変換（スライダー位置 ➔ 閾値%） */
private fun mapSliderToThreshold(sliderValue: Float): Float {
    return when {
        sliderValue <= 0f -> 0f
        sliderValue <= 0.1f -> lerp(0f, 50f, sliderValue / 0.1f)
        sliderValue <= 0.2f -> lerp(50f, 60f, (sliderValue - 0.1f) / 0.1f)
        sliderValue <= 0.5f -> lerp(60f, 70f, (sliderValue - 0.2f) / 0.3f)
        sliderValue <= 0.6f -> lerp(70f, 80f, (sliderValue - 0.5f) / 0.1f)
        else -> lerp(80f, 100f, (sliderValue - 0.6f) / 0.4f)
    }
}

/** mapSliderToThreshold の逆変換（閾値% ➔ スライダー位置） */
private fun mapThresholdToSlider(threshold: Float): Float {
    return when {
        threshold <= 0f -> 0f
        threshold <= 50f -> (threshold / 50f) * 0.1f
        threshold <= 60f -> 0.1f + ((threshold - 50f) / 10f) * 0.1f
        threshold <= 70f -> 0.2f + ((threshold - 60f) / 10f) * 0.3f
        threshold <= 80f -> 0.5f + ((threshold - 70f) / 10f) * 0.1f
        else -> 0.6f + ((threshold - 80f) / 20f) * 0.4f
    }
}

/** 線形補間(Linear Interpolation) */
private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}
