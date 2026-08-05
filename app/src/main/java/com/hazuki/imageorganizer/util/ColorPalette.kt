package com.hazuki.imageorganizer.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 画像の「色使いのクセ(スタイル)」を比較するための、軽量な代表色(カラーパレット)抽出・類似度計算。
 *
 * 【設計方針】
 * ・形や輪郭は一切見ず、色の分布だけを見る(K-meansで代表色5色+その割合を抽出)。
 * ・32x32(1024px)程度の縮小画像から計算するため、2000枚規模でも十分高速に動く。
 * ・類似度の比較は、保存済みの数値配列(PaletteColorのリスト)同士の距離計算のみで完結し、
 *   画像本体の再デコードは行わない(スライダー操作時のカクつき防止)。
 */
object ColorPalette {

    /** 代表色1色分: RGB(0f〜1f)と、画像内でその色が占める割合(0f〜1f) */
    data class PaletteColor(val r: Float, val g: Float, val b: Float, val weight: Float)

    private const val K = 5
    private const val MAX_ITERATIONS = 6

    /**
     * ビットマップからK-means法で代表色5色を抽出する。
     * @param bitmap 32x32程度に縮小済みのビットマップを渡すこと(呼び出し側で縮小してから渡す)。
     */
    fun extract(bitmap: Bitmap): List<PaletteColor> {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        if (n == 0) return emptyList()

        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 初期セントロイドは、ランダムではなくピクセル列から等間隔にK個サンプリングして決める
        // (毎回同じ画像なら同じ結果になり、比較の再現性が保てるため)
        val centroids = Array(K) { k ->
            val idx = (k * n / K).coerceIn(0, n - 1)
            val p = pixels[idx]
            floatArrayOf(
                ((p shr 16) and 0xFF).toFloat(),
                ((p shr 8) and 0xFF).toFloat(),
                (p and 0xFF).toFloat()
            )
        }

        val assignment = IntArray(n)
        repeat(MAX_ITERATIONS) {
            // 各ピクセルを最も近いセントロイドに割り当てる
            for (i in 0 until n) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                var best = 0
                var bestDist = Float.MAX_VALUE
                for (k in 0 until K) {
                    val c = centroids[k]
                    val dr = r - c[0]
                    val dg = g - c[1]
                    val db = b - c[2]
                    val dist = dr * dr + dg * dg + db * db
                    if (dist < bestDist) {
                        bestDist = dist
                        best = k
                    }
                }
                assignment[i] = best
            }
            // セントロイドを、割り当てられたピクセルの平均色で更新する
            val sums = Array(K) { FloatArray(3) }
            val counts = IntArray(K)
            for (i in 0 until n) {
                val k = assignment[i]
                val p = pixels[i]
                sums[k][0] += ((p shr 16) and 0xFF).toFloat()
                sums[k][1] += ((p shr 8) and 0xFF).toFloat()
                sums[k][2] += (p and 0xFF).toFloat()
                counts[k]++
            }
            for (k in 0 until K) {
                if (counts[k] > 0) {
                    centroids[k][0] = sums[k][0] / counts[k]
                    centroids[k][1] = sums[k][1] / counts[k]
                    centroids[k][2] = sums[k][2] / counts[k]
                }
            }
        }

        val finalCounts = IntArray(K)
        for (i in 0 until n) finalCounts[assignment[i]]++

        return (0 until K).map { k ->
            PaletteColor(
                r = centroids[k][0] / 255f,
                g = centroids[k][1] / 255f,
                b = centroids[k][2] / 255f,
                weight = finalCounts[k].toFloat() / n
            )
        }.sortedByDescending { it.weight }
    }

    /**
     * 2枚の代表色パレット同士の「スタイル一致度」を 0f(全く異なる)〜1f(ほぼ同じ) で返す。
     * 画像データ本体は一切使わず、パレットの数値配列同士の距離計算のみで完結する
     * (スライダー操作のたびに呼んでも軽い)。
     *
     * 簡易的な重み付き最近傍マッチング(Earth Mover's Distanceの近似)で計算する:
     * 基準側の各代表色について、比較先で最も近い色との距離を、基準側の占有率で重み付けして平均する。
     */
    fun similarity(a: List<PaletteColor>, b: List<PaletteColor>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var totalDist = 0f
        var totalWeight = 0f
        for (colorA in a) {
            val nearest = b.minByOrNull { colorB ->
                val dr = colorA.r - colorB.r
                val dg = colorA.g - colorB.g
                val db = colorA.b - colorB.b
                dr * dr + dg * dg + db * db
            } ?: continue
            val dr = colorA.r - nearest.r
            val dg = colorA.g - nearest.g
            val db = colorA.b - nearest.b
            // RGB(各0f〜1f)間の最大距離は sqrt(3) なので、0f〜1fに正規化する
            val dist = sqrt(dr * dr + dg * dg + db * db) / sqrt(3f)
            totalDist += dist * colorA.weight
            totalWeight += colorA.weight
        }
        if (totalWeight == 0f) return 0f
        val avgDist = totalDist / totalWeight
        return (1f - avgDist).coerceIn(0f, 1f)
    }

    /** 2つのアスペクト比がほぼ一致するとみなせるか(丸め誤差を吸収するための小さな許容誤差付き) */
    fun aspectRatioMatches(a: Float, b: Float, tolerance: Float = 0.02f): Boolean {
        return abs(a - b) <= tolerance
    }
}
