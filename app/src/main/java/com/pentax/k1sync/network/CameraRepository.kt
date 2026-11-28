package com.pentax.k1sync.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pentax.k1sync.cache.AppPreferencesStore
import com.pentax.k1sync.cache.DownloadHistoryStore
import com.pentax.k1sync.cache.FavoriteStore
import com.pentax.k1sync.cache.PreviewMemoryCache
import com.pentax.k1sync.cache.ThumbnailDiskCache
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.PhotoExif
import com.pentax.k1sync.model.ShootingParams
import com.pentax.k1sync.model.StorageSlot
import com.pentax.k1sync.util.BitmapOrientationUtil
import com.pentax.k1sync.util.PentaxExifReader
import kotlinx.coroutines.delay

class CameraRepository(
    val wifiBinder: WifiNetworkBinder,
    val apiClient: PentaxK1ApiClient,
    val mockRepo: MockPentaxK1Repository,
    val thumbCache: ThumbnailDiskCache,
    val previewCache: PreviewMemoryCache,
    val historyStore: DownloadHistoryStore,
    val favoriteStore: FavoriteStore,
    val prefsStore: AppPreferencesStore
) {
    @Volatile
    var isMockMode: Boolean = false

    suspend fun connectAndFetchStatus(fastProbe: Boolean = false): Result<Pair<CameraInfo, ShootingParams>> {
        return if (isMockMode) {
            delay(150)
            Result.success(mockRepo.getCameraInfo() to mockRepo.getParams())
        } else {
            apiClient.pingAndFetchProps(fastProbe = fastProbe)
        }
    }

    suspend fun listAllPhotos(
        silentLog: Boolean = false,
        fallbackBySlot: Map<StorageSlot, List<CameraPhoto>> = emptyMap()
    ): Result<List<CameraPhoto>> {
        val sd1Res = listPhotos(StorageSlot.SD1, silentLog = silentLog)
        val sd2Res = listPhotos(StorageSlot.SD2, silentLog = silentLog)
        if (sd1Res.isFailure && sd2Res.isFailure) {
            return sd1Res
        }
        val sd1List = sd1Res.getOrElse { fallbackBySlot[StorageSlot.SD1].orEmpty() }
        val sd2List = sd2Res.getOrElse { fallbackBySlot[StorageSlot.SD2].orEmpty() }
        val combined = (sd1List + sd2List).sortedByDescending { it.sortKey }
        return Result.success(combined)
    }

    suspend fun listPhotos(slot: StorageSlot, silentLog: Boolean = false): Result<List<CameraPhoto>> {
        val downloaded = historyStore.getDownloadedIds()
        val starred = favoriteStore.getStarredIds()
        val rawResult = if (isMockMode) {
            delay(if (silentLog) 40 else 120)
            Result.success(mockRepo.listPhotos(slot))
        } else {
            apiClient.listPhotos(slot, silentLog = silentLog)
        }
        return rawResult.map { list ->
            list.map { photo ->
                photo.copy(
                    isDownloaded = downloaded.contains(photo.uniqueId),
                    isStarred = starred.contains(photo.uniqueId)
                )
            }
        }
    }

    suspend fun loadThumbnail(photo: CameraPhoto): Bitmap? {
        val cacheKey = "${if (isMockMode) "mock" else "real"}_${photo.uniqueId}_thumb"
        thumbCache.get(cacheKey)?.let { return it }

        val bytes = if (isMockMode) {
            mockRepo.renderMockPhotoBytes(photo, 360, 240)
        } else {
            apiClient.downloadPhotoBytes(photo, "thumb").getOrNull()
        } ?: return null

        return thumbCache.put(cacheKey, bytes)
    }

    fun getCachedPreview(photo: CameraPhoto): Bitmap? {
        val key = "${if (isMockMode) "mock" else "real"}_${photo.uniqueId}_view"
        return previewCache.get(key)
    }

    suspend fun loadPreviewWithExif(photo: CameraPhoto): Result<Pair<Bitmap, PhotoExif?>> {
        val key = "${if (isMockMode) "mock" else "real"}_${photo.uniqueId}_view"
        previewCache.get(key)?.let { cached ->
            return Result.success(cached to photo.exif)
        }

        if (isMockMode) {
            delay(90)
            val bytes = mockRepo.renderMockPhotoBytes(photo, 1620, 1080)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Result.failure(IllegalStateException("解码失败"))
            previewCache.put(key, bmp)
            return Result.success(bmp to photo.exif)
        }

        return apiClient.downloadPhotoBytes(photo, "view").mapCatching { data ->
            val bmp = BitmapOrientationUtil.decodeOrientedBitmap(data) ?: error("无法解码图像数据")
            previewCache.put(key, bmp)
            val parsed = PentaxExifReader.parse(data)
            bmp to parsed
        }
    }

    suspend fun fetchPhotoExif(photo: CameraPhoto): PhotoExif {
        if (isMockMode) return photo.exif ?: PhotoExif(cameraModel = "PENTAX K-1")
        return apiClient.fetchPhotoInfo(photo).getOrElse {
            photo.exif ?: PhotoExif(cameraModel = "PENTAX K-1")
        }
    }

    suspend fun downloadPhotoBytes(
        photo: CameraPhoto,
        quality: DownloadQuality,
        onProgress: (Long, Long) -> Unit
    ): Result<ByteArray> {
        return if (isMockMode) {
            val total = when (quality) {
                DownloadQuality.VIEW -> 800_000L
                DownloadQuality.FULL_JPG -> 12_000_000L
                DownloadQuality.RAW -> 44_000_000L
            }
            for (step in 1..6) {
                delay(90)
                onProgress((total * step) / 6, total)
            }
            val w = if (quality == DownloadQuality.VIEW) 1620 else 2400
            val h = if (quality == DownloadQuality.VIEW) 1080 else 1600
            Result.success(mockRepo.renderMockPhotoBytes(photo, w, h))
        } else {
            val sizeParam = if (quality == DownloadQuality.VIEW) "view" else "full"
            apiClient.downloadPhotoBytes(photo, sizeParam, onProgress)
        }
    }

    suspend fun updateParam(key: String, value: String): Result<ShootingParams?> {
        return if (isMockMode) Result.success(mockRepo.setParam(key, value))
        else apiClient.updateCameraParam(key, value).map { null }
    }

    suspend fun triggerAutoFocus(xPercent: Int?, yPercent: Int?): Result<Boolean> =
        if (isMockMode) { delay(220); Result.success(xPercent != null && yPercent != null) } else apiClient.triggerAutoFocus(xPercent, yPercent)

    suspend fun triggerShutter(afEnabled: Boolean): Result<CameraPhoto?> =
        if (isMockMode) { delay(160); Result.success(mockRepo.shootNewPhoto()) }
        else apiClient.triggerShutter(afEnabled).map { null }

    suspend fun startBulb(): Result<Unit> =
        if (isMockMode) Result.success(Unit) else apiClient.startBulbExposure()

    suspend fun finishBulb(): Result<Unit> =
        if (isMockMode) Result.success(Unit) else apiClient.finishBulbExposure()

    suspend fun syncPhoneTime(): Result<String> =
        if (isMockMode) Result.success("2026-11-22T19:25:00 (Mock)") else apiClient.syncPhoneTimeToCamera()
}
