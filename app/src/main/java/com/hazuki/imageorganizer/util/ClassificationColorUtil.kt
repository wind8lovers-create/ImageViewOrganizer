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

    /**
     * 【はづきさん厳選・見分けやすい7色ローテーションパレット】
     * 隣り合うグループの境界が一目でわかるよう、明暗と色相が交互に配置された7色。
     *
     * 1. 🔴 レッド (はっきりした赤)
     * 2. 🔵 #5d63b0 (上品な藍紫・スレートブルー)
     * 3. 🟠 オレンジ (明るい橙)
     * 4. 🟡 #b5b877 (若草色・オリーブイエロー)
     * 5. 🟣 パープル (深みのある紫)
     * 6. 🌸 ピンク / マゼンタ (華やかな桃色)
     * 7. 🟢 #54AA70 (鮮やかなフォレストグリーン・翡翠色)
     */
    val SEVEN_COLORS: List<Color> = listOf(
        Color(0xFFE53935), // 1. 🔴 レッド
        Color(0xFF5D63B0), // 2. 藍紫 (#5d63b0)
        Color(0xFFFB8C00), // 3. 🟠 オレンジ
        Color(0xFFB5B877), // 4. 若草色 (#b5b877)
        Color(0xFF8E24AA), // 5. 🟣 パープル
        Color(0xFF606060), // 6. ⬛ ダークグレー (#606060)
        Color(0xFF54AA70)  // 7. 🟢 翡翠グリーン (#54AA70)
    )

    private val cache: Map<Char, Color> by lazy { buildPalette() }

    /** カテゴリ文字(A〜Z)に対応する枠線色を返す。7色をローテーションで循環利用します。 */
    fun colorForCategory(category: Char): Color {
        val upper = category.uppercaseChar()
        return cache[upper] ?: SEVEN_COLORS[0]
    }

    /** 0から始まる通し番号から直接7色をローテーションで取得するヘルパー */
    fun colorForIndex(index: Int): Color {
        val safeIndex = kotlin.math.abs(index) % SEVEN_COLORS.size
        return SEVEN_COLORS[safeIndex]
    }

    private fun buildPalette(): Map<Char, Color> {
        val letters = ('A'..'Z').toList()
        return letters.mapIndexed { index, ch ->
            // 7色を順番にローテーション（0〜6 ➔ 0〜6 ...）
            ch to SEVEN_COLORS[index % SEVEN_COLORS.size]
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
