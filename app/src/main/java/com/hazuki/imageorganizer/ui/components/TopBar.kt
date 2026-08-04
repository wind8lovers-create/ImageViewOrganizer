package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.RecentEntryType
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurfaceVariant
import com.hazuki.imageorganizer.util.ColorGroupPreset
import com.hazuki.imageorganizer.util.ImageGrouping

@Composable
fun OrganizerTopBar(
    folderLabel: String,
    imageCount: Int,
    folderTotalCount: Int,
    isLoading: Boolean,
    isStreaming: Boolean,
    isGrouping: Boolean,
    onOpenFolder: () -> Unit,
    onOpenZip: () -> Unit,
    recentEntries: List<RecentEntry>,
    onSelectRecent: (RecentEntry) -> Unit,
    onSelectDefaultFolder: () -> Unit,
    sameImageOnly: Boolean,
    onToggleSameImageOnly: () -> Unit,
    groupThreshold: Int,
    onThresholdChange: (Int) -> Unit,
    colorPreset: ColorGroupPreset?,
    onColorPresetSelected: (ColorGroupPreset) -> Unit,
    groupCount: Int,
    hideSinglesWhenGrouped: Boolean,
    onToggleHideSingles: () -> Unit,
    slideshowActive: Boolean,
    onToggleSlideshow: () -> Unit,
    thumbnailSize: ThumbnailSize,
    onToggleThumbnailSize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        // ステータスバー分の余白を確保(Edge-to-Edge表示でも重ならないように)
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
        color = FujiSurfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenFolder) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = "フォルダを開く",
                            tint = FujiPrimaryDark
                        )
                    }
                    IconButton(onClick = onOpenZip) {
                        Icon(
                            Icons.Filled.FolderZip,
                            contentDescription = "ZIPを開く",
                            tint = FujiPrimaryDark
                        )
                    }

                    var historyExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { historyExpanded = true }) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = "履歴",
                                tint = FujiPrimaryDark
                            )
                        }
                        DropdownMenu(expanded = historyExpanded, onDismissRequest = { historyExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("既定のフォルダ(Download/未整理)") },
                                onClick = {
                                    historyExpanded = false
                                    onSelectDefaultFolder()
                                }
                            )
                            if (recentEntries.isNotEmpty()) {
                                androidx.compose.material3.HorizontalDivider()
                            }
                            recentEntries.forEach { entry ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (entry.type == RecentEntryType.ZIP) "📦 " else "📁 ") + entry.label
                                        )
                                    },
                                    onClick = {
                                        historyExpanded = false
                                        onSelectRecent(entry)
                                    }
                                )
                            }
                        }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = folderLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = FujiPrimaryDark
                            )
                            // テキストのカウントは読込が速いと一瞬で変わってしまい見えにくいため、
                            // 読み込み中(ストリーミング中)は小さなスピナーを添えて一目でわかるようにする
                            if (isStreaming) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = FujiPrimaryDark
                                )
                            }
                        }
                        Text(
                            text = when {
                                isLoading -> "読み込み中…"
                                // 読み込み中(ストリーミング中)は「読み込み済み/総数」を表示して進捗が分かるようにする
                                isStreaming && folderTotalCount > 0 -> "読込中 $imageCount / $folderTotalCount 枚"
                                isStreaming -> "読込中… $imageCount 枚"
                                else -> "$imageCount 枚"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = FujiPrimaryDark.copy(alpha = 0.7f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // サムネイルサイズ切替(100dp/150dpの2段階)
                    IconButton(onClick = onToggleThumbnailSize) {
                        Icon(
                            imageVector = if (thumbnailSize == ThumbnailSize.SMALL)
                                Icons.Filled.PhotoSizeSelectSmall else Icons.Filled.PhotoSizeSelectLarge,
                            contentDescription = "サムネイルサイズ切替(現在: ${thumbnailSize.dp}dp)",
                            tint = FujiPrimaryDark
                        )
                    }

                    // 彩度・明度プリセット(A/B/C)。「同画像のみ表示」がOFFでも単独で機能する
                    // (彩度・明度が近い画像同士でグルーピングする、ハッシュ判定を使わないモード)。
                    ColorGroupPreset.values().forEach { preset ->
                        val selected = colorPreset == preset
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = { onColorPresetSelected(preset) },
                            label = { Text(preset.name) },
                            modifier = Modifier.padding(end = 2.dp),
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FujiPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }

                    Text(
                        text = "同画像のみ表示",
                        style = MaterialTheme.typography.labelSmall,
                        color = FujiPrimaryDark,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Switch(
                        checked = sameImageOnly,
                        onCheckedChange = { onToggleSameImageOnly() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FujiPrimary,
                            checkedTrackColor = FujiPrimaryDark.copy(alpha = 0.5f)
                        )
                    )
                    IconButton(onClick = onToggleSlideshow) {
                        Icon(
                            imageVector = if (slideshowActive) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
                            contentDescription = "スライドショー",
                            tint = if (slideshowActive) FujiPrimaryDark else FujiPrimary
                        )
                    }
                }
            }

            // グループ化に関する行(「同画像のみ表示」ON、またはプリセット選択中に表示)
            if (sameImageOnly || colorPreset != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 判定の厳しさスライダーは「同画像のみ表示」(ハッシュ判定)の時だけ意味を持つ
                    if (sameImageOnly) {
                        Text(
                            text = "判定の厳しさ",
                            style = MaterialTheme.typography.labelSmall,
                            color = FujiPrimaryDark,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Slider(
                            value = groupThreshold.toFloat(),
                            onValueChange = { onThresholdChange(it.toInt()) },
                            valueRange = ImageGrouping.THRESHOLD_RANGE.first.toFloat()..ImageGrouping.THRESHOLD_RANGE.last.toFloat(),
                            modifier = Modifier.width(140.dp),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = FujiPrimaryDark,
                                activeTrackColor = FujiPrimary
                            )
                        )
                        Text(
                            text = groupThreshold.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = FujiPrimaryDark,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    } else {
                        Text(
                            text = "色味のみで判定中(${colorPreset?.label ?: ""})",
                            style = MaterialTheme.typography.labelSmall,
                            color = FujiPrimaryDark,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }

                    // 設定変更の効果を確認しやすくするための、見つかったグループ数の表示。
                    // 読込中/計算中は数字が古い(直前フォルダのもの)と誤解されないよう文言を変える。
                    Text(
                        text = when {
                            isStreaming -> "グループ: 読込完了後に計算"
                            isGrouping -> "グループ: 計算中…"
                            else -> "グループ: ${groupCount}件"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = FujiPrimaryDark,
                        modifier = Modifier.padding(start = 10.dp)
                    )

                    // グループのみ表示(単独画像を隠す)。設定を変えた時の見た目の変化を分かりやすくする
                    androidx.compose.material3.FilterChip(
                        selected = hideSinglesWhenGrouped,
                        onClick = onToggleHideSingles,
                        label = { Text("グループのみ") },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FujiPrimary,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                }
            }
        }
    }
}
