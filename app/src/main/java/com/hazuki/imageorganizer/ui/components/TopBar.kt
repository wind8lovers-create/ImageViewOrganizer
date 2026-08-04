package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurfaceVariant
import com.hazuki.imageorganizer.util.ImageGrouping

@Composable
fun OrganizerTopBar(
    folderLabel: String,
    imageCount: Int,
    isLoading: Boolean,
    onOpenFolder: () -> Unit,
    sameImageOnly: Boolean,
    onToggleSameImageOnly: () -> Unit,
    groupThreshold: Int,
    onThresholdChange: (Int) -> Unit,
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
                    Column {
                        Text(
                            text = folderLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = FujiPrimaryDark
                        )
                        Text(
                            text = if (isLoading) "読み込み中…" else "$imageCount 枚",
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
                    Text(
                        text = "同画像のみ表示",
                        style = MaterialTheme.typography.labelSmall,
                        color = FujiPrimaryDark
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

            if (sameImageOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        modifier = Modifier.weight(1f),
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
                }
            }
        }
    }
}
