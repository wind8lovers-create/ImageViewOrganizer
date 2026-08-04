package com.hazuki.imageorganizer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.hazuki.imageorganizer.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private val IMAGE_MIME_PREFIXES = listOf("image/")

/** 一度に画面へ反映する件数(これが「先に50枚だけ表示」の単位になる) */
private const val STREAM_BATCH_SIZE = 50

class ImageRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 起動時のデフォルト表示用: 端末の「Pictures/未整理」フォルダ配下の画像を MediaStore 経由で読み込む。
     * ("Documents"はSAF/一部端末のプライバシー制限で直接アクセスできないため変更)
     *
     * タイムスタンプ新しい順(DATE_MODIFIED DESC)でクエリし、STREAM_BATCH_SIZE件ずつ
     * Flowで発行する。呼び出し側は最初の発行分だけですぐに一覧を表示できる。
     */
    fun loadDefaultFolderStreaming(): Flow<List<ImageItem>> {
        val relativePath = "${Environment.DIRECTORY_PICTURES}/未整理%"
        return queryImagesStreaming(relativePathLike = relativePath)
    }

    /** ユーザーがフォルダ選択(SAF)した任意のフォルダから画像を読み込む(段階読込) */
    fun loadFromTreeStreaming(treeUri: Uri): Flow<List<ImageItem>> = flow {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
        if (treeDoc == null) {
            emit(emptyList())
            return@flow
        }
        val children = treeDoc.listFiles()
            .filter { doc -> doc.isFile && (doc.type ?: "").let { t -> IMAGE_MIME_PREFIXES.any { t.startsWith(it) } } }
            .sortedByDescending { it.lastModified() }

        val accumulated = mutableListOf<ImageItem>()
        children.chunked(STREAM_BATCH_SIZE).forEach { chunk ->
            val items = chunk.map { doc ->
                val uri = doc.uri
                val lastModifiedSec = doc.lastModified() / 1000L
                ImageItem(
                    id = uri.toString().hashCode().toLong() and 0x7FFFFFFFL,
                    uri = uri,
                    displayName = doc.name ?: "unknown",
                    sizeBytes = doc.length(),
                    dateModifiedEpochSec = lastModifiedSec,
                    dateAddedEpochSec = lastModifiedSec,
                    dateTakenEpochMillis = null, // 必要になるまでEXIFは読まない(遅延)
                    mimeType = doc.type ?: "image/*",
                    width = 0,
                    height = 0
                )
            }
            accumulated += items
            emit(accumulated.toList())
        }
    }.flowOn(Dispatchers.IO)

    private fun queryImagesStreaming(relativePathLike: String): Flow<List<ImageItem>> = flow {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(relativePathLike)
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val accumulated = mutableListOf<ImageItem>()
        var sinceLastEmit = 0

        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val dateAddCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val name = cursor.getString(nameCol) ?: continue
                accumulated += ImageItem(
                    id = id,
                    uri = uri,
                    displayName = name,
                    sizeBytes = cursor.getLong(sizeCol),
                    dateModifiedEpochSec = cursor.getLong(dateModCol),
                    dateAddedEpochSec = cursor.getLong(dateAddCol),
                    dateTakenEpochMillis = null, // 必要になるまでEXIFは読まない(遅延、大量枚数での初期読込を高速化)
                    mimeType = cursor.getString(mimeCol) ?: "image/*",
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol)
                )
                sinceLastEmit++
                if (sinceLastEmit >= STREAM_BATCH_SIZE) {
                    emit(accumulated.toList())
                    sinceLastEmit = 0
                }
            }
        }
        // 端数分(最後のバッチ未満の残り)を発行
        emit(accumulated.toList())
    }.flowOn(Dispatchers.IO)

    /**
     * 撮影日ソート用に、EXIFの撮影日時をまだ読んでいない画像だけ遅延で読み込む。
     * (一覧の初期表示を高速にするため、これは「撮影日ソート」が選ばれた時だけ呼ぶ)
     */
    suspend fun ensureDateTaken(images: List<ImageItem>): List<ImageItem> = withContext(Dispatchers.IO) {
        val targets = images.filter { it.dateTakenEpochMillis == null }
        targets.chunked(32).forEach { chunk ->
            chunk.map { item ->
                async { item.dateTakenEpochMillis = readExifDateTaken(item.uri) }
            }.awaitAll()
        }
        images
    }

    /** EXIFの撮影日時を読み取る(取得できなければ null → フォールバックは effectiveTakenEpochMillis) */
    private fun readExifDateTaken(uri: Uri): Long? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                dateStr?.let { parseExifDate(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseExifDate(raw: String): Long? {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            fmt.parse(raw)?.time
        } catch (e: Exception) {
            null
        }
    }

    /** 知覚ハッシュを計算して ImageItem に設定する(グループ化前に呼ぶ) */
    suspend fun computeHashes(images: List<ImageItem>): List<ImageItem> = withContext(Dispatchers.Default) {
        images.map { item ->
            if (item.perceptualHash == null) {
                item.perceptualHash = PerceptualHash.compute(resolver, item.uri)
            }
            item
        }
    }
}
