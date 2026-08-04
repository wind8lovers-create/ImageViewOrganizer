package com.hazuki.imageorganizer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.disk.DiskCache

/**
 * サムネイル一覧のスクロールを軽くするためのCoil設定。
 * メモリキャッシュを多めに確保しておくことで、一度表示した画像は
 * スクロールで戻ってきた際に再デコードなしで即表示される(キャッシュ的な先読み効果)。
 */
class OrganizerApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.35) // 利用可能メモリの35%までサムネイルキャッシュに使う
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_thumbnail_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
