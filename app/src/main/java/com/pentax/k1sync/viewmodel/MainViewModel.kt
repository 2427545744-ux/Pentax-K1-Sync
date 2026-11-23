package com.pentax.k1sync.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pentax.k1sync.K1SyncApp
import com.pentax.k1sync.image.CropAspectRatio
import com.pentax.k1sync.image.CropStraightenConfig
import com.pentax.k1sync.image.CubeLutEngine
import com.pentax.k1sync.image.ImageCropStraighten
import com.pentax.k1sync.image.LiveViewAnalyzer
import com.pentax.k1sync.image.LutPreset
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.FocusPeakingColor
import com.pentax.k1sync.model.GridOverlayMode
import com.pentax.k1sync.model.HistogramData
import com.pentax.k1sync.model.LiveViewAssistConfig
import com.pentax.k1sync.model.PeakingSensitivity
import com.pentax.k1sync.model.PhotoExif
import com.pentax.k1sync.model.PhotoFormatFilter
import com.pentax.k1sync.model.RemoteShotMode
import com.pentax.k1sync.model.ShootingParams
import com.pentax.k1sync.model.StorageSlot
import com.pentax.k1sync.model.TransferStatus
import com.pentax.k1sync.model.TransferTask
import com.pentax.k1sync.service.TransferForegroundService
import com.pentax.k1sync.spektra.DiffusionFilterFamily
import com.pentax.k1sync.spektra.FilmFormatSpec
import com.pentax.k1sync.spektra.LightLeakKind
import com.pentax.k1sync.spektra.SavedDarkroomRecipe
import com.pentax.k1sync.spektra.SpektraCatalog
import com.pentax.k1sync.spektra.SpektraDarkroomParams
import com.pentax.k1sync.spektra.SpektraFilmStock
import com.pentax.k1sync.spektra.SpektraLatitudeReadout
import com.pentax.k1sync.spektra.SpektraNativeBridge
import com.pentax.k1sync.spektra.SpektraResourceManager
import com.pentax.k1sync.util.ApiDebugLogger
import com.pentax.k1sync.util.ApiLogEntry
import com.pentax.k1sync.util.BitmapOrientationUtil
import com.pentax.k1sync.util.HistogramCalculator
import com.pentax.k1sync.util.MediaStoreSaver
import com.pentax.k1sync.util.PentaxExifReader
import com.pentax.k1sync.util.PhotoShareHelper
import com.pentax.k1sync.watermark.PentaxWatermarkRenderer
import com.pentax.k1sync.watermark.WatermarkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

enum class AppTab(val label: String) {
    GALLERY("图库"),
    REMOTE("遥控"),
    WATERMARK("后期"),
    SYNC("机身")
}

enum class ColorGradeMode(val label: String) {
    CUBE_LUT("3D LUT"),
    SPEKTRA_DARKROOM("Spektra 81波长暗房")
}

data class IntervalState(
    val running: Boolean = false,
    val intervalSec: Int = 5,
    val totalShots: Int = 20,
    val shotsTaken: Int = 0,
    val nextShotCountdown: Int = 0
)

data class BulbState(
    val running: Boolean = false,
    val targetSec: Int = 30,
    val elapsedSec: Int = 0
)

data class BatchWatermarkProgress(
    val current: Int,
    val total: Int
)

