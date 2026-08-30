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
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
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
    saturationTolerance: Float,
    onSaturationChange: (Float) -> Unit,
    brightnessTolerance: Float,
    onBrightnessChange: (Float) -> Unit,
    colorPresetStep: Int,
    onCyclePreset: () -> Unit,
    hashMatchEnabled: Boolean,
    onToggleHashMatch: (Boolean) -> Unit,
    aspectRatioOnly: Boolean,
    onToggleAspectRatioOnly: (Boolean) -> Unit,
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
    onSortClick: () -> Unit,

    // ---- ラベル関連（新規） ----
    displayLabel: String = "",
    onLabelSelectClick: () -> Unit = {},

    // ---- 選択モード・リネーム機能 ----
    selectedCount: Int = 0,
    currentLabel: String = "",
    labels: List<String> = emptyList(),
    isSelectionMode: Boolean = false,
    onClearSelection: () -> Unit = {},
    onRenameMove: () -> Unit = {},
    onRenameOnly: () -> Unit = {},
    onCopyRequested: () -> Unit = {},
    onMoveRequested: () -> Unit = {},
    onDeleteConfirmed: () -> Unit = {},
    onRenameGroupLabel: () -> Unit = {},

    // ---- しおり機能（「選択へ」）----
    currentJumpIndex: Int? = null,
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

        // トップバーのメインコンテンツ
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FujiPrimary)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // =====================================
            // 【左側】フォルダ名 + 操作アイコン
            // =====================================
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // フォルダ名（テキスト表示）
                Text(
                    text = folderLabel.take(12) + if (folderLabel.length > 12) "..." else "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
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

                // 🕐 フォルダ履歴
                TooltipIconButton(
                    icon = Icons.Default.History,
                    tooltip = "フォルダ履歴",
                    onClick = { /* TODO: 履歴メニューを開く */ },
                    contentDescription = "フォルダ履歴"
                )

                // ≡ ソート
                TooltipIconButton(
                    icon = Icons.Default.Sort,
                    tooltip = "ソート: ${sortOption.label}",
                    onClick = onSortClick,
                    contentDescription = "ソート"
                )

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

            // =====================================
            // 【中央】ラベル名 + 選択枚数 + ラベル選択
            // =====================================
            Row(
                modifier = Modifier
                    .weight(1.5f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // -ラベル名- （テキスト表示のボタン、機能保留）
                if (displayLabel.isNotEmpty()) {
                    Text(
                        text = "-$displayLabel-",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                // 〇枚選択（テキスト表示、選択モード時のみ、プルダウンメニュー付き）
                if (isSelectionMode) {
                    SelectionMenuButton(
                        selectedCount = selectedCount,
                        currentLabel = currentLabel,
                        onClearSelection = onClearSelection,
                        onRenameMove = onRenameMove,
                        onRenameOnly = onRenameOnly,
                        onCopyRequested = onCopyRequested,
                        onMoveRequested = onMoveRequested,
                        onDeleteConfirmed = onDeleteConfirmed,
                        onRenameGroupLabel = onRenameGroupLabel
                    )
                }

                // □▷ ラベル名選択ボタン
                TooltipIconButton(
                    icon = null,
                    iconText = "□▷",
                    tooltip = "ラベル選択",
                    onClick = onLabelSelectClick,
                    contentDescription = "ラベル選択"
                )
            }

            // =====================================
            // 【右側】しおり + スライドショー + グリッド
            // =====================================
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🔖 しおり（「選択へ」機能）
                TooltipIconButton(
                    icon = null,
                    iconText = "🔖",
                    tooltip = if (currentJumpIndex != null) "次の選択へ" else "選択がありません",
                    onClick = onJumpToSelected,
                    onLongClick = onJumpToSelectedLongClick,
                    isEnabled = currentJumpIndex != null,
                    contentDescription = "しおり"
                )

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

        // 拡張選択パネル（拡張選択 ON 時のみ表示）
        if (extensionSelectionActive) {
            ExtensionSelectionPanel(
                matchedCount = matchedCount,
                saturationTolerance = saturationTolerance,
                onSaturationChange = onSaturationChange,
                brightnessTolerance = brightnessTolerance,
                onBrightnessChange = onBrightnessChange,
                colorPresetStep = colorPresetStep,
                onCyclePreset = onCyclePreset,
                hashMatchEnabled = hashMatchEnabled,
                onToggleHashMatch = onToggleHashMatch,
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun TooltipIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconText: String? = null,
    tooltip: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isEnabled: Boolean = true,
    isActive: Boolean = false,
    contentDescription: String = ""
) {
    val tooltipState = rememberTooltipState()

    TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            Text(
                text = tooltip,
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(4.dp)
            )
        },
        state = tooltipState
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
    onClearSelection: () -> Unit,
    onRenameMove: () -> Unit,
    onRenameOnly: () -> Unit,
    onCopyRequested: () -> Unit,
    onMoveRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
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
            DropdownMenuItem(
                text = { Text("連番→📁$currentLabel") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameMove"
                }
            )
            DropdownMenuItem(
                text = { Text("Rename→${currentLabel}連番") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameOnly"
                }
            )
            DropdownMenuItem(
                text = { Text("コピー") },
                onClick = {
                    showMenu = false
                    onCopyRequested()
                }
            )
            DropdownMenuItem(
                text = { Text("移動") },
                onClick = {
                    showMenu = false
                    onMoveRequested()
                }
            )
            DropdownMenuItem(
                text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    pendingAction = "delete"
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
                else -> return@let
            }

            AlertDialog(
                onDismissRequest = { pendingAction = null },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingAction = null
                        when (action) {
                            "renameMove" -> onRenameMove()
                            "renameOnly" -> onRenameOnly()
                            "delete" -> onDeleteConfirmed()
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
 * 拡張選択 ON 時に表示されるフィルタパネル
 */
@Composable
fun ExtensionSelectionPanel(
    matchedCount: Int,
    saturationTolerance: Float,
    onSaturationChange: (Float) -> Unit,
    brightnessTolerance: Float,
    onBrightnessChange: (Float) -> Unit,
    colorPresetStep: Int,
    onCyclePreset: () -> Unit,
    hashMatchEnabled: Boolean,
    onToggleHashMatch: (Boolean) -> Unit,
    aspectRatioOnly: Boolean,
    onToggleAspectRatioOnly: (Boolean) -> Unit,
    styleMatchThreshold: Float,
    onStyleMatchThresholdChange: (Float) -> Unit
) {
    // 拡張選択パネル実装（既存コードを転用可能）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Gray)
            .padding(8.dp)
    ) {
        Text(
            text = "一致: $matchedCount 件",
            color = Color.White,
            fontSize = 12.sp
        )
    }
}
