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
     * @return グループID -> 画像リスト のマップ(グループ化できた=2枚以上のもののみ)
     */
    fun group(images: List<ImageItem>, threshold: Int): Map<Int, List<ImageItem>> {
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
                if (PerceptualHash.hammingDistance(hi, hj) <= threshold) {
                    union(i, j)
                }
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
}
