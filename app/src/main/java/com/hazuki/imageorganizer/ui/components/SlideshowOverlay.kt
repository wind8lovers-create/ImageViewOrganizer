package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.viewmodel.SlideshowInterval

/**
 * スライドショー本体。タップで次の画像へ、長押しで一覧表示に復帰する。
 * 反転表示: 背景を黒、操作系ラベルは白文字反転で統一(押した瞬間に文字色が反転する仕様の表現)。
 */
@Composable
fun SlideshowOverlay(
    entries: List<ImageItem>,
    currentIndex: Int,
    interval: SlideshowInterval,
    onTapAdvance: () -> Unit,
    onIntervalSelected: (SlideshowInterval) -> Unit,
    onStop: () -> Unit
) {
    if (entries.isEmpty()) return
    val image = entries[currentIndex % entries.size]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // タップで次の画像へ、長押しでスライドショーを終了して一覧表示に戻る
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapAdvance() },
                    onLongPress = { onStop() }
                )
            }
    ) {
        AsyncImage(
            model = image.uri,
            contentDescription = image.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.White) // 反転: 白背景+黒文字
                // ステータスバー(時計・電波・バッテリー等)と重ならないよう、上に余白を確保する
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            SlideshowInterval.values().forEach { opt ->
                Text(
                    text = "${opt.seconds}秒",
                    color = if (opt == interval) Color.Black else Color.Gray,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clickable { onIntervalSelected(opt) }
                )
            }
            Text(
                text = "終了",
                color = Color.Red,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { onStop() }
            )
        }
    }
}
