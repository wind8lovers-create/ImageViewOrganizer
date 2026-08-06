package com.hazuki.imageorganizer.util

/**
 * 「拡張選択」の彩度・明度プリセット(A/B/C)。
 * 前アプリ image_sort_selector で実際に使われていた数値をそのまま踏襲している。
 *
 * tolerance(許容差)は0f(絞り込みなし)〜0.35f(35%まで許容)の範囲。
 * ※ 彩度・明度スライダーは「0%=その項目では絞り込まない」という約束事になっている
 *   (スタイル一致度スライダーの「0%で絞り込みなし」と同じ考え方)。
 *   なので、例えばA(彩度15%/明度0%)は「彩度だけで絞り込み、明度は見ない」という意味になる。
 */
enum class ColorGroupPreset(val label: String, val saturationTolerance: Float, val brightnessTolerance: Float) {
    A("A", 0.15f, 0f),
    B("B", 0f, 0.30f),
    C("C", 0.30f, 0f)
}
