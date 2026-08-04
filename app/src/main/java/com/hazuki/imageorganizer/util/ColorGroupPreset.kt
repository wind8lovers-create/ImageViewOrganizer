package com.hazuki.imageorganizer.util

/**
 * 彩度・明度の許容差のプリセット(A/B/C)。
 * image_sort_selectorでの経験上、彩度・明度も加味した方が精度が良かったとのことなので、
 * スライダーではなくワンタップのプリセットとして提供する。
 *
 * tolerance(許容差)は0f(完全一致のみ)〜1f(制限なし)の範囲。値が小さいほど厳しい判定になる。
 */
enum class ColorGroupPreset(val label: String, val saturationTolerance: Float, val brightnessTolerance: Float) {
    A("A(厳しめ)", 0.06f, 0.06f),
    B("B(標準)", 0.15f, 0.15f),
    C("C(緩め)", 0.30f, 0.30f)
}
