package com.pentax.k1sync.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ThumbnailDiskCache(context: Context) {

    private val cacheDir = File(context.cacheDir, "k1_thumbs").apply { mkdirs() }

    private val memoryCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8).coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun get(key: String): Bitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(key)?.let { return@withContext it }
        val file = File(cacheDir, "${hashKey(key)}.jpg")
        if (!file.exists()) return@withContext null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
        if (bmp != null) memoryCache.put(key, bmp)
        bmp
    }

    private var writeCountSinceTrim = 0

    suspend fun put(key: String, bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
        memoryCache.put(key, bmp)
        try {
            File(cacheDir, "${hashKey(key)}.jpg").writeBytes(bytes)
            if (++writeCountSinceTrim >= 30) {
                writeCountSinceTrim = 0
                trimDiskCacheIfNeeded()
            }
        } catch (_: Exception) {
        }
        bmp
    }

    private fun trimDiskCacheIfNeeded(maxBytes: Long = 120L * 1024L * 1024L, maxFiles: Int = 1500) {
        val files = cacheDir.listFiles() ?: return
        val totalSize = files.sumOf { it.length() }
        if (files.size <= maxFiles && totalSize <= maxBytes) return
        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        val dropCount = (sortedOldestFirst.size / 4).coerceAtLeast(1)
        var remainingBytes = totalSize
        var remainingFiles = sortedOldestFirst.size
        for (idx in sortedOldestFirst.indices) {
            if (idx >= dropCount && remainingFiles <= maxFiles && remainingBytes <= maxBytes) break
            val f = sortedOldestFirst[idx]
            val len = f.length()
            if (f.delete()) {
                remainingBytes -= len
                remainingFiles--
            }
        }
    }

    fun clearAll() {
        memoryCache.evictAll()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun cacheSizeMb(): Float {
        val totalBytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        return totalBytes / (1024f * 1024f)
    }
}
