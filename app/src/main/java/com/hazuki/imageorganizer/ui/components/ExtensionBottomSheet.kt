package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurface

/**
 * 「拡張」ボタンから開く設定パネル。選択中の画像(基準画像)に似た画像を絞り込む。
 * ・アスペクト比: 基準画像と縦横比が一致する画像のみ表示するON/OFF
 * ・スタイル一致度スライダー: 代表色パレットの類似度が閾値以上の画像のみ表示(0=絞り込みなし)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionBottomSheet(
    sheetState: SheetState,
    originLabel: String,
    matchedCount: Int,
    isCalculating: Boolean,
    aspectRatioOnly: Boolean,
    onToggleAspectRatioOnly: (Boolean) -> Unit,
    styleMatchThreshold: Int,
    onStyleMatchThresholdChange: (Int) -> Unit,
    onDisableFilter: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FujiSurface
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Text(
                text = "拡張機能: 似た画像を絞り込む",
                fontWeight = FontWeight.Bold,
                color = FujiPrimaryDark
            )
            Text(
                text = "基準画像: $originLabel",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = FujiPrimaryDark.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = if (isCalculating) "計算中…" else "一致: ${matchedCount}件",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = FujiPrimaryDark.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "アスペクト比",
                    color = FujiPrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = aspectRatioOnly,
                    onCheckedChange = onToggleAspectRatioOnly,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FujiPrimary,
                        checkedTrackColor = FujiPrimaryDark.copy(alpha = 0.5f)
                    )
                )
            }
            Text(
                text = "基準画像と縦横比が同じ画像だけを表示します",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "スタイル一致度スライダー",
                color = FujiPrimaryDark
            )
            Text(
                text = "色使いのクセ(代表色)が似ている画像だけを表示します。0%で絞り込みなし。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = FujiPrimaryDark.copy(alpha = 0.7f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = styleMatchThreshold.toFloat(),
                    onValueChange = { onStyleMatchThresholdChange(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = FujiPrimaryDark,
                        activeTrackColor = FujiPrimary
                    )
                )
                Text(
                    text = "${styleMatchThreshold}%",
                    color = FujiPrimaryDark,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onDisableFilter) {
                Text("絞り込みを解除して通常表示に戻す", color = FujiPrimaryDark)
            }
        }
    }
}
