package com.pentax.k1sync.viewmodel

import com.pentax.k1sync.model.CameraPhoto

data class SyncMergeResult(
    val mergedPhotos: List<CameraPhoto>,
    val newlyCaptured: List<CameraPhoto>,
    val listChanged: Boolean
)

/**
 * Thread-safe coordinator for dual-card SD1/SD2 chronological merging, new-shot baseline diffing,
 * and RAW+JPG deduplication for Tether auto-download.
 */
class CameraSyncCoordinator {

    private val lock = Any()
    private val knownPhotoIds = mutableSetOf<String>()
    private val tetherDispatchedBaseKeys = mutableSetOf<String>()
    @Volatile
    private var baselineInitialized = false

    fun reset() {
        synchronized(lock) {
            knownPhotoIds.clear()
            tetherDispatchedBaseKeys.clear()
            baselineInitialized = false
        }
    }

    fun snapshotBaselineIfNeeded(currentPhotos: List<CameraPhoto>) {
        synchronized(lock) {
            if (!baselineInitialized && currentPhotos.isNotEmpty()) {
                currentPhotos.forEach { knownPhotoIds.add(it.uniqueId) }
                baselineInitialized = true
            }
            tetherDispatchedBaseKeys.clear()
        }
    }

    fun mergeFreshList(currentPhotos: List<CameraPhoto>, freshList: List<CameraPhoto>): SyncMergeResult {
        val existingById = currentPhotos.associateBy { it.uniqueId }
        val mergedFresh = freshList.map { p ->
            val old = existingById[p.uniqueId]
            if (old != null) {
                p.copy(
                    exif = old.exif ?: p.exif,
                    sortKey = if (old.exif?.dateTime?.isNotBlank() == true) old.sortKey else p.sortKey,
                    dateGroup = if (old.exif?.dateTime?.isNotBlank() == true) old.dateGroup else p.dateGroup
                )
            } else {
                p
            }
        }.sortedByDescending { it.sortKey }

        val newlyCaptured: List<CameraPhoto>
        synchronized(lock) {
            if (!baselineInitialized) {
                knownPhotoIds.clear()
                mergedFresh.forEach { knownPhotoIds.add(it.uniqueId) }
                baselineInitialized = true
                newlyCaptured = emptyList()
            } else {
                newlyCaptured = mergedFresh.filter { !knownPhotoIds.contains(it.uniqueId) }
                newlyCaptured.forEach { knownPhotoIds.add(it.uniqueId) }
            }
        }

        val changed = mergedFresh.size != currentPhotos.size || newlyCaptured.isNotEmpty() || currentPhotos.isEmpty()
        return SyncMergeResult(mergedFresh, newlyCaptured, changed)
    }

    fun selectDeduplicatedTetherTargets(newPhotos: List<CameraPhoto>): List<CameraPhoto> {
        val grouped = newPhotos.groupBy { "${it.dateGroup}/${it.baseName}" }
        val toDownload = mutableListOf<CameraPhoto>()
        synchronized(lock) {
            for ((baseKey, group) in grouped) {
                if (tetherDispatchedBaseKeys.contains(baseKey)) continue
                tetherDispatchedBaseKeys.add(baseKey)
                val preferred = group.firstOrNull { !it.isRaw } ?: group.first()
                toDownload += preferred
            }
        }
        return toDownload
    }
}
