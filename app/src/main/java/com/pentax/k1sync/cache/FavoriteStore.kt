package com.pentax.k1sync.cache

import android.content.Context

class FavoriteStore(context: Context) {

    private val prefs = context.getSharedPreferences("k1_sync_favorites_v4", Context.MODE_PRIVATE)
    private val keySet = "starred_photo_ids"
    private val keyRejectedSet = "rejected_photo_ids"

    fun getStarredIds(): Set<String> = prefs.getStringSet(keySet, emptySet())?.toSet() ?: emptySet()

    fun setStarredIds(ids: Set<String>) {
        prefs.edit().putStringSet(keySet, ids.toSet()).apply()
    }

    fun toggleStar(uniqueId: String): Boolean {
        val updated = getStarredIds().toMutableSet()
        val nowStarred = if (updated.contains(uniqueId)) {
            updated.remove(uniqueId)
            false
        } else {
            updated.add(uniqueId)
            true
        }
        prefs.edit().putStringSet(keySet, updated).apply()
        return nowStarred
    }

    fun removeStarred(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val updated = getStarredIds().toMutableSet()
        if (updated.removeAll(ids.toSet())) {
            prefs.edit().putStringSet(keySet, updated).apply()
        }
    }

    fun getRejectedIds(): Set<String> = prefs.getStringSet(keyRejectedSet, emptySet())?.toSet() ?: emptySet()

    fun toggleReject(ids: Collection<String>): Boolean {
        val updated = getRejectedIds().toMutableSet()
        val anyCurrentlyRejected = ids.any { updated.contains(it) }
        val nowRejected = if (anyCurrentlyRejected) {
            updated.removeAll(ids.toSet())
            false
        } else {
            updated.addAll(ids)
            true
        }
        prefs.edit().putStringSet(keyRejectedSet, updated).apply()
        return nowRejected
    }

    fun addRejected(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val updated = getRejectedIds().toMutableSet()
        updated.addAll(ids)
        prefs.edit().putStringSet(keyRejectedSet, updated).apply()
    }

    fun removeRejected(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val updated = getRejectedIds().toMutableSet()
        if (updated.removeAll(ids.toSet())) {
            prefs.edit().putStringSet(keyRejectedSet, updated).apply()
        }
    }

    fun clearAllRejected() {
        prefs.edit().remove(keyRejectedSet).apply()
    }
}
