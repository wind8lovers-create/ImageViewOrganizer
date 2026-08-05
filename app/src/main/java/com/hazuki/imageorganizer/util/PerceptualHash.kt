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

    /** 代表色(カラーパレット)抽出用の縮小サイズ。要件通り32x32。 */
    private const val PALETTE_SAMPLE_SIZE = 32

    /** 彩度・明度の平均値(0f〜1f)。dHash計算時に使う縮小ビットマップを再利用して計算する。 */
    data class ColorProfile(val avgSaturation: Float, val avgBrightness: Float)

    /** dHash・彩度明度・代表色パレット・元画像サイズを、1回のデコードからまとめて計算した結果 */
    data class AnalysisResult(
        val hash: Long,
        val color: ColorProfile,
        val palette: List<ColorPalette.PaletteColor>,
        val originalWidth: Int,
        val originalHeight: Int
    )

    /**
     * dHash + 彩度/明度 + 代表色パレット(5色) + 元画像の幅高さを、同じデコード処理からまとめて計算する。
     *
     * 【重要】このアプリの一覧サムネイルはCoilが独自にデコード・キャッシュしており、
     * ここから再利用することはできない。そのため、従来通りUriから直接デコードするが、
     * 従来の dHash 用(9x8)より一段階大きい 32x32 相当でデコードし、
     * ・9x8へさらに縮小してdHash/彩度明度を計算
     * ・32x32のまま代表色パレット(K-means)を計算
     * という形で、ファイルを2回開かずに済ませている(32x32でも十分軽量なため速度への影響は軽微)。
     * デコードに失敗した場合は null。
     */
    fun analyze(resolver: ContentResolver, uri: Uri): AnalysisResult? {
        val decoded = decodeSampledBitmapWithOriginalSize(resolver, uri, PALETTE_SAMPLE_SIZE, PALETTE_SAMPLE_SIZE)
            ?: return null
        val (bitmap, originalWidth, originalHeight) = decoded
        return try {
            val palette32 = Bitmap.createScaledBitmap(bitmap, PALETTE_SAMPLE_SIZE, PALETTE_SAMPLE_SIZE, true)
            val palette = ColorPalette.extract(palette32)
            if (palette32 !== bitmap) palette32.recycle()

            val small9x8 = Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true)
            val hash = computeFromBitmap(small9x8)
            val color = computeColorProfile(small9x8)
            if (small9x8 !== bitmap) small9x8.recycle()

            AnalysisResult(hash, color, palette, originalWidth, originalHeight)
        } finally {
            bitmap.recycle()
        }
    }

    /** 縮小デコードと同時に、デコード前の元画像サイズ(bounds)も取得する */
    private fun decodeSampledBitmapWithOriginalSize(
        resolver: ContentResolver,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Triple<Bitmap, Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // サイズ計測モード(inJustDecodeBounds=true)では decodeStream は常に null を返すが、
            // ここでストリームを正常に開いて処理できたかどうかが重要。
            val headerOk = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
                true
            } ?: false

            if (!headerOk || opts.outWidth <= 0 || opts.outHeight <= 0) return null

            val originalWidth = opts.outWidth
            val originalHeight = opts.outHeight
            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false

            val bitmap = resolver.openInputStream(uri)?.use { input2 ->
                BitmapFactory.decodeStream(input2, null, opts)
            } ?: return null

            Triple(bitmap, originalWidth, originalHeight)
        } catch (e: Exception) {
            null
        }
    }

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
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val headerOk = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
                true
            } ?: false

            if (!headerOk) return null

            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false

            resolver.openInputStream(uri)?.use { input2 ->
                BitmapFactory.decodeStream(input2, null, opts)
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
