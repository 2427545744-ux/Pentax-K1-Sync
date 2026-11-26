package com.pentax.k1sync.cache

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Keeps recently viewed & adjacent preloaded full-screen preview bitmaps in memory
 * so horizontal swiping in PhotoDetailOverlay feels instantaneous.
 */
class PreviewMemoryCache {
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 5).coerceIn(16 * 1024, 64 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() { cache.evictAll() }
}
