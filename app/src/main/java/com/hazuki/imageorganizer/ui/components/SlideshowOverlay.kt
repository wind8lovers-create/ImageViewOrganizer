package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
 * パワーアップしたスライドショー画面。
 * アルバムのように左右にめくれる(HorizontalPager)構造になり、一時停止や高速再生に対応。
 *
 * 【今回追加した機能】
 * 一時停止中は、拡大表示画面(FullscreenViewer)と同じ「ZoomableImagePage」部品を使い、
 * ピンチズーム・拡大中のパン・ダブルタップでの拡大縮小ができるようにしています。
 * 再生中(自動でページがめくられている間)は今まで通りの、ズーム機能なしの表示のままです。
 * (自動再生中にズームできてしまうと、拡大している最中に画像が切り替わって
 * 混乱するため、あえて一時停止中だけに限定しています)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideshowOverlay(
    entries: List<ImageItem>,
    currentIndex: Int,
    interval: SlideshowInterval,
    paused: Boolean,
    onPauseToggle: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onIntervalSelected: (SlideshowInterval) -> Unit,
    onStop: () -> Unit
) {
    if (entries.isEmpty()) return

    // 4000枚あっても「今見ている周辺」しか読み込まないPagerを使用
    val pagerState = rememberPagerState(initialPage = currentIndex) { entries.size }
    var menuExpanded by remember { mutableStateOf(false) }

    // 「今表示中のページが、どのくらいの倍率でズームされているか」を覚えておく変数です。
    // FullscreenViewerのときと同じ考え方で、ここが1倍(等倍)より大きい間は
    // HorizontalPagerの横スワイプ(ページめくり)を止めて、
    // 指の動きを「画像内の移動(パン)」専用にします。
    var currentScale by remember { mutableStateOf(1f) }

    // ページ(表示している写真)が切り替わったタイミングで、ズーム状態を等倍にリセットします。
    LaunchedEffect(pagerState.currentPage) {
        currentScale = 1f
    }

    // 一時停止が解除された(再生が再開された)タイミングでも、念のためズームをリセットします。
    // (再生中はそもそもAsyncImage表示に切り替わってズーム機能自体を使わなくなりますが、
    // 保険として値を戻しておきます)
    LaunchedEffect(paused) {
        if (!paused) {
            currentScale = 1f
        }
    }

    // ViewModel側のインデックス（自動再生による進捗）が変わったら、Pagerをその位置へ移動させる
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }

    // ユーザーが手動でスワイプしてページを変えたら、ViewModel側に「今ここを見ている」と伝える
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPageSelected(page)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // アルバム本体：左右に自由にめくれる
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // ページ間隔を少し空けて、境界をわかりやすくする
            pageSpacing = 16.dp,
            // ズームしていない(等倍)ときだけスワイプでのページ送りを許可します。
            // 再生中は常にcurrentScaleが1fのままなので、今まで通りスワイプ可能です。
            userScrollEnabled = currentScale <= 1f
        ) { page ->
            val image = entries[page]
            if (paused) {
                // 一時停止中：ピンチズーム・パン・ダブルタップ拡大が使える表示
                ZoomableImagePage(
                    image = image,
                    onScaleChanged = { newScale -> currentScale = newScale }
                )
            } else {
                // 再生中：今まで通りのシンプルな表示(ズーム機能なし)
                AsyncImage(
                    model = image.uri,
                    contentDescription = image.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 上部の操作バー：秒数設定や停止ボタンを配置
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
        ) {
            // ステータスバー（時計など）と重ならないための黒帯
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .windowInsetsTopHeight(WindowInsets.statusBars)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.9f)) // 少し透けさせて圧迫感を減らす
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 秒数設定（プルダウンメニュー風）
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { menuExpanded = true }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = interval.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Black
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.Black)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        SlideshowInterval.values().forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    onIntervalSelected(opt)
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }

                // 再生・一時停止ボタン
                IconButton(
                    onClick = onPauseToggle,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) "再生" else "一時停止",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // 終了ボタン
                Text(
                    text = "終了",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Red,
                    modifier = Modifier
                        .clickable { onStop() }
                        .padding(12.dp)
                )
            }
        }
    }
}
