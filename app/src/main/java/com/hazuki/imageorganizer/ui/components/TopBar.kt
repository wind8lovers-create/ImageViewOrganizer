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
import androidx.compose.material.icons.filled.Label
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
    onLabelSelectClick: () -> Unit = {},
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
                        SortOption.values().forEach { option ->
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
                // -ラベル名- （独立したタップ可能ボタン：将来の機能保留用）
                val labelText = if (displayLabel.isNotBlank()) "-$displayLabel-" else "-未選択-"
                Text(
                    text = labelText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onLabelSelectClick() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )

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
                    // ラベルが読み込まれていない場合は、ボタンのみ表示（非活性）
                    TooltipIconButton(
                        icon = Icons.Default.Label,
                        tooltip = "ラベル選択（未読込）",
                        onClick = {},
                        contentDescription = "ラベル選択",
                        isEnabled = false
                    )
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
                // しおり（「選択へ」機能）
                // 画像が1枚以上選択されていれば有効になり、タップで選択中の画像へ順次スクロール
                val hasSelection = selectedCount > 0
                val jumpTooltip = if (hasSelection) {
                    if (currentJumpIndex != null) "次の選択へ (${currentJumpIndex}/${selectedCount})" else "選択へジャンプ (全${selectedCount}枚)"
                } else {
                    "選択がありません"
                }
                TooltipIconButton(
                    icon = Icons.Default.Bookmark,
                    tooltip = jumpTooltip,
                    onClick = onJumpToSelected,
                    onLongClick = onJumpToSelectedLongClick,
                    isEnabled = hasSelection,
                    contentDescription = "しおり（選択へジャンプ）"
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
                // 下層フォルダ読み込み状態と切り替えコールバックを伝達
                includeSubFolders = includeSubFolders,
                onToggleIncludeSubFolders = onToggleIncludeSubFolders,
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
 * 拡張選択 ON 時に表示される詳細フィルターパネル
 * 1行目: 彩度スライダー ＋ 明度スライダー ＋ プリセットA/B/C切り替え
 * 2行目: ハッシュ値スイッチ ＋ 縦横比スイッチ ＋ RGB5色スライダー ＋ 一致件数表示
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
    // 下層フォルダも含めて読み込むかどうかのフラグ（デフォルト: false = 直下のみ）
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
    onStyleMatchThresholdChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // 1列目: 彩度・明度・プリセット（横スクロール可能）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "彩度",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = saturationTolerance,
                onValueChange = onSaturationChange,
                valueRange = 0f..0.35f,
                modifier = Modifier
                    .width(180.dp) // さらに120%拡大（150dp ➔ 180dp）で微調整をより快適に
                    .padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(thumbColor = FujiPrimaryDark, activeTrackColor = FujiPrimary)
            )
            Text(
                text = "${(saturationTolerance * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark
            )

            Text(
                text = "明度",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp)
            )
            Slider(
                value = brightnessTolerance,
                onValueChange = onBrightnessChange,
                valueRange = 0f..0.35f,
                modifier = Modifier
                    .width(180.dp) // さらに120%拡大（150dp ➔ 180dp）で微調整をより快適に
                    .padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(thumbColor = FujiPrimaryDark, activeTrackColor = FujiPrimary)
            )
            Text(
                text = "${(brightnessTolerance * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark
            )

            // ---- プリセット切り替えボタン（SET:0 ➔ SET:A ➔ SET:B ➔ SET:C） ----
            FilterChip(
                selected = colorPresetStep != 0,
                onClick = onCyclePreset,
                label = { Text("SET:${presetStepLabel(colorPresetStep)}") },
                modifier = Modifier.padding(start = 12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FujiPrimary,
                    selectedLabelColor = Color.White
                )
            )

            // ---- 下層フォルダ読み込み切り替えボタン（下層📁:OFF / 下層📁:ON） ----
            // デフォルトはOFF（直下のみ）。タップしてONにすると下層フォルダも含めて再読込
            FilterChip(
                selected = includeSubFolders,
                onClick = onToggleIncludeSubFolders,
                label = { Text(if (includeSubFolders) "下層📁:ON" else "下層📁:OFF") },
                modifier = Modifier.padding(start = 8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FujiPrimary,
                    selectedLabelColor = Color.White
                )
            )
        }

        // 2列目: ハッシュ値（スイッチ＋180dpスライダー）・RGB5色・一致件数（横スクロール可能）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
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

            // ---- ハッシュ値 閾値スライダー（1〜17、108dpの120% = 130dp幅） ----
            // スイッチがONのときだけ操作可能。数値が小さいほど厳密一致、大きいほど大まか一致
            Slider(
                value = hashMatchThreshold.toFloat(),
                onValueChange = { onHashMatchThresholdChange(it.toInt().coerceIn(1, 17)) },
                valueRange = 1f..17f,
                enabled = hashMatchEnabled,
                modifier = Modifier
                    .width(130.dp) // 現在の108dpから120%（約130dp）に拡大し、指での操作性を向上
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
                    .width(180.dp) // さらに120%拡大（150dp ➔ 180dp）で微調整をより快適に
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
