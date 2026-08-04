package com.hazuki.imageorganizer.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import kotlin.random.Random

/**
 * 和紙風の背景。
 * フリー素材の画像を後から差し替えられるよう、
 * 単純な塗りつぶし + 薄い繊維ラインの procedural 生成にしてある。
 * (現時点ではネットワークから画像を取得していないため、簡易的な代替表現)
 */
@Composable
fun WashiBackground(modifier: Modifier = Modifier) {
    // 毎回同じ模様になるよう固定シードにする(再Composeでちらつかないように)
    val fibers = remember { generateFibers(seed = 42, count = 140) }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = FujiBackground)
        drawFibers(fibers)
    }
}

private data class Fiber(
    val xRatio: Float,
    val yRatio: Float,
    val lengthRatio: Float,
    val angleDeg: Float,
    val alpha: Float
)

private fun generateFibers(seed: Int, count: Int): List<Fiber> {
    val rnd = Random(seed)
    return List(count) {
        Fiber(
            xRatio = rnd.nextFloat(),
            yRatio = rnd.nextFloat(),
            lengthRatio = 0.02f + rnd.nextFloat() * 0.05f,
            angleDeg = rnd.nextFloat() * 360f,
            alpha = 0.03f + rnd.nextFloat() * 0.05f
        )
    }
}

private fun DrawScope.drawFibers(fibers: List<Fiber>) {
    val w = size.width
    val h = size.height
    fibers.forEach { f ->
        val cx = f.xRatio * w
        val cy = f.yRatio * h
        val len = f.lengthRatio * w
        val rad = Math.toRadians(f.angleDeg.toDouble())
        val dx = (Math.cos(rad) * len / 2).toFloat()
        val dy = (Math.sin(rad) * len / 2).toFloat()
        drawLine(
            color = FujiPrimaryLight.copy(alpha = f.alpha),
            start = Offset(cx - dx, cy - dy),
            end = Offset(cx + dx, cy + dy),
            strokeWidth = 1.2f,
            cap = StrokeCap.Round
        )
    }
}
