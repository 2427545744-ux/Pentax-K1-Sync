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
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        if (bmp != null) memoryCache.put(key, bmp)
        bmp
    }

    suspend fun put(key: String, bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        memoryCache.put(key, bmp)
        try {
            File(cacheDir, "${hashKey(key)}.jpg").writeBytes(bytes)
        } catch (_: Exception) {
        }
        bmp
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
