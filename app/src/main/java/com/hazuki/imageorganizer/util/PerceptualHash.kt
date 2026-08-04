package com.hazuki.imageorganizer.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 知覚ハッシュ(dHash方式)の計算ユーティリティ。
 * image_sort_selector から移植したロジックを、このプロジェクト用に整理したもの。
 *
 * dHashは画像を9x8グレースケールに縮小し、隣接ピクセルの明暗差から
 * 64bitのハッシュ値を作る。見た目が近い画像同士は、ハッシュのハミング距離が小さくなる。
 */
object PerceptualHash {

    private const val HASH_WIDTH = 9  // 差分を取るため横+1
    private const val HASH_HEIGHT = 8

    /**
     * Uriから縮小デコードして dHash(Long) を計算する。
     * デコードに失敗した場合は null を返す。
     */
    fun compute(resolver: ContentResolver, uri: Uri): Long? {
        val bitmap = decodeSampledBitmap(resolver, uri, HASH_WIDTH, HASH_HEIGHT) ?: return null
        return try {
            computeFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun computeFromBitmap(source: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(source, HASH_WIDTH, HASH_HEIGHT, true)
        var hash = 0L
        var bitIndex = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                val left = grayscale(small.getPixel(x, y))
                val right = grayscale(small.getPixel(x + 1, y))
                if (left > right) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        if (small !== source) small.recycle()
        return hash
    }

    /** 2つのハッシュ間のハミング距離(0〜64、値が小さいほど似ている) */
    fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }

    private fun grayscale(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun decodeSampledBitmap(
        resolver: ContentResolver,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, opts)
                opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
                opts.inJustDecodeBounds = false

                resolver.openInputStream(uri)?.use { input2 ->
                    BitmapFactory.decodeStream(input2, null, opts)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
