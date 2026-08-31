package com.hazuki.imageorganizer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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

/** 段階読込の進捗。総数が先にわかる場合(SAF一括クエリ・ローカルディレクトリ)は totalCount に設定する。 */
data class LoadProgress(val images: List<ImageItem>, val totalCount: Int)

class ImageRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 起動時のデフォルト表示用: 端末の「Download/未整理」フォルダ配下の画像を MediaStore 経由で読み込む。
     * ("Documents"はSAF/一部端末のプライバシー制限で直接アクセスできないため変更)
     *
     * タイムスタンプ新しい順(DATE_MODIFIED DESC)でクエリし、STREAM_BATCH_SIZE件ずつ
     * Flowで発行する。呼び出し側は最初の発行分だけですぐに一覧を表示できる。
     */
    fun loadDefaultFolderStreaming(): Flow<LoadProgress> {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/未整理%"
        return queryImagesStreaming(relativePathLike = relativePath)
    }

    /**
     * ユーザーがフォルダ選択(SAF)した任意のフォルダから画像を読み込む(段階読込)。
     *
     * 【重要】DocumentFile.listFiles() が返す各 DocumentFile はメタ情報を保持しておらず、
     * .isFile / .type / .lastModified() / .length() / .name を呼ぶたびに個別に
     * ContentResolver.query() が発生する(いわゆる N+1 問題)。数千枚規模のフォルダでは
     * これがファイル1件につき6〜7回のIPC通信となり、致命的に遅くなる
     * (グループ0件・プリセット無反応に見えていたのもこれが根本原因: 一覧がそもそも埋まっていなかった)。
     *
     * そのため DocumentsContract の低レベルAPIを使い、子ドキュメント全件のメタ情報を
     * 1回の ContentResolver.query() でまとめて取得する(MediaStoreクエリと同じ発想)。
     */
    fun loadFromTreeStreaming(treeUri: Uri): Flow<LoadProgress> = flow {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            emit(LoadProgress(emptyList(), 0))
            return@flow
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        data class RawEntry(val documentId: String, val name: String, val size: Long, val lastModified: Long, val mime: String)

        val rawEntries = mutableListOf<RawEntry>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeCol) ?: ""
                    if (IMAGE_MIME_PREFIXES.any { mime.startsWith(it) }) {
                        rawEntries += RawEntry(
                            documentId = cursor.getString(idCol),
                            name = cursor.getString(nameCol) ?: "unknown",
                            size = cursor.getLong(sizeCol),
                            lastModified = cursor.getLong(dateCol),
                            mime = mime
                        )
                    }
                }
            }
        } catch (e: Exception) {
            emit(LoadProgress(emptyList(), 0))
            return@flow
        }

        val sorted = rawEntries.sortedByDescending { it.lastModified }
        // クエリ完了時点でフォルダ内の総数(画像のみ)が既にわかっているので、進捗表示に使う
        val total = sorted.size

        val accumulated = mutableListOf<ImageItem>()
        sorted.chunked(STREAM_BATCH_SIZE).forEach { chunk ->
            val items = chunk.map { entry ->
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
                val lastModifiedSec = entry.lastModified / 1000L
                ImageItem(
                    id = uri.toString().hashCode().toLong() and 0x7FFFFFFFL,
                    uri = uri,
                    displayName = entry.name,
                    sizeBytes = entry.size,
                    dateModifiedEpochSec = lastModifiedSec,
                    dateAddedEpochSec = lastModifiedSec,
                    dateTakenEpochMillis = null, // 必要になるまでEXIFは読まない(遅延)
                    mimeType = entry.mime,
                    width = 0,
                    height = 0
                )
            }
            accumulated += items
            emit(LoadProgress(accumulated.toList(), total))
        }
        if (sorted.isEmpty()) emit(LoadProgress(emptyList(), 0))
    }.flowOn(Dispatchers.IO)

    private fun queryImagesStreaming(relativePathLike: String): Flow<LoadProgress> = flow {
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
            // cursor.count は全件走査せずに取得できるので、これを「フォルダの総数」として先に使う
            val total = cursor.count
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
                    emit(LoadProgress(accumulated.toList(), total))
                    sinceLastEmit = 0
                }
            }
            // 端数分(最後のバッチ未満の残り)を発行
            emit(LoadProgress(accumulated.toList(), total))
        } ?: emit(LoadProgress(emptyList(), 0))
    }.flowOn(Dispatchers.IO)

    /**
     * ZIP書庫を app のキャッシュ領域に展開し、展開先ディレクトリを返す。
     * (Android 12以降、任意フォルダのZIP中身へ直接アクセスする標準手段が無いため、
     *  一度アプリ内キャッシュへ取り出してから通常のフォルダと同じように閲覧する方式にしている)
     */
    suspend fun extractZipToCache(zipUri: Uri): java.io.File = withContext(Dispatchers.IO) {
        val extractDir = java.io.File(context.cacheDir, "zip_extract_${System.currentTimeMillis()}")
        extractDir.mkdirs()
        resolver.openInputStream(zipUri)?.use { input ->
            java.util.zip.ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val type = android.webkit.MimeTypeMap.getFileExtensionFromUrl(entry.name)
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(type)
                        if (mime == null || mime.startsWith("image/")) {
                            val outFile = java.io.File(extractDir, java.io.File(entry.name).name)
                            outFile.outputStream().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        extractDir
    }

    /** アプリのキャッシュ領域など、通常のjava.io.Fileでアクセスできるローカルディレクトリから段階読込する */
    fun loadFromLocalDirectoryStreaming(dir: java.io.File): Flow<LoadProgress> = flow {
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && (android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(it.extension.lowercase()) ?: "").startsWith("image/") }
            .sortedByDescending { it.lastModified() }
        val total = files.size

        val accumulated = mutableListOf<ImageItem>()
        files.chunked(STREAM_BATCH_SIZE).forEach { chunk ->
            val items = chunk.map { file ->
                val uri = Uri.fromFile(file)
                val lastModifiedSec = file.lastModified() / 1000L
                ImageItem(
                    id = uri.toString().hashCode().toLong() and 0x7FFFFFFFL,
                    uri = uri,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    dateModifiedEpochSec = lastModifiedSec,
                    dateAddedEpochSec = lastModifiedSec,
                    dateTakenEpochMillis = null,
                    mimeType = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/*",
                    width = 0,
                    height = 0
                )
            }
            accumulated += items
            emit(LoadProgress(accumulated.toList(), total))
        }
        if (files.isEmpty()) emit(LoadProgress(emptyList(), 0))
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

    /**
     * 知覚ハッシュ + 彩度/明度プロファイルを計算して ImageItem に設定する(グループ化前に呼ぶ)。
     * この処理はCPU計算そのものより「SAF経由でファイルを開く」IO待ちが支配的なため、
     * CPU用のDispatchers.Defaultではなく、IO待ちに向いたDispatchers.IOで並列実行する
     * (Defaultのままだと全CPUコアを使い切ろうとして、メイン(UI)スレッドの描画を圧迫し、
     *  結果的に画面消灯防止(keepScreenOn)の反映が遅れる/カクつく原因になり得るため)。
     * 1枚ずつ直列処理だと2500枚規模で著しく遅くなるため、chunked+async/awaitAllで並列化している
     * (ensureDateTakenと同じ方式)。既にハッシュ計算済みの画像はスキップされる。
     */
    /**
     * 知覚ハッシュ + 彩度/明度プロファイル + 代表色パレット(拡張機能用)を計算して ImageItem に設定する。
     * この処理はCPU計算そのものより「SAF経由でファイルを開く」IO待ちが支配的なため、
     * CPU用のDispatchers.Defaultではなく、IO待ちに向いたDispatchers.IOで並列実行する
     * (Defaultのままだと全CPUコアを使い切ろうとして、メイン(UI)スレッドの描画を圧迫し、
     *  結果的に画面消灯防止(keepScreenOn)の反映が遅れる/カクつく原因になり得るため)。
     * 1枚ずつ直列処理だと2500枚規模で著しく遅くなるため、chunked+async/awaitAllで並列化している
     * (ensureDateTakenと同じ方式)。既に計算済みの画像(perceptualHashが設定済み)はスキップされる。
     *
     * @param onProgress 計算進捗コールバック (done: 完了枚数, total: 対象枚数)
     */
    suspend fun computeHashes(
        images: List<ImageItem>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<ImageItem> = withContext(Dispatchers.IO) {
        val targets = images.filter { it.perceptualHash == null }
        val total = targets.size
        if (total == 0) {
            // すでに全件計算済みの場合は完了状態を通知
            onProgress?.invoke(images.size, images.size)
            return@withContext images
        }

        // 開始時の進捗通知 (0 / total)
        onProgress?.invoke(0, total)

        var completedCount = 0
        targets.chunked(24).forEach { chunk ->
            chunk.map { item ->
                async {
                    val result = PerceptualHash.analyze(resolver, item.uri)
                    if (result != null) {
                        item.perceptualHash = result.hash
                        item.avgSaturation = result.color.avgSaturation
                        item.avgBrightness = result.color.avgBrightness
                        item.colorPalette = result.palette
                        // 既定フォルダ(MediaStore)は読込時点で幅高さ判明済みなので上書きしない。
                        // SAF/ZIP展開フォルダは読込時点では 0 のままなので、ここで実サイズを埋める。
                        if (item.width <= 0 || item.height <= 0) {
                            item.width = result.originalWidth
                            item.height = result.originalHeight
                        }
                    }
                }
            }.awaitAll()

            // 1チャンク(24枚)完了ごとに進捗を通知
            completedCount += chunk.size
            onProgress?.invoke(completedCount.coerceAtMost(total), total)
        }
        images
    }

    /** 履歴に残っているフォルダが、まだ実際にアクセス可能か確認する(移動・削除済みなら false) */
    suspend fun isTreeAccessible(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** 履歴に残っているZIP書庫が、まだ実際にアクセス可能か確認する(移動・削除済みなら false) */
    suspend fun isZipAccessible(zipUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(zipUri)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
