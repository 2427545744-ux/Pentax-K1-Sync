package com.pentax.k1sync.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.PhotoExif
import com.pentax.k1sync.model.ShootingParams
import com.pentax.k1sync.model.StorageSlot
import com.pentax.k1sync.model.StorageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PentaxK1ApiClient(
    private val wifiBinder: WifiNetworkBinder,
    private val baseUrl: String = "http://192.168.0.1"
) {
    private var activeWebSocket: WebSocket? = null

    private fun buildClient(readTimeoutSec: Long = 15L): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(wifiBinder.getSocketFactory())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun pingAndFetchProps(): Result<Pair<CameraInfo, ShootingParams>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/v1/props").get().build()
            buildClient(8).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())

                val model = json.optString("model", "PENTAX K-1").ifBlank { "PENTAX K-1" }
                val fw = json.optString("firmwareVersion", "")
                val battery = json.optInt("battery", 100)

                val storagesList = mutableListOf<StorageStatus>()
                val storagesArr = json.optJSONArray("storages")
                if (storagesArr != null) {
                    for (i in 0 until storagesArr.length()) {
                        val obj = storagesArr.optJSONObject(i) ?: continue
                        val name = obj.optString("name", "").lowercase()
                        val slot = if (name.contains("2")) StorageSlot.SD2 else StorageSlot.SD1
                        storagesList += StorageStatus(
                            slot = slot,
                            available = obj.optBoolean("available", obj.optBoolean("equipped", true)),
                            remainShots = obj.optInt("remain", 0),
                            format = obj.optString("format", "")
                        )
                    }
                }
                if (storagesList.isEmpty()) {
                    storagesList += StorageStatus(StorageSlot.SD1, available = true)
                    storagesList += StorageStatus(StorageSlot.SD2, available = false)
                }

                val defaultParams = ShootingParams()
                val params = ShootingParams(
                    av = json.optString("av", defaultParams.av),
                    tv = json.optString("tv", defaultParams.tv),
                    sv = json.optString("sv", defaultParams.sv),
                    xv = json.optString("xv", defaultParams.xv),
                    wbMode = json.optString("wbMode", defaultParams.wbMode),
                    exposureMode = json.optString("exposureMode", defaultParams.exposureMode),
                    avList = json.optJSONArray("avList").toStringList(defaultParams.avList),
                    tvList = json.optJSONArray("tvList").toStringList(defaultParams.tvList),
                    svList = json.optJSONArray("svList").toStringList(defaultParams.svList),
                    xvList = json.optJSONArray("xvList").toStringList(defaultParams.xvList),
                    wbList = json.optJSONArray("wbModeList").toStringList(defaultParams.wbList)
                )

                val info = CameraInfo(
                    connected = true,
                    isMock = false,
                    model = model,
                    firmwareVersion = fw,
                    batteryPercent = battery.coerceIn(0, 100),
                    storages = storagesList,
                    ssid = "PENTAX K-1"
                )
                info to params
            }
        }
    }

    suspend fun fetchCameraParams(current: ShootingParams): Result<ShootingParams> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/v1/params/camera").get().build()
            buildClient(8).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                current.copy(
                    av = json.optString("av", current.av),
                    tv = json.optString("tv", current.tv),
                    sv = json.optString("sv", current.sv),
                    xv = json.optString("xv", current.xv),
                    wbMode = json.optString("wbMode", current.wbMode),
                    exposureMode = json.optString("exposureMode", current.exposureMode)
                )
            }
        }
    }

    suspend fun updateCameraParam(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder().add(key, value).build()
            val req = Request.Builder().url("$baseUrl/v1/params/camera").put(body).build()
            buildClient(8).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("设置参数失败: HTTP ${resp.code}")
            }
        }
    }

    suspend fun syncPhoneTimeToCamera(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val formatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            val body = FormBody.Builder().add("datetime", formatted).build()
            val req = Request.Builder().url("$baseUrl/v1/params/device").put(body).build()
            buildClient(8).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("同步时间失败: HTTP ${resp.code}")
                formatted
            }
        }
    }

    suspend fun listPhotos(slot: StorageSlot): Result<List<CameraPhoto>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/v1/photos?storage=${slot.apiName}"
            val req = Request.Builder().url(url).get().build()
            buildClient(20).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("读取照片列表失败: HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                val dirs = json.optJSONArray("dirs") ?: JSONArray()
                val result = mutableListOf<CameraPhoto>()
                for (i in 0 until dirs.length()) {
                    val dirObj = dirs.optJSONObject(i) ?: continue
                    val dirName = dirObj.optString("name", "")
                    val files = dirObj.optJSONArray("files") ?: continue
                    val dateLabel = parsePentaxDirDate(dirName)
                    for (j in 0 until files.length()) {
                        val fileName = files.optString(j, "")
                        if (fileName.isBlank()) continue
                        val upper = fileName.uppercase(Locale.US)
                        val isRaw = upper.endsWith(".PEF") || upper.endsWith(".DNG")
                        val isImg = isRaw || upper.endsWith(".JPG") || upper.endsWith(".JPEG")
                        if (!isImg) continue
                        result += CameraPhoto(
                            dir = dirName,
                            file = fileName,
                            slot = slot,
                            dateGroup = dateLabel,
                            isRaw = isRaw
                        )
                    }
                }
                result.reversed()
            }
        }
    }

    suspend fun fetchPhotoInfo(photo: CameraPhoto): Result<PhotoExif> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/v1/photos/${photo.dir}/${photo.file}/info?storage=${photo.slot.apiName}"
            val req = Request.Builder().url(url).get().build()
            buildClient(10).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                val focal = json.optString("focalLength", json.optString("focal", ""))
                val rawDate = json.optString("datetime", "")
                val cleanDate = rawDate.replace("T", " ").replace("-", ".")
                PhotoExif(
                    cameraModel = json.optString("cameraModel", "PENTAX K-1").ifBlank { "PENTAX K-1" },
                    lensModel = json.optString("lensModel", "").trim(),
                    focalLength = focal,
                    aperture = json.optString("av", ""),
                    shutterSpeed = json.optString("tv", ""),
                    iso = json.optString("sv", ""),
                    exposureComp = json.optString("xv", ""),
                    dateTime = cleanDate
                )
            }
        }
    }

    suspend fun downloadPhotoBytes(
        photo: CameraPhoto,
        size: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/v1/photos/${photo.dir}/${photo.file}?size=$size&storage=${photo.slot.apiName}"
            val timeout = if (size == "full") 120L else 25L
            val req = Request.Builder().url(url).get().build()
            buildClient(timeout).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败: HTTP ${resp.code}")
                val body = resp.body ?: error("响应内容为空")
                val total = body.contentLength()
                val input = body.byteStream()
                val out = ByteArrayOutputStream(if (total > 0 && total < 80_000_000) total.toInt() else 65536)
                val buffer = ByteArray(16 * 1024)
                var readTotal = 0L
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    out.write(buffer, 0, count)
                    readTotal += count
                    onProgress?.invoke(readTotal, total)
                }
                out.toByteArray()
            }
        }
    }

    suspend fun triggerAutoFocus(xPercent: Int? = null, yPercent: Int? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                if (xPercent != null && yPercent != null) {
                    form.add("pos", "$xPercent,$yPercent")
                }
                val req = Request.Builder().url("$baseUrl/v1/lens/focus").post(form.build()).build()
                buildClient(10).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("对焦指令失败: HTTP ${resp.code}")
                }
            }
        }

    suspend fun triggerShutter(afEnabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val form = FormBody.Builder().add("af", if (afEnabled) "on" else "off").build()
            val req = Request.Builder().url("$baseUrl/v1/camera/shoot").post(form).build()
            buildClient(20).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("快门触发失败: HTTP ${resp.code}")
            }
        }
    }

    suspend fun startBulbExposure(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/camera/shoot/start")
                .post(FormBody.Builder().add("af", "off").build())
                .build()
            buildClient(15).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("B门启动失败: HTTP ${resp.code}")
            }
        }
    }

    suspend fun finishBulbExposure(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/camera/shoot/finish")
                .post(FormBody.Builder().build())
                .build()
            buildClient(15).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("B门结束失败: HTTP ${resp.code}")
            }
        }
    }

    suspend fun streamLiveView(onFrame: (Bitmap) -> Unit, onError: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$baseUrl/v1/liveview").get().build()
            try {
                buildClient(10).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onError("实时取景未开启或相机处于回放模式 (HTTP ${resp.code})")
                        return@withContext
                    }
                    val stream: InputStream = BufferedInputStream(resp.body?.byteStream() ?: return@withContext, 32 * 1024)
                    val frameBuffer = ByteArrayOutputStream(128 * 1024)
                    var inFrame = false
                    var prev = -1
                    var cur: Int

                    while (currentCoroutineContext().isActive) {
                        cur = stream.read()
                        if (cur == -1) break
                        if (!inFrame) {
                            if (prev == 0xFF && cur == 0xD8) {
                                frameBuffer.reset()
                                frameBuffer.write(0xFF)
                                frameBuffer.write(0xD8)
                                inFrame = true
                            }
                        } else {
                            frameBuffer.write(cur)
                            if (prev == 0xFF && cur == 0xD9) {
                                val bytes = frameBuffer.toByteArray()
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) onFrame(bmp)
                                inFrame = false
                            }
                        }
                        prev = cur
                    }
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    onError("实时取景流中断: ${e.localizedMessage ?: "未知错误"}")
                }
            }
        }

    fun startChangesListener(
        onNewPhoto: (dir: String, file: String, slot: StorageSlot) -> Unit,
        onStateChanged: () -> Unit
    ) {
        stopChangesListener()
        val wsUrl = baseUrl.replace("http://", "ws://") + "/v1/changes"
        val req = Request.Builder().url(wsUrl).build()
        activeWebSocket = buildClient(0).newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val dir = json.optString("dir", "")
                    val file = json.optString("file", "")
                    if (dir.isNotBlank() && file.isNotBlank()) {
                        val storageName = json.optString("storage", "sd1")
                        val slot = if (storageName.contains("2")) StorageSlot.SD2 else StorageSlot.SD1
                        onNewPhoto(dir, file, slot)
                    } else {
                        onStateChanged()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        })
    }

    fun stopChangesListener() {
        try {
            activeWebSocket?.close(1000, "Stopped")
        } catch (_: Exception) {
        }
        activeWebSocket = null
    }

    private fun JSONArray?.toStringList(fallback: List<String>): List<String> {
        if (this == null || length() == 0) return fallback
        val list = mutableListOf<String>()
        for (i in 0 until length()) {
            val v = optString(i, "")
            if (v.isNotBlank()) list += v
        }
        return if (list.isEmpty()) fallback else list
    }

    private fun parsePentaxDirDate(dirName: String): String {
        val parts = dirName.split("_")
        if (parts.size == 2 && parts[1].length == 4) {
            return "${parts[1].substring(0, 2)}月${parts[1].substring(2, 4)}日 · $dirName"
        }
        return dirName.ifBlank { "相机相册" }
    }
}
