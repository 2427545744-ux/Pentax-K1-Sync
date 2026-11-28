package com.pentax.k1sync.cache

import android.content.Context

class FavoriteStore(context: Context) {

    private val prefs = context.getSharedPreferences("k1_sync_favorites_v4", Context.MODE_PRIVATE)
    private val keySet = "starred_photo_ids"

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
}
