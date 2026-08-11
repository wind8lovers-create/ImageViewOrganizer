package com.hazuki.imageorganizer.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.roundToInt

/**
 * 手動グルーピング(分類)のカテゴリ(A〜Z)ごとの固定色を提供する。
 *
 * 要件定義 Q6: 「文字カテゴリ(A〜Z)ごとに固定の色が自動で決まる。同じカテゴリのグループは全部同じ色の枠になる」
 *
 * 和紙・藤色を基調とした既存テーマ(ui/theme/Color.kt)と馴染むよう、
 * 彩度・明度は抑えめにしつつ、26色それぞれの色相をずらして「見分けやすさ」を優先している。
 * (色相環を26分割し、少しずつ色相をずらすことで、隣り合うアルファベットでも色が被らないようにしている)
 */
object ClassificationColorUtil {

    private val cache: Map<Char, Color> by lazy { buildPalette() }

    /** カテゴリ文字(A〜Z)に対応する枠線色を返す。想定外の文字が来た場合はグレーを返す。 */
    fun colorForCategory(category: Char): Color {
        val upper = category.uppercaseChar()
        return cache[upper] ?: Color(0xFF9E9E9E)
    }

    private fun buildPalette(): Map<Char, Color> {
        val letters = ('A'..'Z').toList()
        val saturation = 0.55f
        val value = 0.80f
        return letters.mapIndexed { index, ch ->
            val hue = (360f / letters.size) * index
            ch to hsvToColor(hue, saturation, value)
        }.toMap()
    }

    /** HSV(色相・彩度・明度)からComposeのColorを組み立てる簡易ヘルパー */
    private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        return Color(red = r, green = g, blue = b)
    }

    /** グリッド上で暗く表示するときの重ね色(既存の DisabledContent と同じ考え方で、画像の上に半透明の黒を重ねる) */
    fun dimOverlay(base: Color = Color.Black, alpha: Float = 0.45f): Color =
        base.copy(alpha = alpha).compositeOver(Color.Transparent)
}
