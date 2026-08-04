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

    /** 彩度・明度の平均値(0f〜1f)。dHash計算時に使う縮小ビットマップを再利用して計算する。 */
    data class ColorProfile(val avgSaturation: Float, val avgBrightness: Float)

    /**
     * dHash と 彩度・明度の平均値を同時に計算する(同じ縮小ビットマップを使い回すため効率的)。
     * デコードに失敗した場合は null。
     */
    fun computeHashAndColor(resolver: ContentResolver, uri: Uri): Pair<Long, ColorProfile>? {
        val bitmap = decodeSampledBitmap(resolver, uri, HASH_WIDTH, HASH_HEIGHT) ?: return null
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true)
            val hash = computeFromBitmap(small)
            val color = computeColorProfile(small)
            if (small !== bitmap) small.recycle()
            hash to color
        } finally {
            bitmap.recycle()
        }
    }

    private fun computeColorProfile(small: Bitmap): ColorProfile {
        var satSum = 0f
        var brightSum = 0f
        var count = 0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
                satSum += hsv[1]
                brightSum += hsv[2]
                count++
            }
        }
        return if (count == 0) ColorProfile(0f, 0f) else ColorProfile(satSum / count, brightSum / count)
    }

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
