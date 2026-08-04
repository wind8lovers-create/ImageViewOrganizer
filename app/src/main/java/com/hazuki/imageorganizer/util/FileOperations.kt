package com.hazuki.imageorganizer.util

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.hazuki.imageorganizer.data.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 移動先固定フォルダ名 */
private const val MOVED_FOLDER_NAME = "_Moved_"

class FileOperations(private val context: Context) {

    /**
     * 他アプリが作成した画像を移動/リネームする前に必要な「書き込み許可」を、
     * まとめてシステムのダイアログでユーザーに求めるためのIntentSenderを作る。
     * (Android 11以降、自分のアプリが作っていないMediaStore項目の変更にはこの同意が必須)
     */
    fun createWriteRequest(images: List<ImageItem>): IntentSender {
        val uris = images.map { it.uri }
        return MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
    }

    /** 削除の前にまとめて同意を求めるIntentSenderを作る。許可されると削除はシステム側で実行される。 */
    fun createDeleteRequest(images: List<ImageItem>): IntentSender {
        val uris = images.map { it.uri }
        return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }

    /**
     * 選択された画像を Download/_Moved_ フォルダへ移動する(移動先指定なし・固定)。
     * 呼び出し前に createWriteRequest() での許可が得られている前提。
     */
    suspend fun moveToMovedFolder(images: List<ImageItem>): List<ImageItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val targetRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/$MOVED_FOLDER_NAME/"

