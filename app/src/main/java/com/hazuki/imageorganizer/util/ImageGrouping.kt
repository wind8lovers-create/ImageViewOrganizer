package com.hazuki.imageorganizer.util

import com.hazuki.imageorganizer.data.ImageItem

/**
 * 「同画像のみ表示」ON時のグルーピングロジック。
 * ハミング距離が閾値以下の画像同士を同じグループにまとめる(Union-Find)。
 */
object ImageGrouping {

    /**
     * 閾値スライダーのデフォルト値。dHash(64bit)のハミング距離の目安として、
     * 「かなり似ている」とみなせるライン。
     * ±20%の可動範囲は [defaultThreshold * 0.8, defaultThreshold * 1.2] とする。
     */
    const val DEFAULT_THRESHOLD = 10

    val THRESHOLD_RANGE: IntRange = run {
        val min = (DEFAULT_THRESHOLD * 0.8).toInt().coerceAtLeast(0)
        val max = (DEFAULT_THRESHOLD * 1.2).toInt().coerceAtMost(64)
        min..max
    }

    /**
     * @param images ハッシュ計算済みの画像リスト(表示順)
     * @param threshold ハミング距離の許容閾値(この値以下なら同一グループ)
     * @param saturationTolerance 彩度平均値の許容差(0f〜1f)。nullなら彩度は判定に使わない。
     * @param brightnessTolerance 明度平均値の許容差(0f〜1f)。nullなら明度は判定に使わない。
     * @return グループID -> 画像リスト のマップ(グループ化できた=2枚以上のもののみ)
     */
    fun group(
        images: List<ImageItem>,
        threshold: Int,
        saturationTolerance: Float? = null,
        brightnessTolerance: Float? = null
    ): Map<Int, List<ImageItem>> {
        val hashed = images.filter { it.perceptualHash != null }
        val parent = IntArray(hashed.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (i in hashed.indices) {
            for (j in i + 1 until hashed.size) {
                val hi = hashed[i].perceptualHash ?: continue
                val hj = hashed[j].perceptualHash ?: continue
                if (PerceptualHash.hammingDistance(hi, hj) > threshold) continue

                if (saturationTolerance != null) {
                    val si = hashed[i].avgSaturation
                    val sj = hashed[j].avgSaturation
                    if (si != null && sj != null && kotlin.math.abs(si - sj) > saturationTolerance) continue
                }
                if (brightnessTolerance != null) {
                    val bi = hashed[i].avgBrightness
                    val bj = hashed[j].avgBrightness
                    if (bi != null && bj != null && kotlin.math.abs(bi - bj) > brightnessTolerance) continue
                }

                union(i, j)
            }
        }

        val groups = mutableMapOf<Int, MutableList<ImageItem>>()
        for (i in hashed.indices) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(hashed[i])
        }

        // 1枚だけのグループは「グループなし(単独画像)」扱いにするため除外
        return groups.filterValues { it.size >= 2 }
            .values
            .mapIndexed { index, list -> index to list }
            .toMap()
    }

    /**
     * 彩度・明度のみを基準にしたグルーピング(ハッシュ判定なし)。
     * 「同画像のみ表示」がOFFの状態でプリセットA/B/Cだけを使い、色味が近い画像同士をまとめたい場合に使う
     * (前アプリ image_sort_selector にあった、プリセット単体での仕分け機能に相当)。
     *
     * @param images 彩度・明度計算済みの画像リスト(表示順)
     * @param saturationTolerance 彩度平均値の許容差(0f〜1f)
     * @param brightnessTolerance 明度平均値の許容差(0f〜1f)
     */
    fun groupByColor(
        images: List<ImageItem>,
        saturationTolerance: Float,
        brightnessTolerance: Float
    ): Map<Int, List<ImageItem>> {
        val withColor = images.filter { it.avgSaturation != null && it.avgBrightness != null }
        val parent = IntArray(withColor.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (i in withColor.indices) {
            for (j in i + 1 until withColor.size) {
                val si = withColor[i].avgSaturation ?: continue
                val sj = withColor[j].avgSaturation ?: continue
                if (kotlin.math.abs(si - sj) > saturationTolerance) continue

                val bi = withColor[i].avgBrightness ?: continue
                val bj = withColor[j].avgBrightness ?: continue
                if (kotlin.math.abs(bi - bj) > brightnessTolerance) continue

                union(i, j)
            }
        }

        val groups = mutableMapOf<Int, MutableList<ImageItem>>()
        for (i in withColor.indices) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(withColor[i])
        }

        return groups.filterValues { it.size >= 2 }
            .values
            .mapIndexed { index, list -> index to list }
            .toMap()
    }
}
