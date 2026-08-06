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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.data.RecentEntry
import com.hazuki.imageorganizer.data.RecentEntryType
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurfaceVariant

/**
 * スライダーの操作位置(0.0〜1.0)を、計算用の閾値(0.0〜100.0)に非線形にマッピングする。
 * 加速度的な変化(最初は速く、後半はゆっくり)を実現するための折れ線変換。
 * (RGB5色=スタイル一致度スライダー専用。彩度・明度スライダーは単純な線形のままでよい)
 */
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

/** mapSliderToThreshold の逆変換。現在の閾値からスライダーのノブ位置を求める。 */
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

/** 「プリセット」ボタンの表示ラベル(0=OFF, 1=A, 2=B, 3=C) */
private fun presetStepLabel(step: Int): String = when (step) {
    1 -> "A"
    2 -> "B"
    3 -> "C"
    else -> "OFF"
}

@Composable
fun OrganizerTopBar(
    folderLabel: String,
    imageCount: Int,
    folderTotalCount: Int,
    isLoading: Boolean,
    isStreaming: Boolean,
    isComparing: Boolean,
    onOpenFolder: () -> Unit,
    onOpenZip: () -> Unit,
    recentEntries: List<RecentEntry>,
    onSelectRecent: (RecentEntry) -> Unit,
    onSelectDefaultFolder: () -> Unit,
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
    slideshowActive: Boolean,
    onToggleSlideshow: () -> Unit,
    thumbnailSize: ThumbnailSize,
    onToggleThumbnailSize: () -> Unit,
    modifier: Modifier = Modifier
) {
    // --- 画面幅に応じたスライダー幅の決定 ---
    // 現在の画面情報を取得します
    val configuration = LocalConfiguration.current
    // 画面の横幅が 600dp 以上ならタブレットとみなし、260dp にします。
    // それ以外（スマホなど）は 120dp に設定します。
    val sliderWidth = if (configuration.screenWidthDp >= 600) 260.dp else 120.dp

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
                            // テキストのカウントは読込/計算が速いと一瞬で変わってしまい見えにくいため、
                            // 読み込み中・拡張選択の絞り込み計算中は小さなスピナーを添えて一目でわかるようにする
                            if (isStreaming || isComparing) {
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
                                isComparing -> "計算中…"
                                // 読み込み中(ストリーミング中)は「読み込み済み/総数」を表示して進捗が分かるようにする
                                isStreaming && folderTotalCount > 0 -> "読込中 $imageCount / $folderTotalCount 枚"
                                isStreaming -> "読込中… $imageCount 枚"
                                extensionSelectionActive -> "一致: $matchedCount 件"
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

                    // 「拡張選択」: 押すと反転表示になり、下に彩度・明度・ハッシュ値・縦横比・RGB5色の
                    // 操作パネルが出る。もう一度押すと解除して通常の一覧表示に戻る。
                    FilterChip(
                        selected = extensionSelectionActive,
                        onClick = onToggleExtensionSelection,
                        label = { Text("拡張選択") },
                        modifier = Modifier.padding(end = 4.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FujiPrimary,
                            selectedLabelColor = Color.White
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

            // 拡張選択パネル(「拡張選択」ON時だけ表示)。1列目: 彩度・明度・プリセット。2列目: ハッシュ値・縦横比・RGB5色。
            if (extensionSelectionActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "彩度", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                    Slider(
                        value = saturationTolerance,
                        onValueChange = onSaturationChange,
                        valueRange = 0f..0.35f,
                        // 固定の 120.dp から、画面幅で決まる sliderWidth に変更しました
                        modifier = Modifier.width(sliderWidth).padding(horizontal = 4.dp),
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
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    Slider(
                        value = brightnessTolerance,
                        onValueChange = onBrightnessChange,
                        valueRange = 0f..0.35f,
                        // 固定の 120.dp から、画面幅で決まる sliderWidth に変更しました
                        modifier = Modifier.width(sliderWidth).padding(horizontal = 4.dp),
                        colors = SliderDefaults.colors(thumbColor = FujiPrimaryDark, activeTrackColor = FujiPrimary)
                    )
                    Text(
                        text = "${(brightnessTolerance * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = FujiPrimaryDark
                    )

                    FilterChip(
                        selected = colorPresetStep != 0,
                        onClick = onCyclePreset,
                        label = { Text(presetStepLabel(colorPresetStep)) },
                        modifier = Modifier.padding(start = 10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FujiPrimary,
                            selectedLabelColor = Color.White
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "ハッシュ値", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                    Switch(
                        checked = hashMatchEnabled,
                        onCheckedChange = onToggleHashMatch,
                        modifier = Modifier.padding(end = 10.dp),
                        colors = SwitchDefaults.colors(checkedThumbColor = FujiPrimary, checkedTrackColor = FujiPrimaryDark.copy(alpha = 0.5f))
                    )

                    Text(text = "縦横比", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                    Switch(
                        checked = aspectRatioOnly,
                        onCheckedChange = onToggleAspectRatioOnly,
                        modifier = Modifier.padding(end = 10.dp),
                        colors = SwitchDefaults.colors(checkedThumbColor = FujiPrimary, checkedTrackColor = FujiPrimaryDark.copy(alpha = 0.5f))
                    )

                    Text(text = "RGB5色", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                    Slider(
                        value = mapThresholdToSlider(styleMatchThreshold),
                        onValueChange = { onStyleMatchThresholdChange(mapSliderToThreshold(it)) },
                        valueRange = 0f..1f,
                        // 固定の 120.dp から、画面幅で決まる sliderWidth に変更しました
                        modifier = Modifier.width(sliderWidth).padding(horizontal = 4.dp),
                        colors = SliderDefaults.colors(thumbColor = FujiPrimaryDark, activeTrackColor = FujiPrimary)
                    )
                    Text(
                        text = java.lang.String.format(java.util.Locale.US, "%.0f%%", styleMatchThreshold),
                        style = MaterialTheme.typography.labelSmall,
                        color = FujiPrimaryDark
                    )
                }
            }
        }
    }
}