        val moved = mutableListOf<ImageItem>()
        for (item in images) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, targetRelativePath)
                    put(MediaStore.Images.Media.DISPLAY_NAME, resolveNonCollidingName(item.displayName, targetRelativePath))
                }
                val rows = resolver.update(item.uri, values, null, null)
                if (rows > 0) moved += item
            } catch (e: Exception) {
                // 個別の失敗はスキップし、他のファイルの移動は継続する
            }
        }
        moved
    }

    /**
     * 選択された画像を削除する。
     * Android 11以降、他アプリ作成の項目は事前に createDeleteRequest() でユーザーの同意を得ておく必要がある。
     * 同意済みであれば、ここでの delete() は成功する(未同意のまま単体削除するとRecoverableSecurityExceptionになる)。
     */
    suspend fun deleteImages(images: List<ImageItem>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var count = 0
        for (item in images) {
            try {
                val rows = resolver.delete(item.uri, null, null)
                if (rows > 0) count++
            } catch (e: Exception) {
                // 個別失敗はスキップ
            }
        }
        count
    }

    /**
     * 表示(ソート)順のリストに対して、REN命名規則で連番リネームする。
     * 呼び出し前に createWriteRequest() での許可が得られている前提。
     * @param orderedImages ソート後の並び順(この順で01, 02, ...と振る)
     */
    suspend fun renameSequentially(orderedImages: List<ImageItem>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val prefix = RenameUtil.buildTimestampPrefix()
        var count = 0
        orderedImages.forEachIndexed { index, item ->
            try {
                val newName = RenameUtil.buildFileName(prefix, index, item.extension)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, newName)
                }
                val rows = resolver.update(item.uri, values, null, null)
                if (rows > 0) count++
            } catch (e: Exception) {
                // 個別失敗はスキップ、通しナンバーは元の並び順indexで振り続ける
            }
        }
        count
    }

    /**
     * 選択画像をZIPアーカイブ化し、Download/ 直下に保存する。
     * @return 生成したZIPファイルのUri。失敗時はnull。
     */
    suspend fun zipImages(images: List<ImageItem>, zipFileName: String): Uri? = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext null
        val resolver = context.contentResolver

        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val zipUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null

            resolver.openOutputStream(zipUri)?.use { out ->
                ZipOutputStream(out).use { zos ->
                    for (item in images) {
                        try {
                            resolver.openInputStream(item.uri)?.use { input ->
                                zos.putNextEntry(ZipEntry(item.displayName))
                                input.copyTo(zos)
                                zos.closeEntry()
                            }
                        } catch (e: Exception) {
                            // 1枚失敗しても残りは続行
                        }
                    }
                }
            }
            zipUri
        } catch (e: Exception) {
            null
        }
    }

    /** 同名ファイルが移動先に既にある場合は連番を付けて衝突を回避する(簡易実装) */
    private fun resolveNonCollidingName(originalName: String, @Suppress("UNUSED_PARAMETER") targetRelativePath: String): String {
        // MediaStore側で重複時は自動的に "(1)" 等が付与されるため、基本はそのまま返す。
        // 将来的に厳密な重複チェックが必要になった場合はここを拡張する。
        return originalName
    }

    // ==================================================================
    // SAFフォルダ(ユーザーがフォルダ選択したツリー)向けの操作
    //
    // 【重要】上のMediaStore版(createWriteRequest/createDeleteRequest + resolver.update/delete)は
    // MediaStoreのURI専用のAPIであり、SAFのDocumentsContract系URI(content://.../tree/.../document/...)を
    // 渡すと IllegalArgumentException で即クラッシュする。SAFで選択したフォルダの画像に対しては、
    // 以下のDocumentsContract系のAPIを使う必要がある(こちらは事前の同意ダイアログは不要。
    // フォルダ選択時に取得済みの永続アクセス権限だけで操作できる)。
    // ==================================================================

    /** SAFツリー内に「_Moved_」サブフォルダを探し、無ければ作成してそのUriを返す */
    private fun getOrCreateMovedFolder(resolver: android.content.ContentResolver, treeUri: Uri, parentDocUri: Uri): Uri {
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == MOVED_FOLDER_NAME &&
                    cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                }
            }
        }
        return DocumentsContract.createDocument(
            resolver, parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR, MOVED_FOLDER_NAME
        ) ?: throw java.io.IOException("「$MOVED_FOLDER_NAME」フォルダの作成に失敗しました")
    }

    /** SAFフォルダ内の画像を、同じツリー内の「_Moved_」サブフォルダへ移動する */
    suspend fun moveToMovedFolderSaf(treeUri: Uri, images: List<ImageItem>): List<ImageItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val movedFolderUri = try {
            getOrCreateMovedFolder(resolver, treeUri, parentDocUri)
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        val moved = mutableListOf<ImageItem>()
        for (item in images) {
            try {
                val newUri = DocumentsContract.moveDocument(resolver, item.uri, parentDocUri, movedFolderUri)
                if (newUri != null) moved += item
            } catch (e: Exception) {
                // 個別の失敗はスキップし、他のファイルの移動は継続する
            }
        }
        moved
    }

    /** SAFフォルダ内の画像を削除する(事前の同意ダイアログは不要) */
    suspend fun deleteImagesSaf(images: List<ImageItem>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var count = 0
        for (item in images) {
            try {
                if (DocumentsContract.deleteDocument(resolver, item.uri)) count++
            } catch (e: Exception) {
                // 個別失敗はスキップ
            }
        }
        count
    }

    /** SAFフォルダ内の画像を、表示順で連番リネームする */
    suspend fun renameSequentiallySaf(orderedImages: List<ImageItem>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val prefix = RenameUtil.buildTimestampPrefix()
        var count = 0
        orderedImages.forEachIndexed { index, item ->
            try {
                val newName = RenameUtil.buildFileName(prefix, index, item.extension)
                DocumentsContract.renameDocument(resolver, item.uri, newName)
                count++
            } catch (e: Exception) {
                // 個別失敗はスキップ
            }
        }
        count
    }

    // ==================================================================
    // ローカルディレクトリ(ZIP書庫をアプリキャッシュへ展開したもの)向けの操作
    // これらは通常の java.io.File で直接アクセスできるため、MediaStore/DocumentsContractは使わない。
    // ==================================================================

    suspend fun moveToMovedFolderLocal(images: List<ImageItem>): List<ImageItem> = withContext(Dispatchers.IO) {
        val moved = mutableListOf<ImageItem>()
        for (item in images) {
            try {
                val src = item.uri.path?.let { java.io.File(it) } ?: continue
                val movedDir = java.io.File(src.parentFile, MOVED_FOLDER_NAME).apply { mkdirs() }
                val dest = java.io.File(movedDir, src.name)
                if (src.renameTo(dest)) moved += item
            } catch (e: Exception) {
                // 個別失敗はスキップ
            }
        }
        moved
    }

    suspend fun deleteImagesLocal(images: List<ImageItem>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (item in images) {
            try {
                val f = item.uri.path?.let { java.io.File(it) } ?: continue
                if (f.delete()) count++
            } catch (e: Exception) {
                // 個別失敗はスキップ
            }
        }
        count
    }

    suspend fun renameSequentiallyLocal(orderedImages: List<ImageItem>): Int = withContext(Dispatchers.IO) {
        val prefix = RenameUtil.buildTimestampPrefix()
        var count = 0
        orderedImages.forEachIndexed { index, item ->
            try {
                val src = item.uri.path?.let { java.io.File(it) } ?: return@forEachIndexed
                val newName = RenameUtil.buildFileName(prefix, index, item.extension)
                val dest = java.io.File(src.parentFile, newName)
                if (src.renameTo(dest)) count++
            } catch (e: Exception) {
                // 個別失敗はスキップ
            }
        }
        count
    }
}

