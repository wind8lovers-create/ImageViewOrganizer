package com.hazuki.imageorganizer.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hazuki.imageorganizer.data.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 移動先固定フォルダ名 */
private const val MOVED_FOLDER_NAME = "_Moved_"

class FileOperations(private val context: Context) {

    /**
     * 選択された画像を Download/_Moved_ フォルダへ移動する(移動先指定なし・固定)。
     * MediaStoreのRELATIVE_PATHを書き換えることで、コピー→削除ではなく実質的な移動として扱う。
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

    /** 選択された画像を削除する(MediaStore経由) */
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
}
