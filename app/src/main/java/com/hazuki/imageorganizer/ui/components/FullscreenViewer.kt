package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.data.ImageItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenViewer(
    entries: List<ImageItem>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { entries.size }

    // 「今表示中のページが、どのくらいの倍率でズームされているか」を覚えておく変数です。
    // ここが1倍(等倍)より大きい間は、HorizontalPagerの横スワイプ(ページめくり)を
    // 止めて、指の動きを「画像内の移動(パン)」専用にします。
    // これにより「ピンチズーム中に指がちょっと横に動いただけで、
    // 次の写真に切り替わってしまう」という事故を防ぎます。
    var currentScale by remember { mutableStateOf(1f) }

    // ページ(表示している写真)が切り替わったタイミングで、
    // 念のためズーム状態を等倍にリセットします。
    // 「選択へ」ジャンプボタンなど、スワイプ以外の方法でページが変わる場合に備えた保険です。
    LaunchedEffect(pagerState.currentPage) {
        currentScale = 1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 長押しで一覧表示に戻る(ZIP書庫内の画像・通常フォルダの画像どちらでも共通)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onDismiss() })
            }
    ) {
        HorizontalPager(
            state = pagerState,
            // ズームしていない(等倍)ときだけスワイプでのページ送りを許可します。
            // 拡大中(currentScale > 1f)はfalseになり、Pagerが指の動きを無視するため、
            // ZoomableImagePage側のパン操作だけが効くようになります。
            userScrollEnabled = currentScale <= 1f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val image = entries[page]
            ZoomableImagePage(
                image = image,
                onScaleChanged = { newScale -> currentScale = newScale }
            )
        }

        IconButton(
            onClick = onDismiss,
            // 背景は既に黒(status bar自体は見えている)だが、ボタンだけはシステムの時計・電波表示と
            // 重なって押せなくなることがあるため、ステータスバー分だけ下にずらす。
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "閉じる", tint = Color.White)
        }
    }
}
