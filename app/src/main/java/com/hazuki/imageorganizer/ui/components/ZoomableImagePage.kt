package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import com.hazuki.imageorganizer.data.ImageItem
import kotlin.math.max

/**
 * 拡大表示画面(FullscreenViewer)の中で「1枚の画像」を担当するコンポーネントです。
 *
 * 【このファイルで実装しているジェスチャー】
 * 1. 2本指ピンチ  → 拡大・縮小(ズーム)
 * 2. 拡大中に1本指で移動 → 画像内の見たい場所への移動(パン)
 * 3. ダブルタップ  → タップした場所を中心に、最大表示⇔等倍表示を切り替え
 *
 * 【なぜ独自実装にしているか(重要)】
 * Composeが標準で用意している detectTransformGestures という関数は、
 * 「指が1本だけ」動いた場合でも反応してしまう性質があります。
 * これをそのまま使うと、指1本で横にスワイプしただけの操作まで
 * このコンポーネントが横取りしてしまい、親のHorizontalPager(ページめくり)に
 * 指の動きが届かなくなってしまいます。
 * そこで、指の本数を自分で数えて、
 *   ・指が2本のときだけ「ピンチズーム」として処理する
 *   ・指が1本でも、すでに拡大中のときだけ「パン」として処理する
 *   ・指が1本で、かつ等倍のときは、何もしない(=HorizontalPagerに動きを譲る)
 * という条件分けを自前で行っています。
 *
 * 【なぜpointerInputをgraphicsLayerより前に書いているか(重要)】
 * graphicsLayerで画像を拡大すると、その後ろ(内側)にある処理は
 * 「拡大される前の、元のサイズの座標」で指の動きを受け取ってしまいます。
 * そのため、指を動かした量がそのまま反映されず、拡大率に応じて
 * 移動量が目減りしてしまう不具合が起きます。
 * pointerInputをgraphicsLayerより前(外側)に書くことで、
 * 画面に表示されている実際の指の動きをそのまま受け取れるようにしています。
 *
 * @param image 表示する画像の情報
 * @param onScaleChanged 拡大率が変化するたびに呼ばれるコールバック(親への報告用)
 */
@Composable
fun ZoomableImagePage(
    image: ImageItem,
    onScaleChanged: (Float) -> Unit
) {
    // ズームの最小値・最大値(ピンチ操作での上限)
    val minScale = 1f
    val maxScale = 5f
    // ダブルタップしたときに切り替わる「決め打ちの拡大率」
    // (ピンチの上限maxScaleとは別に、タップ1回でちょうどいい大きさに拡大するための値)
    val doubleTapScale = 3f

    // 現在の拡大率(1.0が等倍)
    var scale by remember { mutableStateOf(1f) }
    // 現在の表示位置のズレ(パン)。等倍のときは常に0にリセットします。
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // 画面(このコンポーネント自身)の実際のピクセルサイズを知るために
    // BoxWithConstraintsを使います。これがないと「画像がどこまで動かせるか」の
    // 上限(はみ出し防止の壁)や、ダブルタップでの中心位置を計算できません。
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // 現在の拡大率における「動かせる範囲の壁」を計算する小さな関数です。
        // 拡大した分だけ画像がはみ出すので、そのはみ出し量の半分までしか
        // 動かせないようにすることで、画像が画面から完全に消えてしまうのを防ぎます。
        fun maxOffsetFor(currentScale: Float): Pair<Float, Float> {
            val maxOffsetX = max(0f, (widthPx * (currentScale - 1f)) / 2f)
            val maxOffsetY = max(0f, (heightPx * (currentScale - 1f)) / 2f)
            return maxOffsetX to maxOffsetY
        }

        AsyncImage(
            model = image.uri,
            contentDescription = image.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                // ① ピンチズーム & 拡大中のパンを検知する処理(自前実装)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 最初の指が触れるのを待つ(requireUnconsumed = falseにして、
                        // 他のジェスチャー検知と競合しても検知自体は行えるようにする)
                        awaitFirstDown(requireUnconsumed = false)

                        do {
                            val event = awaitPointerEvent()
                            val changes = event.changes

                            if (changes.size >= 2) {
                                // 【2本指】ピンチズーム + 2本指でのパン
                                val pointer1 = changes[0]
                                val pointer2 = changes[1]

                                // 2点間の距離の変化量から、拡大率の変化(zoom)を計算する
                                val previousDistance =
                                    (pointer1.previousPosition - pointer2.previousPosition).getDistance()
                                val currentDistance =
                                    (pointer1.position - pointer2.position).getDistance()

                                if (previousDistance > 0f) {
                                    val zoomChange = currentDistance / previousDistance
                                    val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)

                                    if (newScale <= minScale) {
                                        // 等倍まで縮小したら、位置ズレも強制的に0に戻す
                                        scale = minScale
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        scale = newScale

                                        // 2本指の中心点(centroid)の移動量を、画像のパンとして反映する
                                        val previousCentroid =
                                            (pointer1.previousPosition + pointer2.previousPosition) / 2f
                                        val currentCentroid =
                                            (pointer1.position + pointer2.position) / 2f
                                        val panChange = currentCentroid - previousCentroid

                                        val (maxOffsetX, maxOffsetY) = maxOffsetFor(scale)
                                        offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                        offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                    }

                                    onScaleChanged(scale)
                                }

                                // 2本指の操作はこのコンポーネントの中だけで完結させる
                                // (HorizontalPagerなど、他の場所に伝える必要がないため)
                                changes.forEach { it.consume() }
                            } else if (changes.size == 1 && scale > minScale) {
                                // 【1本指・拡大中のみ】画像内を見て回るためのパン移動
                                val change = changes[0]
                                val panChange = change.position - change.previousPosition

                                val (maxOffsetX, maxOffsetY) = maxOffsetFor(scale)
                                offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)

                                // 拡大中の1本指移動も、ここで処理を完結させる
                                // (親のHorizontalPagerは既にuserScrollEnabled=falseになっているが、
                                // 念のためここでも明示的にconsumeしておく)
                                change.consume()
                            }
                            // 【1本指・等倍のとき】は、ここでは何もしない(consumeしない)。
                            // これにより指の動きがそのままHorizontalPagerまで届き、
                            // 今まで通りスワイプでの前後移動が機能する。
                        } while (event.changes.any { it.pressed })
                    }
                }
                // ② ダブルタップで最大表示⇔等倍を切り替える処理
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > minScale) {
                                // すでに拡大中 → 等倍に戻す
                                scale = minScale
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                // 等倍 → タップした場所を中心に拡大する
                                scale = doubleTapScale

                                // タップした位置が画面中心からどれだけズレているかを求め、
                                // そのズレを打ち消す向きにオフセットをかけることで、
                                // 「タップした場所へ寄っていくように拡大される」見た目にする
                                val centerX = widthPx / 2f
                                val centerY = heightPx / 2f
                                val rawOffsetX = (centerX - tapOffset.x) * (doubleTapScale - 1f)
                                val rawOffsetY = (centerY - tapOffset.y) * (doubleTapScale - 1f)

                                val (maxOffsetX, maxOffsetY) = maxOffsetFor(doubleTapScale)
                                offsetX = rawOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = rawOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
                            }
                            onScaleChanged(scale)
                        }
                    )
                }
                // ③ 実際の見た目上の拡大・移動を適用する(①②より後ろに書くのがポイント)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}
