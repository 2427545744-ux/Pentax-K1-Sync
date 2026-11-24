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
import com.pentax.k1sync.image.PhotoCollageGenerator
import com.pentax.k1sync.image.PostDarkroomAdjuster
import com.pentax.k1sync.image.PostToneConfig
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.DownloadQuality
import com.pentax.k1sync.model.FocusPeakingColor
import com.pentax.k1sync.model.GallerySlotFilter
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
import com.pentax.k1sync.spektra.BwCameraFilter
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
import kotlinx.coroutines.NonCancellable
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

enum class FocusStatus {
    IDLE, FOCUSING, LOCKED, FAILED
}

data class TouchFocusHudState(
    val status: FocusStatus = FocusStatus.IDLE,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val message: String = "",
    val lockedForShutter: Boolean = false
)

data class AebState(
    val running: Boolean = false,
    val shotCount: Int = 5,
    val stepEv: Float = 1.0f,
    val currentShot: Int = 0,
    val activeTotalShots: Int = 5,
    val currentEvLabel: String = ""
)

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
    val sourceCameraPhoto: CameraPhoto? = null,
    val sourceRawPath: String? = null,
    val isRawSource: Boolean = false,
    val unCroppedGradedBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val fullscreenHighResBitmap: Bitmap? = null,
    val isLoadingFullscreenHd: Boolean = false,
    val sourceFileName: String = "PENTAX_K1_Sample.jpg",
    val config: WatermarkConfig = WatermarkConfig(),
    val cropConfig: CropStraightenConfig = CropStraightenConfig(),
    val postTone: PostToneConfig = PostToneConfig(),
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
    val cellularInternetAlive: StateFlow<Boolean> = repo.wifiBinder.cellularInternetAlive

    private val _cameraWifiPassword = MutableStateFlow(repo.prefsStore.loadCameraWifiPassword())
    val cameraWifiPassword: StateFlow<String> = _cameraWifiPassword.asStateFlow()
    val apiLogEnabled: StateFlow<Boolean> = ApiDebugLogger.enabled
    val apiLogEntries: StateFlow<List<ApiLogEntry>> = ApiDebugLogger.entries

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var userManuallyDisconnected = false

    // --- Gallery State ---
    private val _activeSlot = MutableStateFlow(GallerySlotFilter.ALL)
    val activeSlot: StateFlow<GallerySlotFilter> = _activeSlot.asStateFlow()

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

    private val _smartEdgeSnap = MutableStateFlow(true)
    val smartEdgeSnap: StateFlow<Boolean> = _smartEdgeSnap.asStateFlow()

    private val _touchFocusHud = MutableStateFlow(TouchFocusHudState())
    val touchFocusHud: StateFlow<TouchFocusHudState> = _touchFocusHud.asStateFlow()

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

    private val _aebState = MutableStateFlow(AebState())
    val aebState: StateFlow<AebState> = _aebState.asStateFlow()

    private val _astroRedMode = MutableStateFlow(false)
    val astroRedMode: StateFlow<Boolean> = _astroRedMode.asStateFlow()

    private val _volumeShutterEnabled = MutableStateFlow(repo.prefsStore.loadVolumeShutterEnabled())
    val volumeShutterEnabled: StateFlow<Boolean> = _volumeShutterEnabled.asStateFlow()

    private val _isGeneratingCollage = MutableStateFlow(false)
    val isGeneratingCollage: StateFlow<Boolean> = _isGeneratingCollage.asStateFlow()

    private var rawLatestLiveFrame: Bitmap? = null
    private var liveViewJob: Job? = null
    private var liveAnalyzeJob: Job? = null
    private var intervalJob: Job? = null
    private var bulbJob: Job? = null
    private var aebJob: Job? = null

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
                    _volumeShutterEnabled.value = repo.prefsStore.loadVolumeShutterEnabled()
                    _cameraWifiPassword.value = repo.prefsStore.loadCameraWifiPassword()
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
                                copyrightNotice = wmDefaults.copyrightNotice,
                                showDateTime = wmDefaults.showDateTime,
                                showLensName = wmDefaults.showLensName,
                                showOverlaySignature = wmDefaults.showOverlaySignature,
                                showVintageDateImprint = wmDefaults.showVintageDateImprint,
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

    fun updateCameraWifiPassword(password: String) {
        _cameraWifiPassword.value = password
        repo.prefsStore.saveCameraWifiPassword(password)
    }

    fun smartConnectPentaxWifi() {
        userManuallyDisconnected = false
        if (_isMockMode.value) { connectCamera(); return }
        _isConnecting.value = true
        val pass = _cameraWifiPassword.value.trim()
        repo.wifiBinder.requestPentaxHotspot(pass) { bound ->
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

    fun switchStorageSlot(slotFilter: GallerySlotFilter) {
        if (_activeSlot.value == slotFilter) return
        _activeSlot.value = slotFilter
        _selectedPhotoIds.value = emptySet()
        recomputeDisplayPhotos()
    }

    fun setFormatFilter(filter: PhotoFormatFilter) {
        _formatFilter.value = filter
        recomputeDisplayPhotos()
    }

    fun refreshPhotos() {
        if (!_cameraInfo.value.connected) return
        viewModelScope.launch {
            _isLoadingPhotos.value = true
            repo.listAllPhotos()
                .onSuccess { list ->
                    _rawPhotos.value = list
                    recomputeDisplayPhotos()
                }
                .onFailure { showToast("读取 SD1/SD2 照片失败: ${it.localizedMessage}") }
            _isLoadingPhotos.value = false
        }
    }

    private fun recomputeDisplayPhotos() {
        val slotFilter = _activeSlot.value
        val rawList = when (slotFilter) {
            GallerySlotFilter.ALL -> _rawPhotos.value
            GallerySlotFilter.SD1 -> _rawPhotos.value.filter { it.slot == StorageSlot.SD1 }
            GallerySlotFilter.SD2 -> _rawPhotos.value.filter { it.slot == StorageSlot.SD2 }
        }.sortedByDescending { it.sortKey }

        val filter = _formatFilter.value
        val paired = _pairRawJpg.value
        val starredOnly = _starredOnlyFilter.value

        val mergedList = if (paired && filter == PhotoFormatFilter.ALL) {
            val byBaseKey = LinkedHashMap<String, MutableList<CameraPhoto>>()
            for (p in rawList) {
                // Pair same-day & same-filename RAW+JPG even across SD1(JPG) + SD2(RAW)!
                val key = "${p.dateGroup}/${p.baseName}"
                byBaseKey.getOrPut(key) { mutableListOf() }.add(p)
            }
            byBaseKey.values.map { group ->
                val jpg = group.firstOrNull { !it.isRaw }
                val raw = group.firstOrNull { it.isRaw }
                when {
                    jpg != null && raw != null -> jpg.copy(
                        companionRaw = raw,
                        isDownloaded = jpg.isDownloaded || raw.isDownloaded,
                        isStarred = jpg.isStarred || raw.isStarred,
                        sortKey = maxOf(jpg.sortKey, raw.sortKey)
                    )
                    jpg != null -> jpg
                    else -> group.first()
                }
            }.sortedByDescending { it.sortKey }
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

    fun generateCinemaTriptychFromSelected() {
        val ids = _selectedPhotoIds.value
        val chosen = _displayPhotos.value.filter { ids.contains(it.uniqueId) }.take(4)
        if (chosen.size < 2) {
            showToast("请在图库勾选 2～4 张照片生成电影多格拼图")
            return
        }
        if (_isGeneratingCollage.value) return
        viewModelScope.launch {
            _isGeneratingCollage.value = true
            showToast("正在拉取 ${chosen.size} 张照片生成 2.35:1 电影拼图...")
            val loaded = mutableListOf<Bitmap>()
            for (p in chosen) {
                val bmp = _previewBitmaps.value[p.uniqueId] ?: repo.loadPreviewWithExif(p).getOrNull()?.first
                if (bmp != null) loaded += bmp
            }
            if (loaded.size < 2) {
                _isGeneratingCollage.value = false
                showToast("未能加载足够照片用于拼图，请检查相机连接")
                return@launch
            }
            val cfg = _watermarkState.value.config
            val title = if (cfg.signature.isNotBlank()) "PENTAX K-1 · ${cfg.signature.uppercase()}" else "PENTAX K-1 · CINEMA STORY"
            val resultBmp = PhotoCollageGenerator.renderCinemaTriptych(loaded, titleText = title)
            val outName = "PENTAX_TRIPTYCH_${System.currentTimeMillis() % 100000}.jpg"
            val saveRes = withContext(Dispatchers.IO) {
                MediaStoreSaver.saveBitmapAsJpeg(
                    context = getApplication(),
                    bitmap = resultBmp,
                    fileName = outName,
                    quality = 96,
                    subFolder = "PentaxK1_Collage",
                    artist = cfg.signature,
                    copyright = cfg.copyrightNotice
                )
            }
            resultBmp.recycle()
            _selectedPhotoIds.value = emptySet()
            _isGeneratingCollage.value = false
            saveRes.onSuccess { path -> showToast("电影拼图已保存至：$path") }
                .onFailure { err -> showToast("拼图保存失败: ${err.localizedMessage}") }
        }
    }

    fun generateContactSheetFromSelected() {
        val ids = _selectedPhotoIds.value
        val chosen = _displayPhotos.value.filter { ids.contains(it.uniqueId) }.take(24)
        if (chosen.size < 2) {
            showToast("请至少勾选 2 张照片（最多 24 张）生成暗房接触印相表")
            return
        }
        if (_isGeneratingCollage.value) return
        viewModelScope.launch {
            _isGeneratingCollage.value = true
            showToast("正在制作 ${chosen.size} 张暗房接触印相表...")
            val loaded = mutableListOf<Bitmap>()
            val names = mutableListOf<String>()
            for (p in chosen) {
                val bmp = _previewBitmaps.value[p.uniqueId]
                    ?: repo.loadPreviewWithExif(p).getOrNull()?.first
                    ?: _thumbnails.value[p.uniqueId]
                if (bmp != null) {
                    loaded += bmp
                    names += p.file
                }
            }
            if (loaded.size < 2) {
                _isGeneratingCollage.value = false
                showToast("未能加载足够照片用于接触印相表")
                return@launch
            }
            val cfg = _watermarkState.value.config
            val filmName = SpektraCatalog.findFilm(_watermarkState.value.spektraParams.filmStockId).shortName.uppercase()
            val resultBmp = PhotoCollageGenerator.renderContactSheet(
                photos = loaded,
                rollLabel = "PENTAX K-1 · $filmName · CONTACT SHEET",
                fileNames = names
            )
            val outName = "PENTAX_CONTACT_${System.currentTimeMillis() % 100000}.jpg"
            val saveRes = withContext(Dispatchers.IO) {
                MediaStoreSaver.saveBitmapAsJpeg(
                    context = getApplication(),
                    bitmap = resultBmp,
                    fileName = outName,
                    quality = 96,
                    subFolder = "PentaxK1_Collage",
                    artist = cfg.signature,
                    copyright = cfg.copyrightNotice
                )
            }
            resultBmp.recycle()
            _selectedPhotoIds.value = emptySet()
            _isGeneratingCollage.value = false
            saveRes.onSuccess { path -> showToast("暗房接触印相表已保存至：$path") }
                .onFailure { err -> showToast("接触印相表保存失败: ${err.localizedMessage}") }
        }
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
                if (apiExif.dateTime.isNotBlank()) {
                    val refinedKey = CameraPhoto.computeChronologicalSortKey(photo.dir, photo.file, apiExif.dateTime)
                    val refinedGroup = CameraPhoto.formatUnifiedDateGroup(photo.dir, apiExif.dateTime)
                    _rawPhotos.update { list ->
                        list.map { p ->
                            if (p.uniqueId == id) p.copy(sortKey = refinedKey, dateGroup = refinedGroup, exif = apiExif) else p
                        }
                    }
                }
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
                        sourceCameraPhoto = photo,
                        sourceRawPath = null,
                        isRawSource = false,
                        unCroppedGradedBitmap = bmp,
                        fullscreenHighResBitmap = null,
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
                updateTask(next.id) { it.copy(status = TransferStatus.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, retryAttempt = 0) }
                val remaining = _transferTasks.value.count {
                    it.status == TransferStatus.QUEUED || it.status == TransferStatus.DOWNLOADING
                }
                TransferForegroundService.updateProgress(
                    ctx, "K-1 传图中 (剩余 $remaining 张)", "${next.photo.file} · ${next.quality.label}", 0, 100
                )

                var attempt = 0
                val maxRetries = 2
                var finalRes: Result<ByteArray>? = null

                while (isActive && attempt <= maxRetries) {
                    if (attempt > 0) {
                        updateTask(next.id) { it.copy(retryAttempt = attempt, errorMessage = "连接波动，自动重试 ($attempt/$maxRetries)...") }
                        delay(1500)
                    }
                    val startMs = System.currentTimeMillis()
                    var lastReportMs = startMs
                    val res = repo.downloadPhotoBytes(next.photo, next.quality) { read, total ->
                        val now = System.currentTimeMillis()
                        val elapsedSec = ((now - startMs).coerceAtLeast(100L)) / 1000.0
                        val speed = (read / elapsedSec).toLong()
                        val frac = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0.5f
                        updateTask(next.id) {
                            it.copy(progress = frac, bytesRead = read, totalBytes = total, speedBytesPerSec = speed)
                        }
                        if (now - lastReportMs >= 400L || frac >= 0.99f) {
                            lastReportMs = now
                            val mbps = speed / (1024.0 * 1024.0)
                            val rateLabel = if (mbps >= 0.1) "${"%.1f".format(mbps)} MB/s" else "${speed / 1024} KB/s"
                            TransferForegroundService.updateProgress(
                                ctx, "K-1 传图中 (剩余 $remaining 张)", "${next.photo.file} ${(frac * 100).toInt()}% · $rateLabel", (frac * 100).toInt(), 100
                            )
                        }
                    }
                    finalRes = res
                    if (res.isSuccess) break
                    attempt++
                }

                (finalRes ?: Result.failure(IllegalStateException("传输已取消"))).onSuccess { bytes ->
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
                            val toned = PostDarkroomAdjuster.apply(graded, studioState.postTone, cfg.showVintageDateImprint, cfg.dateTime)
                            val marked = if (cfg.watermarkEnabled) PentaxWatermarkRenderer.render(toned, cfg) else toned
                            MediaStoreSaver.saveBitmapAsJpeg(
                                ctx, marked, outName.substringBeforeLast('.') + "_pentax_wm.jpg",
                                exifToEmbed = mergedExif,
                                artist = cfg.signature,
                                copyright = cfg.copyrightNotice
                            )
                            if (marked !== toned) marked.recycle()
                            if (toned !== graded) toned.recycle()
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
                        updateTask(next.id) { it.copy(status = TransferStatus.COMPLETED, progress = 1f, savedPath = savedPath, errorMessage = null) }
                    }.onFailure { err ->
                        updateTask(next.id) { it.copy(status = TransferStatus.FAILED, errorMessage = err.localizedMessage) }
                    }
                }.onFailure { err ->
                    updateTask(next.id) { it.copy(status = TransferStatus.FAILED, errorMessage = "重试失败: ${err.localizedMessage}") }
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
        repo.apiClient.closeActiveLiveViewSocket()
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

    fun toggleAstroRedMode() {
        val next = !_astroRedMode.value
        _astroRedMode.value = next
        showToast(if (next) "已开启暗夜红光护眼模式（保护星空暗适应）" else "已恢复正常显示色彩")
    }

    fun setVolumeShutterEnabled(enabled: Boolean) {
        _volumeShutterEnabled.value = enabled
        repo.prefsStore.saveVolumeShutterEnabled(enabled)
        showToast(if (enabled) "音量键 / 蓝牙遥控快门已开启" else "音量键快门已关闭")
    }

    fun onVolumeShutterPressed(): Boolean {
        if (_activeTab.value != AppTab.REMOTE || !_volumeShutterEnabled.value) return false
        when (_remoteShotMode.value) {
            RemoteShotMode.SINGLE -> shootSingle()
            RemoteShotMode.AEB -> toggleAebBracketing()
            RemoteShotMode.INTERVAL -> toggleIntervalometer()
            RemoteShotMode.BULB -> toggleBulbExposure()
        }
        return true
    }

    fun updateAebConfig(shotCount: Int, stepEv: Float) {
        _aebState.update {
            it.copy(
                shotCount = shotCount.coerceIn(3, 7),
                stepEv = stepEv.coerceIn(0.3f, 3.0f)
            )
        }
    }

    fun computeResolvedAebSequence(
        shotCount: Int = _aebState.value.shotCount,
        stepEv: Float = _aebState.value.stepEv,
        xvList: List<String> = _shootingParams.value.xvList
    ): List<String> {
        val half = shotCount / 2
        val targetOffsets = (-half..half).map { idx -> idx * stepEv }
        return targetOffsets.map { ev ->
            val best = xvList.minByOrNull { s ->
                val v = s.removePrefix("+").toFloatOrNull() ?: 0f
                kotlin.math.abs(v - ev)
            }
            best ?: if (ev >= 0f) "+${"%.1f".format(ev)}" else "${"%.1f".format(ev)}"
        }.distinct()
    }

    fun toggleAebBracketing() {
        if (_aebState.value.running) {
            aebJob?.cancel()
            showToast("正在中止包围曝光并恢复机身原 EV...")
            return
        }
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        val cfg = _aebState.value
        val originalXv = _shootingParams.value.xv
        val resolvedSeq = computeResolvedAebSequence(cfg.shotCount, cfg.stepEv, _shootingParams.value.xvList)
        if (resolvedSeq.isEmpty()) return

        val actualTotal = resolvedSeq.size
        _aebState.update {
            it.copy(running = true, currentShot = 0, activeTotalShots = actualTotal, currentEvLabel = "准备中...")
        }
        aebJob = viewModelScope.launch {
            var completedAll = false
            var taken = 0
            try {
                resolvedSeq.forEachIndexed { i, xvStr ->
                    if (!isActive) return@forEachIndexed
                    _aebState.update { it.copy(currentShot = i + 1, currentEvLabel = "EV $xvStr") }
                    repo.updateParam("xv", xvStr)
                    _shootingParams.update { it.copy(xv = xvStr) }
                    delay(350)
                    val doAf = (i == 0) && !_manualLensMode.value && !_touchFocusHud.value.lockedForShutter
                    repo.triggerShutter(afEnabled = doAf)
                    taken = i + 1
                    delay(1200)
                }
                completedAll = isActive
            } finally {
                withContext(NonCancellable) {
                    repo.updateParam("xv", originalXv)
                    _shootingParams.update { it.copy(xv = originalXv) }
                    _aebState.update { it.copy(running = false, currentEvLabel = "") }
                    if (completedAll) {
                        showToast("包围曝光完成 ($taken 张)，已恢复机身 EV=$originalXv")
                    } else {
                        showToast("已中止包围曝光 (已拍 $taken 张)，已恢复机身 EV=$originalXv")
                    }
                    refreshPhotos()
                }
            }
        }
    }

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

    fun toggleSmartEdgeSnap() {
        val next = !_smartEdgeSnap.value
        _smartEdgeSnap.value = next
        showToast(if (next) "已开启高反差边缘吸附（减少反差对焦拉风箱）" else "已关闭边缘吸附（严格按点击位置对焦）")
    }

    fun clearTouchFocusLock() {
        _touchFocusHud.update { it.copy(lockedForShutter = false, status = FocusStatus.IDLE, message = "") }
    }

    fun touchFocusAtNormalized(xFraction: Float, yFraction: Float) {
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        if (_manualLensMode.value) { showToast("当前为手动头模式（AF 已跳过）"); return }
        val fx = xFraction.coerceIn(0.05f, 0.95f)
        val fy = yFraction.coerceIn(0.05f, 0.95f)

        viewModelScope.launch {
            val smart = LiveViewAnalyzer.findSmartFocusPoint(
                frame = rawLatestLiveFrame,
                tapXFraction = fx,
                tapYFraction = fy,
                enableSnap = _smartEdgeSnap.value
            )
            val snapFx = smart.xPercent / 100f
            val snapFy = smart.yPercent / 100f
            val startMsg = when {
                smart.lowContrastWarning -> "该处反差偏低，尝试对焦 (${smart.xPercent}%,${smart.yPercent}%)..."
                smart.snapped -> "已吸附边缘，正在对焦 (${smart.xPercent}%,${smart.yPercent}%)..."
                else -> "正在对焦 (${smart.xPercent}%,${smart.yPercent}%)..."
            }
            _touchFocusHud.value = TouchFocusHudState(
                status = FocusStatus.FOCUSING,
                xFraction = snapFx,
                yFraction = snapFy,
                message = startMsg,
                lockedForShutter = false
            )

            repo.triggerAutoFocus(smart.xPercent, smart.yPercent)
                .onSuccess { posAccepted ->
                    val okMsg = if (posAccepted) {
                        val snapTag = if (smart.snapped) "边缘吸附·" else ""
                        "${snapTag}已合焦 (${smart.xPercent}%,${smart.yPercent}%) · 快门免重对焦"
                    } else {
                        "已合焦（建议在机身将 LV 对焦区设为「选择」以支持点哪对哪）"
                    }
                    _touchFocusHud.value = TouchFocusHudState(
                        status = FocusStatus.LOCKED,
                        xFraction = snapFx,
                        yFraction = snapFy,
                        message = okMsg,
                        lockedForShutter = true
                    )
                }
                .onFailure { err ->
                    _touchFocusHud.value = TouchFocusHudState(
                        status = FocusStatus.FAILED,
                        xFraction = snapFx,
                        yFraction = snapFy,
                        message = "对焦未合焦: ${err.localizedMessage ?: "请点选高反差边缘"}",
                        lockedForShutter = false
                    )
                    showToast("对焦未成功: ${err.localizedMessage}")
                }
        }
    }

    fun triggerManualAf() {
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        if (_manualLensMode.value) { showToast("当前为手动头模式（AF 已跳过）"); return }
        val cur = _touchFocusHud.value
        val usePos = cur.status != FocusStatus.IDLE
        val px = if (usePos) (cur.xFraction * 100f).roundToInt().coerceIn(5, 95) else null
        val py = if (usePos) (cur.yFraction * 100f).roundToInt().coerceIn(5, 95) else null
        viewModelScope.launch {
            _touchFocusHud.update {
                it.copy(status = FocusStatus.FOCUSING, message = "正在执行 AF 对焦...", lockedForShutter = false)
            }
            repo.triggerAutoFocus(px, py)
                .onSuccess {
                    _touchFocusHud.update {
                        it.copy(status = FocusStatus.LOCKED, message = "AF 已合焦锁定 · 快门免重对焦", lockedForShutter = true)
                    }
                }
                .onFailure { err ->
                    _touchFocusHud.update {
                        it.copy(status = FocusStatus.FAILED, message = "AF 对焦失败", lockedForShutter = false)
                    }
                    showToast("AF 对焦失败: ${err.localizedMessage}")
                }
        }
    }

    fun shootSingle() {
        if (!_cameraInfo.value.connected) { showToast("请先连接 PENTAX K-1"); return }
        viewModelScope.launch {
            val skipAf = _manualLensMode.value || _touchFocusHud.value.lockedForShutter
            repo.triggerShutter(afEnabled = !skipAf)
                .onSuccess { mockPhoto ->
                    showToast(if (_touchFocusHud.value.lockedForShutter && !_manualLensMode.value) "快门已释放（沿用触屏合焦，未重拉风箱）" else "快门已释放")
                    if (mockPhoto != null) {
                        refreshPhotos()
                        if (_tetherEnabled.value) enqueueDownload(listOf(mockPhoto), DownloadQuality.VIEW)
                    }
                }
                .onFailure {
                    _touchFocusHud.update { hud -> hud.copy(lockedForShutter = false) }
                    showToast("快门未触发: ${it.localizedMessage}")
                }
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
            "bmp1600:${System.identityHashCode(state.sourceBitmap)}:${state.sourceFileName}"
        }

        if (spektraSessionHandle != 0L && spektraSessionSourceKey == desiredKey) {
            return@withContext spektraSessionHandle
        }

        releaseSpektraSession()
        val paramsJson = p.toEngineJson()
        val handle = if (state.isRawSource && !state.sourceRawPath.isNullOrBlank()) {
            _watermarkState.update { it.copy(spektraStatusText = "LibRaw 高速预览解码中...") }
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
            // Always cap preview upload to 1600px so mobile Vulkan never hits 65535 1D workgroup folding
            val previewIn = scaleBitmapToMaxEdge(bmp, 1600)
            try {
                SpektraNativeBridge.nativeOpenBitmap(previewIn, paramsJson)
            } finally {
                if (previewIn !== bmp) previewIn.recycle()
            }
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
        val keptCameraFilter = if (stock.isBw) cur.cameraFilter else BwCameraFilter.NONE
        val next = if (stock.isSlide) {
            cur.copy(
                filmStockId = stock.id, scanFilm = true,
                cameraFilter = keptCameraFilter,
                cFilterNeutral = null, mFilterNeutral = null, yFilterNeutral = null,
                mFilterShift = 0f, yFilterShift = 0f,
                sceneLatitudeActive = false, sceneLatitudeDeltaJson = null
            )
        } else {
            cur.copy(
                filmStockId = stock.id, scanFilm = false,
                printStockId = stock.defaultPrintPaperId ?: cur.printStockId,
                cameraFilter = keptCameraFilter,
                cFilterNeutral = null, mFilterNeutral = null, yFilterNeutral = null,
                mFilterShift = 0f, yFilterShift = 0f,
                sceneLatitudeActive = false, sceneLatitudeDeltaJson = null
            )
        }
        val hadCustomNeutral = cur.cFilterNeutral != null || cur.mFilterNeutral != null || cur.yFilterNeutral != null
        if (hadCustomNeutral) {
            // Engine carries previously-written c/m/y_filter_neutral across stock switches; recreate clean session
            releaseSpektraSession()
        }
        _watermarkState.update { it.copy(spektraParams = next, showAllStocksPicker = false) }
        triggerWatermarkPreview(30L)
    }

    fun selectSpektraPrintPaper(paperId: String) {
        val cur = _watermarkState.value.spektraParams
        val hadCustomNeutral = cur.cFilterNeutral != null || cur.mFilterNeutral != null || cur.yFilterNeutral != null
        if (hadCustomNeutral) {
            releaseSpektraSession()
        }
        _watermarkState.update {
            it.copy(spektraParams = cur.copy(printStockId = paperId, scanFilm = false, cFilterNeutral = null, mFilterNeutral = null, yFilterNeutral = null))
        }
        triggerWatermarkPreview(30L)
    }

    fun selectSpektraFilmFormat(format: FilmFormatSpec) {
        _watermarkState.update { it.copy(spektraParams = it.spektraParams.copy(filmFormat = format)) }
        triggerWatermarkPreview(30L)
    }

    fun selectBwCameraFilter(filter: BwCameraFilter) {
        _watermarkState.update { it.copy(spektraParams = it.spektraParams.copy(cameraFilter = filter)) }
        triggerWatermarkPreview(30L)
    }

    fun updatePostTone(transform: (PostToneConfig) -> PostToneConfig) {
        _watermarkState.update { it.copy(postTone = transform(it.postTone), fullscreenHighResBitmap = null) }
        triggerWatermarkPreview(35L)
    }

    fun resetPostTone() {
        _watermarkState.update { it.copy(postTone = PostToneConfig(), fullscreenHighResBitmap = null) }
        triggerWatermarkPreview(0L)
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
        if (cur.cFilterNeutral != null || cur.mFilterNeutral != null || cur.yFilterNeutral != null) {
            releaseSpektraSession()
        }
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

    private fun scaleBitmapToMaxEdge(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val dstW = (src.width * scale).roundToInt().coerceAtLeast(16)
        val dstH = (src.height * scale).roundToInt().coerceAtLeast(16)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }

    fun updateCropAspectRatio(ratio: CropAspectRatio) {
        _watermarkState.update {
            it.copy(
                cropConfig = it.cropConfig.copy(aspectRatio = ratio, panX = 0f, panY = 0f),
                fullscreenHighResBitmap = null
            )
        }
        triggerWatermarkPreview(35L)
    }

    fun updateStraightenDegrees(deg: Float, deferRender: Boolean = false) {
        val clamped = ((deg * 10f).roundToInt() / 10f).coerceIn(-15f, 15f)
        _watermarkState.update {
            it.copy(
                cropConfig = it.cropConfig.copy(straightenDegrees = clamped),
                fullscreenHighResBitmap = null
            )
        }
        if (!deferRender) triggerWatermarkPreview(40L)
    }

    fun updateCropPanZoom(cropZoom: Float, panX: Float, panY: Float) {
        _watermarkState.update {
            it.copy(
                cropConfig = it.cropConfig.copy(
                    cropZoom = cropZoom.coerceIn(1f, 4f),
                    panX = panX.coerceIn(-1f, 1f),
                    panY = panY.coerceIn(-1f, 1f)
                ),
                fullscreenHighResBitmap = null
            )
        }
    }

    fun commitCropPreview() {
        triggerWatermarkPreview(0L)
    }

    fun rotateQuarterTurn() {
        _watermarkState.update {
            it.copy(
                cropConfig = it.cropConfig.copy(
                    quarterTurns = (it.cropConfig.quarterTurns + 1) % 4,
                    panX = 0f,
                    panY = 0f
                ),
                fullscreenHighResBitmap = null
            )
        }
        triggerWatermarkPreview(20L)
    }

    fun resetCropAndStraighten() {
        _watermarkState.update { it.copy(cropConfig = CropStraightenConfig(), fullscreenHighResBitmap = null) }
        triggerWatermarkPreview(20L)
    }

    fun refreshFullscreenHighRes() {
        val st = _watermarkState.value
        if (st.isLoadingFullscreenHd || (st.sourceBitmap == null && !st.isRawSource)) return
        viewModelScope.launch {
            _watermarkState.update { it.copy(isLoadingFullscreenHd = true) }
            val hdState = st.copy(config = st.config.copy(highQualityExport = false))
            val bmp = renderFullPipeline(hdState, isExport = true, exportMaxEdgeOverride = 2800)?.first
            _watermarkState.update {
                it.copy(
                    fullscreenHighResBitmap = bmp ?: it.fullscreenHighResBitmap,
                    isLoadingFullscreenHd = false
                )
            }
            if (bmp != null) showToast("高清局部检视已就绪 (${bmp.width}×${bmp.height})，可放大至 8× 查看细节")
        }
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
                        sourceCameraPhoto = null,
                        sourceRawPath = null,
                        isRawSource = false,
                        unCroppedGradedBitmap = bmp,
                        fullscreenHighResBitmap = null,
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
                sourceCameraPhoto = null,
                sourceRawPath = rawFile.absolutePath,
                isRawSource = true,
                unCroppedGradedBitmap = fallbackBmp ?: state.sourceBitmap,
                fullscreenHighResBitmap = null,
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

    private suspend fun renderSpektraStage(
        state: WatermarkEditorState,
        isExport: Boolean,
        overrideSourceBitmap: Bitmap? = null,
        exportMaxEdgeOverride: Int? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (isExport) {
            if (!ensureSpektraEngineReady()) return@withContext null
            val p = state.spektraParams
            val paramsJson = p.toEngineJson()

            if (state.isRawSource && !state.sourceRawPath.isNullOrBlank()) {
                _watermarkState.update { it.copy(spektraStatusText = "LibRaw 全分辨率分带暗房渲染中 (零黑块防缩图)...") }
                val outBmp = if (exportMaxEdgeOverride != null) {
                    // HD fullscreen inspection (~2800px) -> fast half-size RAW then tiled render
                    val previewBmp = SpektraNativeBridge.nativeDecodeRawPreview(state.sourceRawPath, exportMaxEdgeOverride)
                    if (previewBmp != null) {
                        try {
                            SpektraNativeBridge.nativeRenderBitmapFullTiled(previewBmp, paramsJson)
                        } finally {
                            previewBmp.recycle()
                        }
                    } else null
                } else {
                    // 100% full 36.4MP native RAW resolution via overlapped-band Vulkan tiling!
                    SpektraNativeBridge.nativeRenderRawFullTiled(
                        filePath = state.sourceRawPath,
                        useHeadroom = true,
                        useCustomWb = p.rawCustomWb,
                        tempKelvin = p.rawTempKelvin.toDouble(),
                        tint = p.rawTint.toDouble(),
                        paramsJson = paramsJson
                    )
                }
                _watermarkState.update {
                    it.copy(spektraStatusText = "${it.spektraDeviceName.ifBlank { "Vulkan GPU" }} · 14-bit RAW 高速预览")
                }
                if (outBmp == null) {
                    val err = SpektraNativeBridge.nativeGetLastError().ifBlank { "全分辨率暗房渲染失败" }
                    _watermarkState.update { it.copy(spektraError = err) }
                }
                return@withContext outBmp
            } else {
                val baseBmp = overrideSourceBitmap ?: state.sourceBitmap ?: return@withContext null
                val targetBmp = if (exportMaxEdgeOverride != null) {
                    scaleBitmapToMaxEdge(baseBmp, exportMaxEdgeOverride)
                } else if (!state.config.highQualityExport) {
                    scaleBitmapToMaxEdge(baseBmp, 2560)
                } else {
                    // 100% original resolution! Overlapped-band Vulkan tiling handles up to 36.4MP seamlessly!
                    baseBmp
                }
                _watermarkState.update {
                    it.copy(spektraStatusText = "81 波长全分辨率分带渲染中 (${targetBmp.width}×${targetBmp.height})...")
                }
                val outBmp = try {
                    SpektraNativeBridge.nativeRenderBitmapFullTiled(targetBmp, paramsJson)
                } finally {
                    if (targetBmp !== baseBmp) targetBmp.recycle()
                    _watermarkState.update {
                        it.copy(spektraStatusText = "${it.spektraDeviceName.ifBlank { "Vulkan GPU" }} · JPG 线性还原")
                    }
                }
                if (outBmp == null) {
                    val err = SpektraNativeBridge.nativeGetLastError().ifBlank { "暗房分带渲染失败" }
                    _watermarkState.update { it.copy(spektraError = err) }
                }
                return@withContext outBmp
            }
        }

        // Fast interactive live preview (capped <= 1600px, zero 2D dispatch glitches!)
        val handle = ensureSpektraPreviewSessionOpened(state)
        if (handle == 0L) return@withContext null

        val params = state.spektraParams
        val prev = lastAppliedSpektraParams
        val printOnlyDelta = (prev != null && params.isPrintOnlyDeltaFrom(prev) && spektraHasRenderedLive)

        val deltaJson = if (printOnlyDelta && prev != null) {
            params.toPrintOnlyEngineJson(prev)
        } else {
            params.toEngineJson()
        }
        val setReply = SpektraNativeBridge.nativeSetParams(handle, deltaJson)
        if (!setReply.startsWith("OK|")) {
            _watermarkState.update { it.copy(spektraError = setReply.removePrefix("ERR|")) }
            return@withContext null
        }
        lastAppliedSpektraParams = params

        val bmp = SpektraNativeBridge.nativeRenderToBitmap(handle, "live", reprintOnly = printOnlyDelta)
        if (bmp != null) {
            spektraHasRenderedLive = true
            if (!printOnlyDelta) {
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

    private suspend fun renderFullPipeline(
        state: WatermarkEditorState,
        isExport: Boolean,
        overrideSourceBitmap: Bitmap? = null,
        exportMaxEdgeOverride: Int? = null
    ): Pair<Bitmap, Bitmap?>? {
        val src = overrideSourceBitmap ?: state.sourceBitmap
        if (src == null && !state.isRawSource) return null

        val maxEdge = if (isExport) {
            exportMaxEdgeOverride ?: if (state.config.highQualityExport) null else 2560
        } else 1600

        val baseGraded: Bitmap = if (state.colorGradeMode == ColorGradeMode.SPEKTRA_DARKROOM) {
            val spektraBmp = renderSpektraStage(state, isExport, overrideSourceBitmap, exportMaxEdgeOverride)
            if (spektraBmp != null) spektraBmp else (src ?: return null)
        } else {
            val nonNullSrc = src ?: return null
            val workingSrc = if (maxEdge != null) scaleBitmapToMaxEdge(nonNullSrc, maxEdge) else nonNullSrc
            val lutOut = CubeLutEngine.applyLut(workingSrc, state.activeLutPreset, state.lutIntensity)
            if (workingSrc !== nonNullSrc && lutOut !== workingSrc) workingSrc.recycle()
            lutOut
        }

        // Keep an uncropped graded copy for 60fps WYSIWYG interactive crop dragging during preview!
        val unCroppedCopyForUi = if (!isExport) {
            baseGraded.copy(Bitmap.Config.ARGB_8888, false)
        } else null

        val cropped = ImageCropStraighten.apply(baseGraded, state.cropConfig)
        if (baseGraded !== src && cropped !== baseGraded) baseGraded.recycle()

        val toned = PostDarkroomAdjuster.apply(
            source = cropped,
            tone = state.postTone,
            showVintageDate = state.config.showVintageDateImprint,
            dateStringRaw = state.config.dateTime
        )
        if (cropped !== src && toned !== cropped) cropped.recycle()

        val finalOut = if (state.config.watermarkEnabled) {
            val marked = PentaxWatermarkRenderer.render(toned, state.config, maxLongEdge = null)
            if (toned !== src && marked !== toned) toned.recycle()
            marked
        } else {
            if (toned === src) toned.copy(Bitmap.Config.ARGB_8888, false) else toned
        }

        return finalOut to unCroppedCopyForUi
    }

    fun triggerWatermarkPreview(debounceMs: Long = 35L) {
        val state = _watermarkState.value
        if (state.sourceBitmap == null && !state.isRawSource) return
        watermarkRenderJob?.cancel()
        watermarkRenderJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            _watermarkState.update { it.copy(isRendering = true) }
            val pair = renderFullPipeline(_watermarkState.value, isExport = false)
            _watermarkState.update {
                it.copy(
                    previewBitmap = pair?.first ?: it.previewBitmap,
                    unCroppedGradedBitmap = pair?.second ?: it.unCroppedGradedBitmap,
                    fullscreenHighResBitmap = null,
                    isRendering = false
                )
            }
        }
    }

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

                    val working = if (maxEdge != null) scaleBitmapToMaxEdge(bmp, maxEdge) else bmp
                    val graded: Bitmap = if (useSpektra) {
                        // Uses overlapped-band full-resolution tiled renderer: zero black-half bug & full resolution!
                        SpektraNativeBridge.nativeRenderBitmapFullTiled(working, spektraJson) ?: working
                    } else {
                        CubeLutEngine.applyLut(working, lutPreset, lutIntensity)
                    }

                    val cropped = ImageCropStraighten.apply(graded, cropRatioOnly)
                    val toned = PostDarkroomAdjuster.apply(
                        cropped, state.postTone, photoCfg.showVintageDateImprint, photoCfg.dateTime
                    )
                    val rendered = if (photoCfg.watermarkEnabled) PentaxWatermarkRenderer.render(toned, photoCfg) else toned
                    val outName = "PENTAX_BATCH_${System.currentTimeMillis() % 100000}_${idx + 1}.jpg"
                    val res = MediaStoreSaver.saveBitmapAsJpeg(
                        ctx, rendered, outName,
                        quality = if (baseConfig.highQualityExport) 97 else 90,
                        exifToEmbed = exif,
                        artist = photoCfg.signature,
                        copyright = photoCfg.copyrightNotice
                    )
                    if (rendered !== toned) rendered.recycle()
                    if (toned !== cropped) toned.recycle()
                    if (cropped !== graded) cropped.recycle()
                    if (graded !== working) graded.recycle()
                    if (working !== bmp) working.recycle()
                    bmp.recycle()
                    if (res.isSuccess) successCount++
                } catch (_: Exception) {
                }
            }

            _watermarkState.update { it.copy(batchProgress = null) }
            val modeLabel = if (useSpektra) "Spektra 暗房" else "3D LUT"
            showToast("$modeLabel 批量出片完成：已保存 $successCount/${uris.size} 张（原分辨率 + 保留 EXIF）")
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

            // If this photo came from K-1 gallery (preview 1600px) and user wants HQ export, automatically pull full-res from K-1!
            var pulledFullBmp: Bitmap? = null
            val camPhoto = state.sourceCameraPhoto
            if (camPhoto != null && !state.isRawSource && !camPhoto.isRaw && state.config.highQualityExport && _cameraInfo.value.connected && !_isMockMode.value) {
                showToast("正在从 K-1 拉取原图以保持 100% 原始分辨率...")
                val fullBytes = repo.downloadPhotoBytes(camPhoto, DownloadQuality.FULL_JPG) { _, _ -> }.getOrNull()
                if (fullBytes != null) {
                    pulledFullBmp = withContext(Dispatchers.IO) { BitmapOrientationUtil.decodeOrientedBitmap(fullBytes) }
                }
            }

            val pair = renderFullPipeline(state, isExport = true, overrideSourceBitmap = pulledFullBmp)
            val finalBitmap = pair?.first
            if (pulledFullBmp != null && pulledFullBmp !== finalBitmap) pulledFullBmp.recycle()

            if (finalBitmap == null) {
                _watermarkState.update { it.copy(isSaving = false) }
                showToast("渲染导出失败: ${_watermarkState.value.spektraError ?: "未知错误"}")
                return@launch
            }
            val outW = finalBitmap.width
            val outH = finalBitmap.height
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
                    exifToEmbed = exifToWrite,
                    artist = state.config.signature,
                    copyright = state.config.copyrightNotice
                )
            }
            if (finalBitmap !== state.sourceBitmap) finalBitmap.recycle()
            _watermarkState.update { it.copy(isSaving = false) }
            result.onSuccess { path -> showToast("已保存原分辨率成片 (${outW}×${outH} · 含EXIF)：$path") }
                .onFailure { err -> showToast("导出失败: ${err.localizedMessage}") }
        }
    }

    fun shareWatermarkedPhoto() {
        val state = _watermarkState.value
        if (state.sourceBitmap == null && !state.isRawSource) { showToast("请先选择一张照片"); return }
        viewModelScope.launch {
            _watermarkState.update { it.copy(isSharing = true) }
            val pair = renderFullPipeline(state, isExport = true, exportMaxEdgeOverride = 2400)
            val finalBitmap = pair?.first
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