data class WatermarkEditorState(
    val sourceBitmap: Bitmap? = null,
    val sourceRawPath: String? = null,
    val isRawSource: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val sourceFileName: String = "PENTAX_K1_Sample.jpg",
    val config: WatermarkConfig = WatermarkConfig(),
    val cropConfig: CropStraightenConfig = CropStraightenConfig(),
    val colorGradeMode: ColorGradeMode = ColorGradeMode.CUBE_LUT,
    val availableLuts: List<LutPreset> = CubeLutEngine.BUILTIN_PRESETS,
    val selectedLutId: String = CubeLutEngine.ID_NONE,
    val lutIntensity: Float = 0.85f,
    val allFilmStocks: List<SpektraFilmStock> = SpektraCatalog.CURATED_FILM_STOCKS,
    val showAllStocksPicker: Boolean = false,
    val spektraParams: SpektraDarkroomParams = SpektraDarkroomParams(),
    val asShotTempKelvin: Float = 5500f,
    val asShotTint: Float = 0f,
    val spektraEngineReady: Boolean = false,
    val spektraStatusText: String = "点击切换至 Spektra 暗房以启动 81 波长 Vulkan 引擎",
    val spektraDeviceName: String = "",
    val spektraError: String? = null,
    val spektraLatitude: SpektraLatitudeReadout? = null,
    val spektraSolving: Boolean = false,
    val isRendering: Boolean = false,
    val isSaving: Boolean = false,
    val isSharing: Boolean = false,
    val fullscreenPreview: Boolean = false,
    val batchProgress: BatchWatermarkProgress? = null
) {
    val activeLutPreset: LutPreset
        get() = availableLuts.firstOrNull { it.id == selectedLutId } ?: CubeLutEngine.BUILTIN_PRESETS.first()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as K1SyncApp).repository

    private val _activeTab = MutableStateFlow(AppTab.GALLERY)
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

    private val _cameraInfo = MutableStateFlow(CameraInfo())
    val cameraInfo: StateFlow<CameraInfo> = _cameraInfo.asStateFlow()

    private val _shootingParams = MutableStateFlow(ShootingParams())
    val shootingParams: StateFlow<ShootingParams> = _shootingParams.asStateFlow()

    private val _isMockMode = MutableStateFlow(false)
    val isMockMode: StateFlow<Boolean> = _isMockMode.asStateFlow()

    val wifiStatus: StateFlow<String> = repo.wifiBinder.statusMessage
    val apiLogEnabled: StateFlow<Boolean> = ApiDebugLogger.enabled
    val apiLogEntries: StateFlow<List<ApiLogEntry>> = ApiDebugLogger.entries

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var userManuallyDisconnected = false

    // --- Gallery State ---
    private val _activeSlot = MutableStateFlow(StorageSlot.SD1)
    val activeSlot: StateFlow<StorageSlot> = _activeSlot.asStateFlow()

    private val _formatFilter = MutableStateFlow(PhotoFormatFilter.ALL)
    val formatFilter: StateFlow<PhotoFormatFilter> = _formatFilter.asStateFlow()

    private val _starredOnlyFilter = MutableStateFlow(false)
    val starredOnlyFilter: StateFlow<Boolean> = _starredOnlyFilter.asStateFlow()

    private val _starredCount = MutableStateFlow(0)
    val starredCount: StateFlow<Int> = _starredCount.asStateFlow()

    private val _galleryColumns = MutableStateFlow(repo.prefsStore.loadGalleryColumns())
    val galleryColumns: StateFlow<Int> = _galleryColumns.asStateFlow()

    private val _pairRawJpg = MutableStateFlow(repo.prefsStore.loadPairRawJpg())
    val pairRawJpg: StateFlow<Boolean> = _pairRawJpg.asStateFlow()

    private val _rawPhotos = MutableStateFlow<List<CameraPhoto>>(emptyList())

    private val _displayPhotos = MutableStateFlow<List<CameraPhoto>>(emptyList())
    val displayPhotos: StateFlow<List<CameraPhoto>> = _displayPhotos.asStateFlow()

    private val _isLoadingPhotos = MutableStateFlow(false)
    val isLoadingPhotos: StateFlow<Boolean> = _isLoadingPhotos.asStateFlow()

    private val _selectedPhotoIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPhotoIds: StateFlow<Set<String>> = _selectedPhotoIds.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    // --- Split-Screen Photo Compare Overlay State ---
    private val _comparePhotos = MutableStateFlow<Pair<CameraPhoto, CameraPhoto>?>(null)
    val comparePhotos: StateFlow<Pair<CameraPhoto, CameraPhoto>?> = _comparePhotos.asStateFlow()

    // --- Full-Screen Photo Detail Overlay State ---
    private val _detailPhotos = MutableStateFlow<List<CameraPhoto>>(emptyList())
    val detailPhotos: StateFlow<List<CameraPhoto>> = _detailPhotos.asStateFlow()

    private val _detailStartIndex = MutableStateFlow(0)
    val detailStartIndex: StateFlow<Int> = _detailStartIndex.asStateFlow()

    private val _detailOpen = MutableStateFlow(false)
    val detailOpen: StateFlow<Boolean> = _detailOpen.asStateFlow()

    private val _previewBitmaps = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val previewBitmaps: StateFlow<Map<String, Bitmap>> = _previewBitmaps.asStateFlow()

    private val _photoExifMap = MutableStateFlow<Map<String, PhotoExif>>(emptyMap())
    val photoExifMap: StateFlow<Map<String, PhotoExif>> = _photoExifMap.asStateFlow()

    private val _photoHistogramMap = MutableStateFlow<Map<String, HistogramData>>(emptyMap())
    val photoHistogramMap: StateFlow<Map<String, HistogramData>> = _photoHistogramMap.asStateFlow()

    private val _loadingPreviewIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingPreviewIds: StateFlow<Set<String>> = _loadingPreviewIds.asStateFlow()

    // --- Transfer Queue State ---
    private val _defaultQuality = MutableStateFlow(DownloadQuality.VIEW)
    val defaultQuality: StateFlow<DownloadQuality> = _defaultQuality.asStateFlow()

    private val _autoWatermarkOnTransfer = MutableStateFlow(false)
    val autoWatermarkOnTransfer: StateFlow<Boolean> = _autoWatermarkOnTransfer.asStateFlow()

    private val _transferTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val transferTasks: StateFlow<List<TransferTask>> = _transferTasks.asStateFlow()

    private var transferWorkerJob: Job? = null

    // --- Remote Control & Live View Assist State ---
    private val _liveViewFrame = MutableStateFlow<Bitmap?>(null)
    val liveViewFrame: StateFlow<Bitmap?> = _liveViewFrame.asStateFlow()

    private val _liveViewActive = MutableStateFlow(false)
    val liveViewActive: StateFlow<Boolean> = _liveViewActive.asStateFlow()

    private val _liveViewError = MutableStateFlow<String?>(null)
    val liveViewError: StateFlow<String?> = _liveViewError.asStateFlow()

    private val _liveAssistConfig = MutableStateFlow(LiveViewAssistConfig())
    val liveAssistConfig: StateFlow<LiveViewAssistConfig> = _liveAssistConfig.asStateFlow()

    private val _manualLensMode = MutableStateFlow(false)
    val manualLensMode: StateFlow<Boolean> = _manualLensMode.asStateFlow()

    private val _tetherEnabled = MutableStateFlow(false)
    val tetherEnabled: StateFlow<Boolean> = _tetherEnabled.asStateFlow()

    private val _gridMode = MutableStateFlow(GridOverlayMode.THIRDS)
    val gridMode: StateFlow<GridOverlayMode> = _gridMode.asStateFlow()

    private val _remoteShotMode = MutableStateFlow(RemoteShotMode.SINGLE)
    val remoteShotMode: StateFlow<RemoteShotMode> = _remoteShotMode.asStateFlow()

    private val _intervalState = MutableStateFlow(IntervalState())
    val intervalState: StateFlow<IntervalState> = _intervalState.asStateFlow()

    private val _bulbState = MutableStateFlow(BulbState())
    val bulbState: StateFlow<BulbState> = _bulbState.asStateFlow()

    private var rawLatestLiveFrame: Bitmap? = null
    private var liveViewJob: Job? = null
    private var liveAnalyzeJob: Job? = null
    private var intervalJob: Job? = null
    private var bulbJob: Job? = null

    // --- Studio & SpektraLab Native Session ---
    private val _watermarkState = MutableStateFlow(
        WatermarkEditorState(
            config = repo.prefsStore.loadWatermarkDefaults(),
            availableLuts = CubeLutEngine.listAllPresets(application),
            selectedLutId = repo.prefsStore.loadSelectedLutId(),
            lutIntensity = repo.prefsStore.loadLutIntensity(),
            allFilmStocks = SpektraCatalog.loadFullCatalog(application)
        )
    )
    val watermarkState: StateFlow<WatermarkEditorState> = _watermarkState.asStateFlow()

    private val _lensPresets = MutableStateFlow(repo.prefsStore.loadLensPresets())
    val lensPresets: StateFlow<List<String>> = _lensPresets.asStateFlow()

    private val _darkroomRecipes = MutableStateFlow(repo.prefsStore.loadDarkroomRecipes())
    val darkroomRecipes: StateFlow<List<SavedDarkroomRecipe>> = _darkroomRecipes.asStateFlow()

    private var watermarkRenderJob: Job? = null
    @Volatile private var spektraSessionHandle: Long = 0L
    @Volatile private var spektraSessionSourceKey: String = ""
    @Volatile private var lastAppliedSpektraParams: SpektraDarkroomParams? = null
    @Volatile private var spektraHasRenderedLive: Boolean = false

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val loadedCatalog = SpektraCatalog.loadFullCatalog(getApplication())
            val savedPrefs = repo.prefsStore.loadWatermarkDefaults()
            val samplePhoto = repo.mockRepo.listPhotos(StorageSlot.SD1).first()
            val bytes = repo.mockRepo.renderMockPhotoBytes(samplePhoto, 1440, 960)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                val cfg = WatermarkConfig.fromExif(samplePhoto.exif ?: PhotoExif(), savedPrefs)
                _watermarkState.update {
                    it.copy(
                        allFilmStocks = loadedCatalog,
                        sourceBitmap = bmp,
                        sourceRawPath = null,
                        isRawSource = false,
                        sourceFileName = samplePhoto.file,
                        config = cfg
                    )
                }
                triggerWatermarkPreview(0L)
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _activeTab.value = tab
        if (tab == AppTab.REMOTE && _cameraInfo.value.connected && !_liveViewActive.value) {
            startLiveView()
        } else if (tab != AppTab.REMOTE && _liveViewActive.value) {
            stopLiveView()
        }
    }

    fun consumeToast() { _toastMessage.value = null }
    fun showToast(msg: String) { _toastMessage.value = msg }

    // ---------------------------------------------------------
    // Protocol Debug Logger & Personal Config Backup / Restore
    // ---------------------------------------------------------
    fun toggleApiLogging(enabled: Boolean) {
        ApiDebugLogger.setEnabled(enabled)
        showToast(if (enabled) "K-1 通信抓包日志已开启（最多保留 30 条）" else "已关闭通信抓包日志")
    }

    fun clearApiLogs() {
        ApiDebugLogger.clear()
        showToast("已清空通信日志")
    }

    fun getApiLogTextForCopy(): String = ApiDebugLogger.formatAllForCopy()

    fun exportConfigBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = repo.prefsStore.exportFullBackupJson(repo.favoriteStore.getStarredIds())
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入目标文件")
                showToast("个人配置备份已导出（含星标、镜头预设与暗房配方）")
            } catch (e: Exception) {
                showToast("导出配置失败: ${e.localizedMessage}")
            }
        }
    }

    fun importConfigBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法读取备份文件")
                repo.prefsStore.importFullBackupJson(text).onSuccess { restoredStars ->
                    repo.favoriteStore.setStarredIds(restoredStars)
                    _galleryColumns.value = repo.prefsStore.loadGalleryColumns()
                    _pairRawJpg.value = repo.prefsStore.loadPairRawJpg()
                    _lensPresets.value = repo.prefsStore.loadLensPresets()
                    _darkroomRecipes.value = repo.prefsStore.loadDarkroomRecipes()
                    val wmDefaults = repo.prefsStore.loadWatermarkDefaults()
                    _watermarkState.update { st ->
                        st.copy(
                            config = st.config.copy(
                                watermarkEnabled = wmDefaults.watermarkEnabled,
                                style = wmDefaults.style,
                                accentColor = wmDefaults.accentColor,
                                signature = wmDefaults.signature,
                                showDateTime = wmDefaults.showDateTime,
                                showLensName = wmDefaults.showLensName,
                                showOverlaySignature = wmDefaults.showOverlaySignature,
                                highQualityExport = wmDefaults.highQualityExport
                            ),
                            selectedLutId = repo.prefsStore.loadSelectedLutId(),
                            lutIntensity = repo.prefsStore.loadLutIntensity()
                        )
                    }
                    _rawPhotos.update { list -> list.map { p -> p.copy(isStarred = restoredStars.contains(p.uniqueId)) } }
                    recomputeDisplayPhotos()
                    triggerWatermarkPreview(0L)
                    showToast("已恢复全部个人配置、镜头预设与暗房配方")
                }.onFailure { err ->
                    showToast("导入配置失败: ${err.localizedMessage}")
                }
            } catch (e: Exception) {
                showToast("读取配置失败: ${e.localizedMessage}")
            }
        }
    }

    // ---------------------------------------------------------
    // Connection, Foreground Auto-Reconnect & Restore 5G
    // ---------------------------------------------------------
    fun onAppForegrounded() {
        if (_isMockMode.value || _cameraInfo.value.connected || _isConnecting.value || userManuallyDisconnected) return
        viewModelScope.launch {
            repo.connectAndFetchStatus(fastProbe = true).onSuccess { (info, params) ->
                _cameraInfo.value = info
                _shootingParams.value = params
                showToast("已自动重新连接 ${info.model}")
                refreshPhotos()
            }
        }
    }

    fun toggleMockMode(enabled: Boolean) {
        stopLiveView()
        stopTether()
        closePhotoDetail()
        closeCompare()
        repo.previewCache.clear()
        _previewBitmaps.value = emptyMap()
        repo.isMockMode = enabled
        _isMockMode.value = enabled
        if (enabled) {
            userManuallyDisconnected = false
            connectCamera()
        } else {
            _cameraInfo.value = CameraInfo(connected = false)
            _rawPhotos.value = emptyList()
            _displayPhotos.value = emptyList()
            _selectedPhotoIds.value = emptySet()
            _starredCount.value = 0
            showToast("已切换为真实 PENTAX K-1 模式 (192.168.0.1)")
        }
    }

    fun smartConnectPentaxWifi() {
        userManuallyDisconnected = false
        if (_isMockMode.value) { connectCamera(); return }
        _isConnecting.value = true
        repo.wifiBinder.requestPentaxHotspot { bound ->
            viewModelScope.launch {
                if (bound) delay(600)
                connectCamera()
            }
        }
    }

    fun bindCurrentWifiAndConnect() {
        userManuallyDisconnected = false
        if (_isMockMode.value) { connectCamera(); return }
        _isConnecting.value = true
        repo.wifiBinder.bindToCurrentWifi {
            viewModelScope.launch {
                delay(400)
                connectCamera()
            }
        }
    }

    fun disconnectAndRestoreMobileNetwork() {
        userManuallyDisconnected = true
        stopLiveView()
        stopTether()
        closePhotoDetail()
        closeCompare()
        if (_isMockMode.value) { _isMockMode.value = false; repo.isMockMode = false }
        repo.wifiBinder.releaseBinding(silent = false)
        _cameraInfo.value = CameraInfo(connected = false)
        _rawPhotos.value = emptyList()
        _displayPhotos.value = emptyList()
        _selectedPhotoIds.value = emptySet()
        showToast("已断开 K-1 路由锁定，手机 4G/5G 网络已恢复")
    }

    fun connectCamera() {
        viewModelScope.launch {
            _isConnecting.value = true
            repo.connectAndFetchStatus()
                .onSuccess { (info, params) ->
                    _cameraInfo.value = info
                    _shootingParams.value = params
                    showToast(if (info.isMock) "已接入虚拟 PENTAX K-1 演示机身" else "已连接 ${info.model}")
                    refreshPhotos()
                }
                .onFailure { err ->
                    _cameraInfo.value = CameraInfo(connected = false)
                    showToast("未检测到 K-1 (192.168.0.1): ${err.localizedMessage ?: "请先连接 PENTAX_xxxx Wi-Fi"}")
                }
            _isConnecting.value = false
        }
    }

    fun syncPhoneTimeToCamera() {
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        viewModelScope.launch {
            repo.syncPhoneTime()
                .onSuccess { showToast("已同步时间至 K-1：$it") }
                .onFailure { showToast("时间同步失败: ${it.localizedMessage}") }
        }
    }

    fun clearThumbnailCache() {
        repo.thumbCache.clearAll()
        repo.previewCache.clear()
        _thumbnails.value = emptyMap()
        _previewBitmaps.value = emptyMap()
        showToast("已清理本地缩略图与预览缓存")
    }

    fun cacheSizeMb(): Float = repo.thumbCache.cacheSizeMb()

    // ---------------------------------------------------------
    // Gallery & Split-Screen Photo Compare
    // ---------------------------------------------------------
    fun cycleGalleryColumns() {
        val next = when (_galleryColumns.value) { 2 -> 3; 3 -> 4; else -> 2 }
        _galleryColumns.value = next
        repo.prefsStore.saveGalleryColumns(next)
    }

    fun togglePairRawJpg() {
        val next = !_pairRawJpg.value
        _pairRawJpg.value = next
        repo.prefsStore.savePairRawJpg(next)
        recomputeDisplayPhotos()
        showToast(if (next) "已开启 RAW+JPG 同名合并显示" else "已展开显示所有独立文件")
    }

    fun toggleStarredOnlyFilter() {
        _starredOnlyFilter.value = !_starredOnlyFilter.value
        recomputeDisplayPhotos()
    }

    fun togglePhotoStar(photo: CameraPhoto) {
        val nowStarred = repo.favoriteStore.toggleStar(photo.uniqueId)
        val companionId = photo.companionRaw?.uniqueId
        _rawPhotos.update { list ->
            list.map { p ->
                if (p.uniqueId == photo.uniqueId || (companionId != null && p.uniqueId == companionId)) {
                    p.copy(isStarred = nowStarred)
                } else p
            }
        }
        recomputeDisplayPhotos()
    }

    fun switchStorageSlot(slot: StorageSlot) {
        if (_activeSlot.value == slot) return
        _activeSlot.value = slot
        _selectedPhotoIds.value = emptySet()
        refreshPhotos()
    }

    fun setFormatFilter(filter: PhotoFormatFilter) {
        _formatFilter.value = filter
        recomputeDisplayPhotos()
    }

    fun refreshPhotos() {
        if (!_cameraInfo.value.connected) return
        viewModelScope.launch {
            _isLoadingPhotos.value = true
            repo.listPhotos(_activeSlot.value)
                .onSuccess { list ->
                    _rawPhotos.value = list
                    recomputeDisplayPhotos()
                }
                .onFailure { showToast("读取 ${_activeSlot.value.displayName} 失败: ${it.localizedMessage}") }
            _isLoadingPhotos.value = false
        }
    }

    private fun recomputeDisplayPhotos() {
        val rawList = _rawPhotos.value
        val filter = _formatFilter.value
        val paired = _pairRawJpg.value
        val starredOnly = _starredOnlyFilter.value

        val mergedList = if (paired && filter == PhotoFormatFilter.ALL) {
            val byBaseKey = LinkedHashMap<String, MutableList<CameraPhoto>>()
            for (p in rawList) {
                val key = "${p.dir}/${p.baseName}"
                byBaseKey.getOrPut(key) { mutableListOf() }.add(p)
            }
            byBaseKey.values.map { group ->
                val jpg = group.firstOrNull { !it.isRaw }
                val raw = group.firstOrNull { it.isRaw }
                when {
                    jpg != null && raw != null -> jpg.copy(
                        companionRaw = raw,
                        isDownloaded = jpg.isDownloaded || raw.isDownloaded,
                        isStarred = jpg.isStarred || raw.isStarred
                    )
                    jpg != null -> jpg
                    else -> group.first()
                }
            }
        } else {
            when (filter) {
                PhotoFormatFilter.ALL -> rawList
                PhotoFormatFilter.JPG -> rawList.filter { !it.isRaw }
                PhotoFormatFilter.RAW -> rawList.filter { it.isRaw }
            }
        }

        _starredCount.value = mergedList.count { it.isStarred }
        val finalDisplay = if (starredOnly) mergedList.filter { it.isStarred } else mergedList
        _displayPhotos.value = finalDisplay

        val updatedById = mergedList.associateBy { it.uniqueId }
        if (_detailOpen.value) {
            _detailPhotos.update { cur -> cur.map { updatedById[it.uniqueId] ?: it } }
        }
        _comparePhotos.value?.let { (a, b) ->
            _comparePhotos.value = (updatedById[a.uniqueId] ?: a) to (updatedById[b.uniqueId] ?: b)
        }
    }

    fun requestThumbnail(photo: CameraPhoto) {
        if (_thumbnails.value.containsKey(photo.uniqueId)) return
        viewModelScope.launch {
            val bmp = repo.loadThumbnail(photo)
            if (bmp != null) _thumbnails.update { it + (photo.uniqueId to bmp) }
        }
    }

    fun togglePhotoSelection(photo: CameraPhoto) {
        _selectedPhotoIds.update { cur ->
            if (cur.contains(photo.uniqueId)) cur - photo.uniqueId else cur + photo.uniqueId
        }
    }

    fun selectAllVisible() { _selectedPhotoIds.value = _displayPhotos.value.map { it.uniqueId }.toSet() }
    fun clearSelection() { _selectedPhotoIds.value = emptySet() }

    fun openCompareSelected() {
        val ids = _selectedPhotoIds.value
        val chosen = _displayPhotos.value.filter { ids.contains(it.uniqueId) }
        if (chosen.size != 2) {
            showToast("请在图库中正好勾选 2 张照片进行同步放大对比")
            return
        }
        val (first, second) = chosen[0] to chosen[1]
        _comparePhotos.value = first to second
        loadPreviewAndMetadata(first, computeHistogram = false)
        loadPreviewAndMetadata(second, computeHistogram = false)
    }

    fun closeCompare() {
        _comparePhotos.value = null
    }

    // ---------------------------------------------------------
    // Full-Screen Photo Detail Overlay
    // ---------------------------------------------------------
    fun openPhotoDetail(photo: CameraPhoto) {
        val list = _displayPhotos.value
        val idx = list.indexOfFirst { it.uniqueId == photo.uniqueId }.coerceAtLeast(0)
        _detailPhotos.value = list.ifEmpty { listOf(photo) }
        _detailStartIndex.value = idx
        _detailOpen.value = true
        onDetailPageActive(idx)
    }

    fun closePhotoDetail() { _detailOpen.value = false }

    fun onDetailPageActive(index: Int) {
        val list = _detailPhotos.value
        if (index !in list.indices) return
        loadPreviewAndMetadata(list[index], computeHistogram = true)
        if (index + 1 in list.indices) loadPreviewAndMetadata(list[index + 1], computeHistogram = false)
        if (index - 1 in list.indices) loadPreviewAndMetadata(list[index - 1], computeHistogram = false)
    }

    private fun loadPreviewAndMetadata(photo: CameraPhoto, computeHistogram: Boolean) {
        val id = photo.uniqueId
        val cached = repo.getCachedPreview(photo)
        if (cached != null && !_previewBitmaps.value.containsKey(id)) {
            _previewBitmaps.update { it + (id to cached) }
        }
        if (_previewBitmaps.value.containsKey(id)) {
            if (computeHistogram && !_photoHistogramMap.value.containsKey(id)) {
                val bmp = _previewBitmaps.value[id] ?: return
                viewModelScope.launch {
                    val hist = HistogramCalculator.compute(bmp)
                    _photoHistogramMap.update { it + (id to hist) }
                }
            }
            if (!_photoExifMap.value.containsKey(id)) {
                viewModelScope.launch {
                    val exif = repo.fetchPhotoExif(photo)
                    _photoExifMap.update { it + (id to exif) }
                }
            }
            return
        }
        if (_loadingPreviewIds.value.contains(id)) return
        _loadingPreviewIds.update { it + id }

        viewModelScope.launch {
            val apiJob = launch {
                val apiExif = repo.fetchPhotoExif(photo)
                _photoExifMap.update { map -> map + (id to mergeExif(map[id], apiExif)) }
            }
            repo.loadPreviewWithExif(photo)
                .onSuccess { (bmp, fileExif) ->
                    _previewBitmaps.update { it + (id to bmp) }
                    if (fileExif != null) {
                        _photoExifMap.update { map -> map + (id to mergeExif(fileExif, map[id])) }
                    }
                    if (computeHistogram) {
                        val hist = HistogramCalculator.compute(bmp)
                        _photoHistogramMap.update { it + (id to hist) }
                    }
                }
            apiJob.join()
            _loadingPreviewIds.update { it - id }
        }
    }

    private fun mergeExif(primary: PhotoExif?, fallback: PhotoExif?): PhotoExif {
        if (primary == null) return fallback ?: PhotoExif()
        if (fallback == null) return primary
        return PhotoExif(
            cameraModel = primary.cameraModel.ifBlank { fallback.cameraModel },
            lensModel = primary.lensModel.ifBlank { fallback.lensModel },
            focalLength = primary.focalLength.ifBlank { fallback.focalLength },
            aperture = primary.aperture.ifBlank { fallback.aperture },
            shutterSpeed = primary.shutterSpeed.ifBlank { fallback.shutterSpeed },
            iso = primary.iso.ifBlank { fallback.iso },
            exposureComp = primary.exposureComp.ifBlank { fallback.exposureComp },
            dateTime = primary.dateTime.ifBlank { fallback.dateTime },
            width = if (primary.width > 0) primary.width else fallback.width,
            height = if (primary.height > 0) primary.height else fallback.height
        )
    }

    fun downloadSingleFromDetail(photo: CameraPhoto, quality: DownloadQuality) {
        val targetPhoto = if (quality == DownloadQuality.RAW && photo.companionRaw != null) photo.companionRaw else photo
        val effectiveQuality = if (targetPhoto.isRaw && quality == DownloadQuality.FULL_JPG) DownloadQuality.RAW else quality
        enqueueDownload(listOf(targetPhoto), effectiveQuality, silentToast = true)
    }

    fun sharePhotoFromDetail(photo: CameraPhoto) {
        viewModelScope.launch {
            val bmp = _previewBitmaps.value[photo.uniqueId] ?: repo.loadPreviewWithExif(photo).getOrNull()?.first
            if (bmp == null) { showToast("照片尚未加载完成，请稍后重试"); return@launch }
            PhotoShareHelper.shareBitmap(getApplication(), bmp, "${photo.baseName}_K1.jpg")
                .onFailure { showToast("调用系统分享失败: ${it.localizedMessage}") }
        }
    }

    fun sendPhotoToWatermarkStudio(photo: CameraPhoto) {
        closePhotoDetail()
        closeCompare()
        _activeTab.value = AppTab.WATERMARK
        viewModelScope.launch {
            _watermarkState.update { it.copy(isRendering = true) }
            val existingBmp = _previewBitmaps.value[photo.uniqueId]
            val existingExif = _photoExifMap.value[photo.uniqueId]
            val pair = if (existingBmp != null) {
                existingBmp to (existingExif ?: repo.fetchPhotoExif(photo))
            } else {
                val res = repo.loadPreviewWithExif(photo).getOrNull()
                res?.first to mergeExif(res?.second, existingExif ?: repo.fetchPhotoExif(photo))
            }
            val bmp = pair.first
            val exif = pair.second
            if (bmp != null) {
                releaseSpektraSession()
                val nextCfg = WatermarkConfig.fromExif(exif, _watermarkState.value.config)
                _watermarkState.update {
                    it.copy(
                        sourceBitmap = bmp,
                        sourceRawPath = null,
                        isRawSource = false,
                        sourceFileName = photo.file,
                        config = nextCfg,
                        cropConfig = CropStraightenConfig(),
                        spektraParams = it.spektraParams.copy(rawCustomWb = false, sceneLatitudeActive = false, sceneLatitudeDeltaJson = null),
                        spektraLatitude = null
                    )
                }
                triggerWatermarkPreview(0L)
            } else {
                _watermarkState.update { it.copy(isRendering = false) }
                showToast("无法加载该照片用于后期编辑")
            }
        }
    }

    fun sendRawPhotoToSpektraStudio(photo: CameraPhoto) {
        val rawPhoto = photo.companionRaw ?: photo
        closePhotoDetail()
        closeCompare()
        _activeTab.value = AppTab.WATERMARK
        viewModelScope.launch {
            _watermarkState.update {
                it.copy(
                    colorGradeMode = ColorGradeMode.SPEKTRA_DARKROOM,
                    isRendering = true,
                    spektraStatusText = "正在从 K-1 拉取 14-bit RAW (${rawPhoto.file})..."
                )
            }
            val exif = _photoExifMap.value[photo.uniqueId] ?: repo.fetchPhotoExif(rawPhoto)
            if (_isMockMode.value) {
                val bmp = _previewBitmaps.value[photo.uniqueId] ?: repo.loadPreviewWithExif(photo).getOrNull()?.first
                if (bmp != null) {
                    releaseSpektraSession()
                    val nextCfg = WatermarkConfig.fromExif(exif, _watermarkState.value.config)
                    _watermarkState.update {
                        it.copy(
                            sourceBitmap = bmp,
                            sourceRawPath = null,
                            isRawSource = false,
                            sourceFileName = rawPhoto.file,
                            config = nextCfg,
                            cropConfig = CropStraightenConfig(),
                            spektraLatitude = null
                        )
                    }
                    triggerWatermarkPreview(0L)
                    showToast("演示模式已送入 Spektra 81波长暗房（连真机可直解 14-bit PEF/DNG）")
                }
                return@launch
            }

            val bytesRes = repo.downloadPhotoBytes(rawPhoto, DownloadQuality.RAW) { read, total ->
                val pct = if (total > 0) ((read * 100) / total).toInt() else 0
                _watermarkState.update { it.copy(spektraStatusText = "正在拉取 K-1 RAW ${rawPhoto.file} ($pct%)...") }
            }
            bytesRes.onSuccess { bytes ->
                val cacheFile = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "raw_studio").apply { mkdirs() }
                    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
                    File(dir, rawPhoto.file).apply { writeBytes(bytes) }
                }
                val parsedExif = withContext(Dispatchers.IO) { mergeExif(PentaxExifReader.parse(bytes), exif) }
                openRawFileIntoStudio(cacheFile, rawPhoto.file, parsedExif)
            }.onFailure { err ->
                _watermarkState.update { it.copy(isRendering = false) }
                showToast("拉取 K-1 RAW 失败: ${err.localizedMessage}")
            }
        }
    }

    // ---------------------------------------------------------
    // Transfer Queue & Foreground Service
    // ---------------------------------------------------------
    fun setDefaultQuality(quality: DownloadQuality) { _defaultQuality.value = quality }
    fun setAutoWatermarkOnTransfer(enabled: Boolean) { _autoWatermarkOnTransfer.value = enabled }

    fun enqueueDownload(
        photosToDownload: List<CameraPhoto>,
        quality: DownloadQuality = _defaultQuality.value,
        silentToast: Boolean = false
    ) {
        if (photosToDownload.isEmpty()) return
        val newTasks = photosToDownload.map { photo ->
            val target = if (quality == DownloadQuality.RAW && photo.companionRaw != null) photo.companionRaw else photo
            val effectiveQuality = if (target.isRaw && quality == DownloadQuality.FULL_JPG) DownloadQuality.RAW else quality
            TransferTask(
                id = UUID.randomUUID().toString(),
                photo = target,
                quality = effectiveQuality,
                autoWatermark = _autoWatermarkOnTransfer.value && !target.isRaw
            )
        }
        _transferTasks.update { newTasks + it }
        _selectedPhotoIds.value = emptySet()
        if (!silentToast) showToast("已添加 ${newTasks.size} 张至后台传输队列")
        ensureTransferWorkerRunning()
    }

    fun enqueueOnlyUndownloaded() {
        val undownloaded = _displayPhotos.value.filter { !it.isDownloaded }
        if (undownloaded.isEmpty()) { showToast("当前列表照片已全部同步完毕"); return }
        enqueueDownload(undownloaded, _defaultQuality.value)
    }

    fun clearCompletedTasks() {
        _transferTasks.update { list ->
            list.filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.DOWNLOADING }
        }
    }

    fun cancelAllPendingTasks() {
        transferWorkerJob?.cancel()
        _transferTasks.update { list ->
            list.map {
                if (it.status == TransferStatus.QUEUED || it.status == TransferStatus.DOWNLOADING) {
                    it.copy(status = TransferStatus.CANCELLED)
                } else it
            }
        }
        TransferForegroundService.stop(getApplication())
    }

    private fun ensureTransferWorkerRunning() {
        if (transferWorkerJob?.isActive == true) return
        transferWorkerJob = viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            while (isActive) {
                val next = _transferTasks.value.lastOrNull { it.status == TransferStatus.QUEUED } ?: break
                updateTask(next.id) { it.copy(status = TransferStatus.DOWNLOADING, progress = 0f) }
                val remaining = _transferTasks.value.count {
                    it.status == TransferStatus.QUEUED || it.status == TransferStatus.DOWNLOADING
                }
                TransferForegroundService.updateProgress(
                    ctx, "K-1 传图中 (剩余 $remaining 张)", "${next.photo.file} · ${next.quality.label}", 0, 100
                )

                val res = repo.downloadPhotoBytes(next.photo, next.quality) { read, total ->
                    val frac = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0.5f
                    updateTask(next.id) { it.copy(progress = frac, bytesRead = read, totalBytes = total) }
                    TransferForegroundService.updateProgress(
                        ctx, "K-1 传图中 (剩余 $remaining 张)", "${next.photo.file} (${(frac * 100).toInt()}%)", (frac * 100).toInt(), 100
                    )
                }

                res.onSuccess { bytes ->
                    val isRawFile = next.photo.isRaw && next.quality == DownloadQuality.RAW
                    val outName = if (next.quality == DownloadQuality.VIEW && next.photo.isRaw) {
                        next.photo.baseName + "_preview.jpg"
                    } else next.photo.file

                    val saveResult = MediaStoreSaver.saveImageBytes(ctx, bytes, outName, isRaw = isRawFile)

                    if (next.autoWatermark && !isRawFile) {
                        val bmp = BitmapOrientationUtil.decodeOrientedBitmap(bytes)
                        if (bmp != null) {
                            val fileExif = PentaxExifReader.parse(bytes)
                            val apiExif = repo.fetchPhotoExif(next.photo)
                            val mergedExif = mergeExif(fileExif, apiExif)
                            val studioState = _watermarkState.value
                            val cfg = WatermarkConfig.fromExif(mergedExif, studioState.config)
                            val graded = CubeLutEngine.applyLut(bmp, studioState.activeLutPreset, studioState.lutIntensity)
                            val marked = if (cfg.watermarkEnabled) PentaxWatermarkRenderer.render(graded, cfg) else graded
                            MediaStoreSaver.saveBitmapAsJpeg(
                                ctx, marked, outName.substringBeforeLast('.') + "_pentax_wm.jpg",
                                exifToEmbed = mergedExif
                            )
                            if (marked !== graded) marked.recycle()
                            if (graded !== bmp) graded.recycle()
                            bmp.recycle()
                        }
                    }

                    saveResult.onSuccess { savedPath ->
                        repo.historyStore.markDownloaded(next.photo.uniqueId)
                        _rawPhotos.update { list ->
                            list.map { p -> if (p.uniqueId == next.photo.uniqueId) p.copy(isDownloaded = true) else p }
                        }
                        recomputeDisplayPhotos()
                        updateTask(next.id) { it.copy(status = TransferStatus.COMPLETED, progress = 1f, savedPath = savedPath) }
                    }.onFailure { err ->
                        updateTask(next.id) { it.copy(status = TransferStatus.FAILED, errorMessage = err.localizedMessage) }
                    }
                }.onFailure { err ->
                    updateTask(next.id) { it.copy(status = TransferStatus.FAILED, errorMessage = err.localizedMessage) }
                }
            }
            TransferForegroundService.stop(ctx)
        }
    }

    private fun updateTask(id: String, transform: (TransferTask) -> TransferTask) {
        _transferTasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    // ---------------------------------------------------------
    // Remote Control, Focus Peaking + Zebra, ND/Astro Bulb
    // ---------------------------------------------------------
    fun startLiveView() {
        if (!_cameraInfo.value.connected) return
        stopLiveView()
        _liveViewActive.value = true
        _liveViewError.value = null
        liveViewJob = viewModelScope.launch {
            if (_isMockMode.value) {
                var tick = 0
                while (isActive) {
                    val rawFrame = withContext(Dispatchers.Default) { repo.mockRepo.renderLiveViewFrame(tick++) }
                    rawLatestLiveFrame = rawFrame
                    _liveViewFrame.value = LiveViewAnalyzer.process(rawFrame, _liveAssistConfig.value)
                    delay(120)
                }
            } else {
                repo.apiClient.streamLiveView(
                    onFrame = { rawFrame ->
                        rawLatestLiveFrame = rawFrame
                        val assist = _liveAssistConfig.value
                        if (!assist.peakingEnabled && !assist.zebraEnabled) {
                            _liveViewFrame.value = rawFrame
                        } else if (liveAnalyzeJob?.isActive != true) {
                            liveAnalyzeJob = viewModelScope.launch {
                                _liveViewFrame.value = LiveViewAnalyzer.process(rawFrame, _liveAssistConfig.value)
                            }
                        }
                    },
                    onError = { msg -> _liveViewError.value = msg; _liveViewActive.value = false }
                )
            }
        }
    }

    fun stopLiveView() {
        liveViewJob?.cancel(); liveViewJob = null
        liveAnalyzeJob?.cancel(); liveAnalyzeJob = null
        _liveViewActive.value = false
    }

    fun toggleFocusPeaking() { _liveAssistConfig.update { it.copy(peakingEnabled = !it.peakingEnabled) }; reanalyzeLatestFrame() }
    fun setPeakingColor(color: FocusPeakingColor) { _liveAssistConfig.update { it.copy(peakingColor = color, peakingEnabled = true) }; reanalyzeLatestFrame() }
    fun setPeakingSensitivity(sens: PeakingSensitivity) { _liveAssistConfig.update { it.copy(peakingSensitivity = sens, peakingEnabled = true) }; reanalyzeLatestFrame() }
    fun toggleZebraStripes() { _liveAssistConfig.update { it.copy(zebraEnabled = !it.zebraEnabled) }; reanalyzeLatestFrame() }
    fun setZebraThreshold(percent: Int) { _liveAssistConfig.update { it.copy(zebraThresholdPercent = percent.coerceIn(75, 99), zebraEnabled = true) }; reanalyzeLatestFrame() }

    private fun reanalyzeLatestFrame() {
        val raw = rawLatestLiveFrame ?: return
        viewModelScope.launch { _liveViewFrame.value = LiveViewAnalyzer.process(raw, _liveAssistConfig.value) }
    }

    fun setManualLensMode(enabled: Boolean) {
        _manualLensMode.value = enabled
        showToast(if (enabled) "手动头安全快门已开启（跳过 AF）" else "已恢复自动对焦快门")
    }
    fun setGridMode(mode: GridOverlayMode) { _gridMode.value = mode }
    fun setRemoteShotMode(mode: RemoteShotMode) { _remoteShotMode.value = mode }

    fun updateCameraParam(key: String, value: String) {
        viewModelScope.launch {
            repo.updateParam(key, value)
                .onSuccess { updatedMock ->
                    if (updatedMock != null) _shootingParams.value = updatedMock
                    else _shootingParams.update { cur ->
                        when (key) {
                            "av" -> cur.copy(av = value); "tv" -> cur.copy(tv = value)
                            "sv" -> cur.copy(sv = value); "xv" -> cur.copy(xv = value)
                            "wbMode" -> cur.copy(wbMode = value); else -> cur
                        }
                    }
                }
                .onFailure { showToast("参数调整失败: ${it.localizedMessage}") }
        }
    }

    fun touchFocus(xPercent: Int, yPercent: Int) {
        if (_manualLensMode.value) { showToast("当前为手动头模式（AF 已跳过）"); return }
        viewModelScope.launch { repo.triggerAutoFocus(xPercent, yPercent).onFailure { showToast("对焦未成功: ${it.localizedMessage}") } }
    }

    fun shootSingle() {
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        viewModelScope.launch {
            repo.triggerShutter(afEnabled = !_manualLensMode.value)
                .onSuccess { mockPhoto ->
                    showToast("快门已释放")
                    if (mockPhoto != null) {
                        refreshPhotos()
                        if (_tetherEnabled.value) enqueueDownload(listOf(mockPhoto), DownloadQuality.VIEW)
                    }
                }
                .onFailure { showToast("快门触发失败: ${it.localizedMessage}") }
        }
    }

    fun updateIntervalConfig(intervalSec: Int, totalShots: Int) {
        _intervalState.update { it.copy(intervalSec = intervalSec.coerceIn(2, 3600), totalShots = totalShots.coerceIn(2, 999)) }
    }

    fun toggleIntervalometer() {
        if (_intervalState.value.running) {
            intervalJob?.cancel()
            _intervalState.update { it.copy(running = false, nextShotCountdown = 0) }
            showToast("已停止间隔延时拍摄")
            return
        }
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        val cfg = _intervalState.value
        _intervalState.update { it.copy(running = true, shotsTaken = 0, nextShotCountdown = 0) }
        intervalJob = viewModelScope.launch {
            for (shot in 1..cfg.totalShots) {
                if (!isActive) break
                repo.triggerShutter(afEnabled = !_manualLensMode.value)
                _intervalState.update { it.copy(shotsTaken = shot) }
                if (shot < cfg.totalShots) {
                    for (sec in cfg.intervalSec downTo 1) {
                        if (!isActive) break
                        _intervalState.update { it.copy(nextShotCountdown = sec) }
                        delay(1000)
                    }
                }
            }
            _intervalState.update { it.copy(running = false, nextShotCountdown = 0) }
            showToast("间隔延时拍摄完成 (${cfg.totalShots} 张)")
            refreshPhotos()
        }
    }

    fun updateBulbTarget(seconds: Int) { _bulbState.update { it.copy(targetSec = seconds.coerceIn(1, 7200)) } }

    fun applyCalculatedBulbSeconds(seconds: Double, autoStart: Boolean = false) {
        val target = seconds.roundToInt().coerceIn(1, 7200)
        _remoteShotMode.value = RemoteShotMode.BULB
        _bulbState.update { it.copy(targetSec = target) }
        if (autoStart) {
            if (_bulbState.value.running) showToast("B 门正在曝光中，请先结束当前曝光")
            else toggleBulbExposure()
        } else {
            showToast("已将 ${target}s 填入 B 门长曝计时器")
        }
    }

    fun toggleBulbExposure() {
        if (_bulbState.value.running) {
            bulbJob?.cancel()
            viewModelScope.launch {
                repo.finishBulb()
                _bulbState.update { it.copy(running = false) }
                showToast("B门长曝已结束 (${_bulbState.value.elapsedSec}s)")
                refreshPhotos()
            }
            return
        }
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        val target = _bulbState.value.targetSec
        bulbJob = viewModelScope.launch {
            repo.startBulb().onFailure {
                showToast("B门启动失败（请确认机身拨盘处于 B 门）: ${it.localizedMessage}")
                return@launch
            }
            _bulbState.update { it.copy(running = true, elapsedSec = 0) }
            for (sec in 1..target) {
                if (!isActive) break
                delay(1000)
                _bulbState.update { it.copy(elapsedSec = sec) }
            }
            repo.finishBulb()
            _bulbState.update { it.copy(running = false) }
            showToast("B门长曝完成 (${target}s)")
            refreshPhotos()
        }
    }

    fun toggleTetherMode(enabled: Boolean) {
        _tetherEnabled.value = enabled
        if (enabled && _cameraInfo.value.connected && !_isMockMode.value) {
            repo.apiClient.startChangesListener(
                onNewPhoto = { dir, file, slot ->
                    val upper = file.uppercase()
                    val isRaw = upper.endsWith(".PEF") || upper.endsWith(".DNG")
                    viewModelScope.launch { enqueueDownload(listOf(CameraPhoto(dir, file, slot, "最新拍摄", isRaw)), DownloadQuality.VIEW) }
                },
                onStateChanged = {
                    viewModelScope.launch {
                        repo.apiClient.fetchCameraParams(_shootingParams.value).onSuccess { _shootingParams.value = it }
                    }
                }
            )
            showToast("拍完即传已开启")
        } else if (!enabled) stopTether()
        else if (enabled && _isMockMode.value) showToast("拍完即传已开启（模拟模式）")
    }

    private fun stopTether() { _tetherEnabled.value = false; repo.apiClient.stopChangesListener() }

    // ---------------------------------------------------------
    // SpektraLab 81-Wavelength Native Vulkan Engine (Dual-Channel RAW + 121 Films + Recipes + WB + Diffusion + Leak + Latitude)
    // ---------------------------------------------------------
    private suspend fun ensureSpektraEngineReady(): Boolean = withContext(Dispatchers.IO) {
        if (_watermarkState.value.spektraEngineReady) return@withContext true
        if (!SpektraNativeBridge.isNativeLibraryLoaded) {
            val err = SpektraNativeBridge.loadError ?: "未找到 libk1spektra.so"
            _watermarkState.update { it.copy(spektraEngineReady = false, spektraError = err, spektraStatusText = err) }
            return@withContext false
        }

        val extractRes = SpektraResourceManager.ensureExtracted(getApplication()) { status ->
            _watermarkState.update { it.copy(spektraStatusText = status) }
        }
        val resDir = extractRes.getOrElse { e ->
            val msg = e.localizedMessage ?: "解压光谱资源失败"
            _watermarkState.update { it.copy(spektraEngineReady = false, spektraError = msg, spektraStatusText = msg) }
            return@withContext false
        }

        // Refresh full 121-film catalog now that StockCatalog.json is guaranteed extracted
        val fullStocks = SpektraCatalog.loadFullCatalog(getApplication())
        _watermarkState.update {
            it.copy(allFilmStocks = fullStocks, spektraStatusText = "正在启动 SpektraLab Vulkan 引擎...")
        }

        val initReply = SpektraNativeBridge.nativeInitEngine(resDir.absolutePath)
        if (initReply.startsWith("OK|")) {
            val devName = SpektraNativeBridge.parseEngineDeviceName(initReply)
            _watermarkState.update {
                it.copy(
                    spektraEngineReady = true,
                    spektraError = null,
                    spektraDeviceName = devName,
                    spektraStatusText = "Vulkan GPU 就绪 · $devName"
                )
            }
            true
        } else {
            val err = initReply.removePrefix("ERR|").ifBlank { "Vulkan 初始化失败" }
            _watermarkState.update {
                it.copy(spektraEngineReady = false, spektraError = err, spektraStatusText = "Vulkan 引擎不可用：$err")
            }
            false
        }
    }

    private fun releaseSpektraSession() {
        val h = spektraSessionHandle
        spektraSessionHandle = 0L
        spektraSessionSourceKey = ""
        lastAppliedSpektraParams = null
        spektraHasRenderedLive = false
        if (h != 0L && SpektraNativeBridge.isNativeLibraryLoaded) {
            SpektraNativeBridge.nativeCloseSession(h)
        }
    }

    private suspend fun ensureSpektraPreviewSessionOpened(state: WatermarkEditorState): Long = withContext(Dispatchers.IO) {
        if (!ensureSpektraEngineReady()) return@withContext 0L
        val p = state.spektraParams
        val wbKey = if (state.isRawSource && p.rawCustomWb) "wb_${p.rawTempKelvin.toInt()}_${p.rawTint.toInt()}" else "wb_shot"
        val desiredKey = if (state.isRawSource && !state.sourceRawPath.isNullOrBlank()) {
            "raw_half:${state.sourceRawPath}:$wbKey"
        } else {
            "bmp:${System.identityHashCode(state.sourceBitmap)}:${state.sourceFileName}"
        }

        if (spektraSessionHandle != 0L && spektraSessionSourceKey == desiredKey) {
            return@withContext spektraSessionHandle
        }

        releaseSpektraSession()
        val paramsJson = p.toEngineJson()
        val handle = if (state.isRawSource && !state.sourceRawPath.isNullOrBlank()) {
            _watermarkState.update { it.copy(spektraStatusText = "LibRaw 半尺寸高速预览解码中 (~9MP)...") }
            SpektraNativeBridge.nativeOpenRawFile(
                filePath = state.sourceRawPath,
                useHeadroom = false,
                halfSize = true,
                useCustomWb = p.rawCustomWb,
                tempKelvin = p.rawTempKelvin.toDouble(),
                tint = p.rawTint.toDouble(),
                paramsJson = paramsJson
            )
        } else {
            val bmp = state.sourceBitmap ?: return@withContext 0L
            SpektraNativeBridge.nativeOpenBitmap(bmp, paramsJson)
        }

        if (handle == 0L) {
            val err = SpektraNativeBridge.nativeGetLastError().ifBlank { "打开暗房会话失败" }
            _watermarkState.update { it.copy(spektraError = err, spektraStatusText = err) }
            return@withContext 0L
        }

        spektraSessionHandle = handle
        spektraSessionSourceKey = desiredKey
        lastAppliedSpektraParams = p
        spektraHasRenderedLive = false

        val srcTag = if (state.isRawSource) "14-bit RAW 高速预览" else "JPG 线性还原"
        _watermarkState.update {
            it.copy(
                spektraError = null,
                spektraStatusText = "${it.spektraDeviceName.ifBlank { "Vulkan GPU" }} · $srcTag"
            )
        }

        val latReply = SpektraNativeBridge.nativeSceneLatitude(handle, null)
        SpektraNativeBridge.parseLatitudeReadout(latReply)?.let { lat ->
            _watermarkState.update { it.copy(spektraLatitude = lat) }
        }
        handle
    }

    fun setColorGradeMode(mode: ColorGradeMode) {
        _watermarkState.update { it.copy(colorGradeMode = mode) }
        triggerWatermarkPreview(0L)
    }

    fun setShowAllStocksPicker(open: Boolean) {
        _watermarkState.update { it.copy(showAllStocksPicker = open) }
    }

    fun selectSpektraFilmStock(stock: SpektraFilmStock) {
        val cur = _watermarkState.value.spektraParams
        val next = if (stock.isSlide) {
            cur.copy(
                filmStockId = stock.id, scanFilm = true,
                cFilterNeutral = null, mFilterNeutral = null, yFilterNeutral = null,
                mFilterShift = 0f, yFilterShift = 0f,
                sceneLatitudeActive = false, sceneLatitudeDeltaJson = null
            )
        } else {
            cur.copy(
                filmStockId = stock.id, scanFilm = false,
                printStockId = stock.defaultPrintPaperId ?: cur.printStockId,
                cFilterNeutral = null, mFilterNeutral = null, yFilterNeutral = null,
                mFilterShift = 0f, yFilterShift = 0f,
                sceneLatitudeActive = false, sceneLatitudeDeltaJson = null
            )
        }
        _watermarkState.update { it.copy(spektraParams = next, showAllStocksPicker = false) }
        triggerWatermarkPreview(30L)
    }

    fun selectSpektraPrintPaper(paperId: String) {
        _watermarkState.update {
            it.copy(spektraParams = it.spektraParams.copy(printStockId = paperId, scanFilm = false, cFilterNeutral = null, mFilterNeutral = null, yFilterNeutral = null))
        }
        triggerWatermarkPreview(30L)
    }

    fun selectSpektraFilmFormat(format: FilmFormatSpec) {
        _watermarkState.update { it.copy(spektraParams = it.spektraParams.copy(filmFormat = format)) }
        triggerWatermarkPreview(30L)
    }

    fun selectDiffusionFamily(family: DiffusionFilterFamily) {
        _watermarkState.update { it.copy(spektraParams = it.spektraParams.copy(diffusionFamily = family)) }
        triggerWatermarkPreview(30L)
    }

    fun selectLightLeakKind(kind: LightLeakKind) {
        _watermarkState.update { it.copy(spektraParams = it.spektraParams.copy(leakKind = kind)) }
        triggerWatermarkPreview(30L)
    }

    fun randomizeLightLeakSeed() {
        val nextSeed = ((System.nanoTime() and 0x7FFFFFFFL) % 99999L).toInt().coerceAtLeast(1)
        _watermarkState.update { it.copy(spektraParams = it.spektraParams.copy(leakSeed = nextSeed)) }
        triggerWatermarkPreview(20L)
    }

    fun updateRawWhiteBalance(tempKelvin: Float, tint: Float) {
        _watermarkState.update {
            it.copy(
                spektraParams = it.spektraParams.copy(
                    rawCustomWb = true,
                    rawTempKelvin = tempKelvin.coerceIn(2200f, 12000f),
                    rawTint = tint.coerceIn(-100f, 100f)
                )
            )
        }
        triggerWatermarkPreview(120L)
    }

    fun resetRawWhiteBalanceToAsShot() {
        val st = _watermarkState.value
        _watermarkState.update {
            it.copy(
                spektraParams = it.spektraParams.copy(
                    rawCustomWb = false,
                    rawTempKelvin = st.asShotTempKelvin,
                    rawTint = st.asShotTint
                )
            )
        }
        triggerWatermarkPreview(30L)
    }

    fun toggleSceneLatitudeAutoFit() {
        val cur = _watermarkState.value.spektraParams
        if (cur.sceneLatitudeActive) {
            _watermarkState.update {
                it.copy(spektraParams = it.spektraParams.copy(sceneLatitudeActive = false, sceneLatitudeDeltaJson = null))
            }
            triggerWatermarkPreview(20L)
            showToast("已关闭大光比场景置位")
            return
        }
        viewModelScope.launch {
            _watermarkState.update { it.copy(isRendering = true) }
            val handle = ensureSpektraPreviewSessionOpened(_watermarkState.value)
            if (handle == 0L) {
                _watermarkState.update { it.copy(isRendering = false) }
                return@launch
            }
            val latReply = withContext(Dispatchers.IO) { SpektraNativeBridge.nativeSceneLatitude(handle, null) }
            val lat = SpektraNativeBridge.parseLatitudeReadout(latReply)
            if (lat != null && lat.fitValid && !lat.fitParamsDeltaJson.isNullOrBlank()) {
                _watermarkState.update {
                    it.copy(
                        spektraLatitude = lat,
                        spektraParams = it.spektraParams.copy(
                            sceneLatitudeActive = true,
                            sceneLatitudeDeltaJson = lat.fitParamsDeltaJson
                        )
                    )
                }
                showToast("已应用大光比场景置位（高光拉回 ${"%.1f".format(lat.suggestedHighlightPullBack)} / 阴影提拉 ${"%.1f".format(lat.suggestedShadowPullBack)}）")
                triggerWatermarkPreview(0L)
            } else {
                _watermarkState.update { it.copy(isRendering = false) }
                showToast("当前画面动态范围已在胶片宽容度内，无需额外压缩")
            }
        }
    }

    fun updateSpektraParams(transform: (SpektraDarkroomParams) -> SpektraDarkroomParams) {
        _watermarkState.update { it.copy(spektraParams = transform(it.spektraParams)) }
        triggerWatermarkPreview(45L)
    }

    fun saveCurrentDarkroomRecipe(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) { showToast("请输入配方名称"); return }
        val recipe = SavedDarkroomRecipe(
            id = "rec_${System.currentTimeMillis()}",
            name = clean.take(18),
            params = _watermarkState.value.spektraParams
        )
        val updated = (listOf(recipe) + _darkroomRecipes.value).take(20)
        _darkroomRecipes.value = updated
        repo.prefsStore.saveDarkroomRecipes(updated)
        showToast("已收藏暗房配方：$clean")
    }

    fun applyDarkroomRecipe(recipe: SavedDarkroomRecipe) {
        val cur = _watermarkState.value.spektraParams
        _watermarkState.update {
            it.copy(
                colorGradeMode = ColorGradeMode.SPEKTRA_DARKROOM,
                spektraParams = recipe.params.copy(
                    rawCustomWb = cur.rawCustomWb,
                    rawTempKelvin = cur.rawTempKelvin,
                    rawTint = cur.rawTint
                )
            )
        }
        triggerWatermarkPreview(0L)
        showToast("已套用配方：${recipe.name}")
    }

    fun deleteDarkroomRecipe(id: String) {
        val updated = _darkroomRecipes.value.filterNot { it.id == id }
        _darkroomRecipes.value = updated
        repo.prefsStore.saveDarkroomRecipes(updated)
    }

    fun solveSpektraAutoBalance() {
        if (_watermarkState.value.spektraSolving) return
        viewModelScope.launch {
            _watermarkState.update { it.copy(spektraSolving = true, isRendering = true) }
            val handle = ensureSpektraPreviewSessionOpened(_watermarkState.value)
            if (handle == 0L) {
                _watermarkState.update { it.copy(spektraSolving = false, isRendering = false) }
                showToast("暗房引擎尚未就绪：${_watermarkState.value.spektraError ?: "请检查 Vulkan 支持"}")
                return@launch
            }
            val solveReply = withContext(Dispatchers.IO) { SpektraNativeBridge.nativeSolve(handle, "both") }
            if (solveReply.startsWith("OK|")) {
                try {
                    val obj = JSONObject(solveReply.removePrefix("OK|"))
                    val solved = obj.optJSONObject("solved_params") ?: obj
                    val ev = solved.optDouble("exposure_compensation_ev", Double.NaN)
                    val cf = solved.optDouble("c_filter_neutral", Double.NaN)
                    val mf = solved.optDouble("m_filter_neutral", Double.NaN)
                    val yf = solved.optDouble("y_filter_neutral", Double.NaN)
                    _watermarkState.update { st ->
                        val p = st.spektraParams
                        st.copy(
                            spektraParams = p.copy(
                                autoExposure = false,
                                exposureCompEv = if (!ev.isNaN()) ev.toFloat().coerceIn(-4f, 4f) else p.exposureCompEv,
                                cFilterNeutral = if (!cf.isNaN()) cf.toFloat() else p.cFilterNeutral,
                                mFilterNeutral = if (!mf.isNaN()) mf.toFloat() else p.mFilterNeutral,
                                yFilterNeutral = if (!yf.isNaN()) yf.toFloat() else p.yFilterNeutral,
                                mFilterShift = 0f,
                                yFilterShift = 0f
                            ),
                            spektraSolving = false
                        )
                    }
                    showToast("已通过 81 波长引擎自动求解曝光与中性放大机滤色")
                    triggerWatermarkPreview(0L)
                } catch (_: Exception) {
                    _watermarkState.update { it.copy(spektraSolving = false, isRendering = false) }
                }
            } else {
                _watermarkState.update { it.copy(spektraSolving = false, isRendering = false) }
                showToast("自动求解失败: ${solveReply.removePrefix("ERR|")}")
            }
        }
    }

    // ---------------------------------------------------------
    // Studio Pipeline (Crop / Straighten / LUT / Spektra / Watermark + EXIF Writeback!)
    // ---------------------------------------------------------
    fun updateWatermarkConfig(transform: (WatermarkConfig) -> WatermarkConfig) {
        val next = transform(_watermarkState.value.config)
        _watermarkState.update { it.copy(config = next) }
        repo.prefsStore.saveWatermarkPrefs(next)
        triggerWatermarkPreview(35L)
    }

    fun updateCropAspectRatio(ratio: CropAspectRatio) {
        _watermarkState.update { it.copy(cropConfig = it.cropConfig.copy(aspectRatio = ratio)) }
        triggerWatermarkPreview(35L)
    }

    fun updateStraightenDegrees(deg: Float) {
        val clamped = ((deg * 10f).roundToInt() / 10f).coerceIn(-15f, 15f)
        _watermarkState.update { it.copy(cropConfig = it.cropConfig.copy(straightenDegrees = clamped)) }
        triggerWatermarkPreview(40L)
    }

    fun rotateQuarterTurn() {
        _watermarkState.update { it.copy(cropConfig = it.cropConfig.copy(quarterTurns = (it.cropConfig.quarterTurns + 1) % 4)) }
        triggerWatermarkPreview(20L)
    }

    fun resetCropAndStraighten() {
        _watermarkState.update { it.copy(cropConfig = CropStraightenConfig()) }
        triggerWatermarkPreview(20L)
    }

    fun selectLutPreset(id: String) {
        _watermarkState.update { it.copy(selectedLutId = id) }
        repo.prefsStore.saveSelectedLutId(id)
        triggerWatermarkPreview(25L)
    }

    fun updateLutIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        _watermarkState.update { it.copy(lutIntensity = clamped) }
        repo.prefsStore.saveLutIntensity(clamped)
        triggerWatermarkPreview(40L)
    }

    fun importCustomCubeLut(uri: Uri) {
        viewModelScope.launch {
            CubeLutEngine.importCubeFile(getApplication(), uri)
                .onSuccess { preset ->
                    val all = CubeLutEngine.listAllPresets(getApplication())
                    _watermarkState.update {
                        it.copy(colorGradeMode = ColorGradeMode.CUBE_LUT, availableLuts = all, selectedLutId = preset.id)
                    }
                    repo.prefsStore.saveSelectedLutId(preset.id)
                    triggerWatermarkPreview(0L)
                    showToast("已导入 3D LUT：${preset.name}")
                }
                .onFailure { err -> showToast("导入 LUT 失败：${err.localizedMessage}") }
        }
    }

    fun deleteCustomLut(preset: LutPreset) {
        if (CubeLutEngine.deleteCustomLut(preset)) {
            val all = CubeLutEngine.listAllPresets(getApplication())
            val nextId = if (_watermarkState.value.selectedLutId == preset.id) CubeLutEngine.ID_NONE else _watermarkState.value.selectedLutId
            _watermarkState.update { it.copy(availableLuts = all, selectedLutId = nextId) }
            repo.prefsStore.saveSelectedLutId(nextId)
            triggerWatermarkPreview(0L)
            showToast("已删除自定义 LUT：${preset.name}")
        }
    }

    fun setWatermarkFullscreen(open: Boolean) { _watermarkState.update { it.copy(fullscreenPreview = open) } }

    fun addLensPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val updated = (listOf(trimmed) + _lensPresets.value).distinct().take(8)
        _lensPresets.value = updated
        repo.prefsStore.saveLensPresets(updated)
        showToast("已保存常用镜头：$trimmed")
    }

    fun removeLensPreset(name: String) {
        val updated = _lensPresets.value.filterNot { it == name }
        _lensPresets.value = updated
        repo.prefsStore.saveLensPresets(updated)
    }

    fun loadLocalPhotoForWatermark(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _watermarkState.update { it.copy(isRendering = true) }
            val ctx = getApplication<Application>()
            try {
                val rawBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取该图片")
                val bmp = BitmapOrientationUtil.decodeOrientedBitmap(rawBytes)
                    ?: error("无法解码图片格式（如为 PEF/DNG 请点击顶部「RAW」按钮导入）")
                val parsedExif = PentaxExifReader.parse(rawBytes)
                val nextConfig = WatermarkConfig.fromExif(parsedExif, _watermarkState.value.config)

                releaseSpektraSession()
                _watermarkState.update { state ->
                    state.copy(
                        sourceBitmap = bmp,
                        sourceRawPath = null,
                        isRawSource = false,
                        sourceFileName = "PENTAX_${System.currentTimeMillis() % 100000}.jpg",
                        config = nextConfig,
                        cropConfig = CropStraightenConfig(),
                        spektraParams = state.spektraParams.copy(rawCustomWb = false, sceneLatitudeActive = false, sceneLatitudeDeltaJson = null),
                        spektraLatitude = null
                    )
                }
                triggerWatermarkPreview(0L)
                if (nextConfig.lensTitle.isBlank()) showToast("已读取拍摄参数；无镜头元数据，水印自动隐藏镜头栏")
                else showToast("已识别镜头：${nextConfig.lensTitle}")
            } catch (e: Exception) {
                _watermarkState.update { it.copy(isRendering = false) }
                showToast("读取相册照片失败: ${e.localizedMessage}")
            }
        }
    }

    fun loadLocalRawForStudio(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _watermarkState.update {
                it.copy(
                    colorGradeMode = ColorGradeMode.SPEKTRA_DARKROOM,
                    isRendering = true,
                    spektraStatusText = "正在读取本地 RAW 文件..."
                )
            }
            val ctx = getApplication<Application>()
            try {
                val displayName = resolveUriDisplayName(uri).ifBlank { "PENTAX_RAW_${System.currentTimeMillis() % 10000}.DNG" }
                val rawDir = File(ctx.cacheDir, "raw_studio").apply { mkdirs() }
                rawDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
                val outFile = File(rawDir, displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                } ?: error("无法读取 RAW 文件流")

                val headBytes = outFile.inputStream().use { ins ->
                    val buf = ByteArray(minOf(outFile.length(), 262_144L).toInt())
                    val n = ins.read(buf)
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                }
                val parsedExif = PentaxExifReader.parse(headBytes)
                openRawFileIntoStudio(outFile, displayName, parsedExif)
            } catch (e: Exception) {
                _watermarkState.update { it.copy(isRendering = false) }
                showToast("导入 RAW 失败: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun openRawFileIntoStudio(rawFile: File, displayName: String, parsedExif: PhotoExif) {
        releaseSpektraSession()
        val (fallbackBmp, asShotWb) = withContext(Dispatchers.IO) {
            if (SpektraNativeBridge.isNativeLibraryLoaded) {
                val wbReply = SpektraNativeBridge.nativeReadRawAsShotWb(rawFile.absolutePath)
                val wbPair = SpektraNativeBridge.parseAsShotWb(wbReply)
                val preview = SpektraNativeBridge.nativeDecodeRawPreview(rawFile.absolutePath, 1600)
                preview to wbPair
            } else null to (5500f to 0f)
        }
        val nextCfg = WatermarkConfig.fromExif(parsedExif, _watermarkState.value.config)
        _watermarkState.update { state ->
            state.copy(
                sourceBitmap = fallbackBmp ?: state.sourceBitmap,
                sourceRawPath = rawFile.absolutePath,
                isRawSource = true,
                sourceFileName = displayName,
                colorGradeMode = ColorGradeMode.SPEKTRA_DARKROOM,
                config = nextCfg,
                cropConfig = CropStraightenConfig(),
                asShotTempKelvin = asShotWb.first,
                asShotTint = asShotWb.second,
                spektraParams = state.spektraParams.copy(
                    rawCustomWb = false,
                    rawTempKelvin = asShotWb.first,
                    rawTint = asShotWb.second,
                    sceneLatitudeActive = false,
                    sceneLatitudeDeltaJson = null
                ),
                spektraLatitude = null
            )
        }
        showToast("LibRaw 半尺寸高速预览已载入：$displayName (${asShotWb.first.toInt()}K)")
        triggerWatermarkPreview(0L)
    }

    private fun resolveUriDisplayName(uri: Uri): String {
        return try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                .orEmpty()
        } catch (_: Exception) { "" }
    }

    private suspend fun renderSpektraStage(state: WatermarkEditorState, isExport: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            // Dual-Channel RAW Export: For full-resolution RAW export, open a dedicated 36.4MP full-size LibRaw session,
            // render tier = "full", and immediately free it so mobile RAM stays lean!
            if (isExport && state.isRawSource && !state.sourceRawPath.isNullOrBlank() && state.config.highQualityExport) {
                if (!ensureSpektraEngineReady()) return@withContext null
                val p = state.spektraParams
                _watermarkState.update { it.copy(spektraStatusText = "LibRaw 全尺寸 36.4MP 解码与 81 波长渲染中...") }
                val fullHandle = SpektraNativeBridge.nativeOpenRawFile(
                    filePath = state.sourceRawPath,
                    useHeadroom = true,
                    halfSize = false,
                    useCustomWb = p.rawCustomWb,
                    tempKelvin = p.rawTempKelvin.toDouble(),
                    tint = p.rawTint.toDouble(),
                    paramsJson = p.toEngineJson()
                )
                if (fullHandle == 0L) {
                    val err = SpektraNativeBridge.nativeGetLastError().ifBlank { "全尺寸 RAW 解码失败" }
                    _watermarkState.update { it.copy(spektraError = err) }
                    return@withContext null
                }
                return@withContext try {
                    SpektraNativeBridge.nativeRenderToBitmap(fullHandle, "full", reprintOnly = false)
                } finally {
                    SpektraNativeBridge.nativeCloseSession(fullHandle)
                    _watermarkState.update {
                        it.copy(spektraStatusText = "${it.spektraDeviceName.ifBlank { "Vulkan GPU" }} · 14-bit RAW 高速预览")
                    }
                }
            }

            // Fast interactive preview or standard export using active session
            val handle = ensureSpektraPreviewSessionOpened(state)
            if (handle == 0L) return@withContext null

            val params = state.spektraParams
            val prev = lastAppliedSpektraParams
            val tier = if (isExport && state.config.highQualityExport) "full" else if (isExport) "preview" else "live"
            val printOnlyDelta = (prev != null && params.isPrintOnlyDeltaFrom(prev) && spektraHasRenderedLive && tier == "live")

            val setReply = SpektraNativeBridge.nativeSetParams(handle, params.toEngineJson())
            if (!setReply.startsWith("OK|")) {
                _watermarkState.update { it.copy(spektraError = setReply.removePrefix("ERR|")) }
                return@withContext null
            }
            lastAppliedSpektraParams = params

            val bmp = SpektraNativeBridge.nativeRenderToBitmap(handle, tier, reprintOnly = printOnlyDelta)
            if (bmp != null) {
                if (tier == "live") spektraHasRenderedLive = true
                if (!printOnlyDelta && tier == "live") {
                    val latReply = SpektraNativeBridge.nativeSceneLatitude(handle, null)
                    SpektraNativeBridge.parseLatitudeReadout(latReply)?.let { lat ->
                        _watermarkState.update { it.copy(spektraLatitude = lat) }
                    }
                }
            } else {
                val err = SpektraNativeBridge.nativeGetLastError().ifBlank { "Vulkan 渲染失败" }
                _watermarkState.update { it.copy(spektraError = err, spektraStatusText = err) }
            }
            bmp
        }

    private suspend fun renderFullPipeline(state: WatermarkEditorState, isExport: Boolean): Bitmap? {
        val src = state.sourceBitmap
        if (src == null && !state.isRawSource) return null

        val maxEdge = if (isExport) {
            if (state.config.highQualityExport) null else 2560
        } else 1080

        val baseGraded: Bitmap = if (state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM) {
            val spektraBmp = renderSpektraStage(state, isExport)
            if (spektraBmp != null) spektraBmp else (src ?: return null)
        } else {
            val nonNullSrc = src ?: return null
            val workingSrc = if (maxEdge != null && (nonNullSrc.width > maxEdge || nonNullSrc.height > maxEdge)) {
                val scale = maxEdge.toFloat() / maxOf(nonNullSrc.width, nonNullSrc.height)
                Bitmap.createScaledBitmap(
                    nonNullSrc,
                    (nonNullSrc.width * scale).roundToInt().coerceAtLeast(16),
                    (nonNullSrc.height * scale).roundToInt().coerceAtLeast(16),
                    true
                )
            } else nonNullSrc
            val lutOut = CubeLutEngine.applyLut(workingSrc, state.activeLutPreset, state.lutIntensity)
            if (workingSrc !== nonNullSrc && lutOut !== workingSrc) workingSrc.recycle()
            lutOut
        }

        val cropped = ImageCropStraighten.apply(baseGraded, state.cropConfig)
        if (baseGraded !== src && cropped !== baseGraded) baseGraded.recycle()

        val finalOut = if (state.config.watermarkEnabled) {
            val marked = PentaxWatermarkRenderer.render(cropped, state.config, maxLongEdge = null)
            if (cropped !== src && marked !== cropped) cropped.recycle()
            marked
        } else {
            if (cropped === src) cropped.copy(Bitmap.Config.ARGB_8888, false) else cropped
        }

        return finalOut
    }

    fun triggerWatermarkPreview(debounceMs: Long = 35L) {
        val state = _watermarkState.value
        if (state.sourceBitmap == null && !state.isRawSource) return
        watermarkRenderJob?.cancel()
        watermarkRenderJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            _watermarkState.update { it.copy(isRendering = true) }
            val rendered = renderFullPipeline(_watermarkState.value, isExport = false)
            _watermarkState.update {
                it.copy(previewBitmap = rendered ?: it.previewBitmap, isRendering = false)
            }
        }
    }

    /**
     * Batch processor supporting BOTH 3D LUT and SpektraLab 81-Wavelength Vulkan Darkroom + EXIF writeback!
     */
    fun batchApplyWatermark(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val state = _watermarkState.value
            val useSpektra = state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM && ensureSpektraEngineReady()
            val baseConfig = state.config
            val spektraJson = state.spektraParams.toEngineJson()
            val lutPreset = state.activeLutPreset
            val lutIntensity = state.lutIntensity
            val cropRatioOnly = CropStraightenConfig(aspectRatio = state.cropConfig.aspectRatio)
            val maxEdge = if (baseConfig.highQualityExport) null else 2560
            var successCount = 0

            uris.forEachIndexed { idx, uri ->
                _watermarkState.update { it.copy(batchProgress = BatchWatermarkProgress(idx + 1, uris.size)) }
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
                    val bmp = BitmapOrientationUtil.decodeOrientedBitmap(bytes) ?: return@forEachIndexed
                    val exif = PentaxExifReader.parse(bytes)
                    val photoCfg = WatermarkConfig.fromExif(exif, baseConfig)

                    val graded: Bitmap = if (useSpektra) {
                        val h = SpektraNativeBridge.nativeOpenBitmap(bmp, spektraJson)
                        if (h != 0L) {
                            try {
                                val tier = if (baseConfig.highQualityExport) "full" else "preview"
                                SpektraNativeBridge.nativeRenderToBitmap(h, tier, false) ?: bmp
                            } finally {
                                SpektraNativeBridge.nativeCloseSession(h)
                            }
                        } else bmp
                    } else {
                        val working = if (maxEdge != null && (bmp.width > maxEdge || bmp.height > maxEdge)) {
                            val s = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
                            Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
                        } else bmp
                        val lutOut = CubeLutEngine.applyLut(working, lutPreset, lutIntensity)
                        if (working !== bmp && lutOut !== working) working.recycle()
                        lutOut
                    }

                    val cropped = ImageCropStraighten.apply(graded, cropRatioOnly)
                    val rendered = if (photoCfg.watermarkEnabled) PentaxWatermarkRenderer.render(cropped, photoCfg) else cropped
                    val outName = "PENTAX_BATCH_${System.currentTimeMillis() % 100000}_${idx + 1}.jpg"
                    val res = MediaStoreSaver.saveBitmapAsJpeg(
                        ctx, rendered, outName,
                        quality = if (baseConfig.highQualityExport) 97 else 90,
                        exifToEmbed = exif
                    )
                    if (rendered !== cropped) rendered.recycle()
                    if (cropped !== graded) cropped.recycle()
                    if (graded !== bmp) graded.recycle()
                    bmp.recycle()
                    if (res.isSuccess) successCount++
                } catch (_: Exception) {
                }
            }

            _watermarkState.update { it.copy(batchProgress = null) }
            val modeLabel = if (useSpektra) "Spektra 暗房" else "3D LUT"
            showToast("$modeLabel 批量出片完成：已保存 $successCount/${uris.size} 张（已保留原图 EXIF）")
        }
    }

    private fun buildExportExif(cfg: WatermarkConfig): PhotoExif = PhotoExif(
        cameraModel = cfg.cameraTitle.ifBlank { "PENTAX K-1" },
        lensModel = cfg.lensTitle,
        focalLength = cfg.focalLength,
        aperture = cfg.aperture,
        shutterSpeed = cfg.shutterSpeed,
        iso = cfg.iso,
        dateTime = cfg.dateTime
    )

    fun exportWatermarkedPhoto() {
        val state = _watermarkState.value
        if (state.sourceBitmap == null && !state.isRawSource) { showToast("请先选择一张照片或 RAW"); return }
        viewModelScope.launch {
            _watermarkState.update { it.copy(isSaving = true) }
            val finalBitmap = renderFullPipeline(state, isExport = true)
            if (finalBitmap == null) {
                _watermarkState.update { it.copy(isSaving = false) }
                showToast("渲染导出失败: ${_watermarkState.value.spektraError ?: "未知错误"}")
                return@launch
            }
            val baseName = state.sourceFileName.substringBeforeLast('.')
            val tag = if (state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM) {
                state.spektraParams.filmStockId
            } else if (state.config.watermarkEnabled) {
                state.config.style.name.lowercase()
            } else "edit"
            val outName = "${baseName}_PENTAX_${tag}.jpg"
            val exifToWrite = buildExportExif(state.config)
            val result = withContext(Dispatchers.IO) {
                MediaStoreSaver.saveBitmapAsJpeg(
                    context = getApplication(),
                    bitmap = finalBitmap,
                    fileName = outName,
                    quality = if (state.config.highQualityExport) 97 else 90,
                    exifToEmbed = exifToWrite
                )
            }
            if (finalBitmap !== state.sourceBitmap) finalBitmap.recycle()
            _watermarkState.update { it.copy(isSaving = false) }
            result.onSuccess { path -> showToast("已保存全分辨率成片（已回写原图 EXIF）：$path") }
                .onFailure { err -> showToast("导出失败: ${err.localizedMessage}") }
        }
    }

    fun shareWatermarkedPhoto() {
        val state = _watermarkState.value
        if (state.sourceBitmap == null && !state.isRawSource) { showToast("请先选择一张照片"); return }
        viewModelScope.launch {
            _watermarkState.update { it.copy(isSharing = true) }
            val shareState = state.copy(config = state.config.copy(highQualityExport = false))
            val finalBitmap = renderFullPipeline(shareState, isExport = true)
            if (finalBitmap == null) {
                _watermarkState.update { it.copy(isSharing = false) }
                showToast("渲染分享图失败")
                return@launch
            }
            val baseName = state.sourceFileName.substringBeforeLast('.')
            val result = PhotoShareHelper.shareBitmap(getApplication(), finalBitmap, "${baseName}_PENTAX.jpg")
            if (finalBitmap !== state.sourceBitmap) finalBitmap.recycle()
            _watermarkState.update { it.copy(isSharing = false) }
            result.onFailure { showToast("调起分享失败: ${it.localizedMessage}") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveView()
        stopTether()
        releaseSpektraSession()
        repo.wifiBinder.releaseBinding(silent = true)
    }
}
