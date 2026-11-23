package com.pentax.k1sync

import android.app.Application
import com.pentax.k1sync.cache.DownloadHistoryStore
import com.pentax.k1sync.cache.ThumbnailDiskCache
import com.pentax.k1sync.network.CameraRepository
import com.pentax.k1sync.network.MockPentaxK1Repository
import com.pentax.k1sync.network.PentaxK1ApiClient
import com.pentax.k1sync.network.WifiNetworkBinder

class K1SyncApp : Application() {
    lateinit var repository: CameraRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val binder = WifiNetworkBinder(this)
        val apiClient = PentaxK1ApiClient(binder)
        val mockRepo = MockPentaxK1Repository()
        val thumbCache = ThumbnailDiskCache(this)
        val historyStore = DownloadHistoryStore(this)
        repository = CameraRepository(binder, apiClient, mockRepo, thumbCache, historyStore)
    }
}
