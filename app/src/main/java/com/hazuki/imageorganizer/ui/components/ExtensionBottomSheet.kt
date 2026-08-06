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
    styleMatchThreshold: Float,
    onStyleMatchThresholdChange: (Float) -> Unit,
    onDisableFilter: () -> Unit,
    onDismiss: () -> Unit
) {
    /**
     * スライダーの操作位置(0.0〜1.0)を、計算用の閾値(0.0〜100.0)に非線形にマッピングする。
     * 加速度的な変化(最初は速く、後半はゆっくり)を実現するための折れ線変換。
     */
    fun mapSliderToThreshold(sliderValue: Float): Float {
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
    fun mapThresholdToSlider(threshold: Float): Float {
        return when {
            threshold <= 0f -> 0f
            threshold <= 50f -> (threshold / 50f) * 0.1f
            threshold <= 60f -> 0.1f + ((threshold - 50f) / 10f) * 0.1f
            threshold <= 70f -> 0.2f + ((threshold - 60f) / 10f) * 0.3f
            threshold <= 80f -> 0.5f + ((threshold - 70f) / 10f) * 0.1f
            else -> 0.6f + ((threshold - 80f) / 20f) * 0.4f
        }
    }

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
                    value = mapThresholdToSlider(styleMatchThreshold),
                    onValueChange = { onStyleMatchThresholdChange(mapSliderToThreshold(it)) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = FujiPrimaryDark,
                        activeTrackColor = FujiPrimary
                    )
                )
                Text(
                    // 小数点第1位まで表示(0.5刻み等の微調整を視認しやすくするため)
                    text = java.lang.String.format(java.util.Locale.US, "%.1f%%", styleMatchThreshold),
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

/** 線形補間(Linear Interpolation) */
private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}
