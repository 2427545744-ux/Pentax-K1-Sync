package com.pentax.k1sync.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.PhotoExif
import com.pentax.k1sync.model.ShootingParams
import com.pentax.k1sync.model.StorageSlot
import com.pentax.k1sync.model.StorageStatus
import com.pentax.k1sync.util.ApiDebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.net.Network
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PentaxK1ApiClient(
    private val wifiBinder: WifiNetworkBinder,
    private val baseUrl: String = "http://192.168.0.1"
) {

    @Volatile
    private var activeLiveViewSocket: Socket? = null

    /**
     * Immediately closes any blocking Live View TCP socket so K-1 releases its single stream channel
     * without waiting for a 10-second socket read timeout.
     */
    fun closeActiveLiveViewSocket() {
        val sock = activeLiveViewSocket
        activeLiveViewSocket = null
        if (sock != null) {
            try { sock.close() } catch (_: Exception) {}
        }
    }
    private var activeWebSocket: WebSocket? = null
    private val clientLock = Any()
    private var sharedBaseClient: OkHttpClient? = null
    private var sharedClientNetwork: Network? = null

    /**
     * Reuses a single OkHttpClient (ConnectionPool + Dispatcher) bound to the active K-1 Network,
     * only recreating the base client when the bound Wi-Fi Network instance changes.
     */
    private fun getSharedBaseClient(): OkHttpClient {
        val currentNet = wifiBinder.boundNetwork.value
        synchronized(clientLock) {
            val existing = sharedBaseClient
            if (existing != null && sharedClientNetwork === currentNet) {
                return existing
            }
            val dispatcher = Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 6
            }
            val created = OkHttpClient.Builder()
                .socketFactory(wifiBinder.getSocketFactory())
                .dns(wifiBinder.getDns())
                .connectionPool(ConnectionPool(6, 45, TimeUnit.SECONDS))
                .dispatcher(dispatcher)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            sharedBaseClient = created
            sharedClientNetwork = currentNet
            return created
        }
    }

    private fun buildClient(readTimeoutSec: Long = 15L, connectTimeoutSec: Long = 5L): OkHttpClient {
        return getSharedBaseClient().newBuilder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()
    }

    private inline fun <T> executeLogged(
        method: String,
        path: String,
        readTimeoutSec: Long = 10L,
        connectTimeoutSec: Long = 5L,
        request: Request,
        silentLog: Boolean = false,
        parser: (Response, String) -> T
    ): T {
        val t0 = System.currentTimeMillis()
        try {
            buildClient(readTimeoutSec, connectTimeoutSec).newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - t0
                if (!silentLog || !resp.isSuccessful) {
                    ApiDebugLogger.record(method, path, resp.code, elapsed, text.ifBlank { "(empty)" })
                }
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(100)}")
                val trimmed = text.trim()
                if (trimmed.startsWith("{")) {
                    val json = try { JSONObject(trimmed) } catch (_: Exception) { null }
                    if (json != null && json.has("errCode")) {
                        val errCode = json.optInt("errCode", 200)
                        if (errCode != 200 && errCode != 0) {
                            val errMsg = json.optString("errMsg", "Camera rejected command")
                            error("K-1 拒绝操作 (errCode $errCode: $errMsg)")
                        }
                    }
                }
                return parser(resp, text)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            ApiDebugLogger.record(method, path, 0, elapsed, e.localizedMessage ?: e.javaClass.simpleName)
            throw e
        }
    }

    suspend fun pingAndFetchProps(fastProbe: Boolean = false): Result<Pair<CameraInfo, ShootingParams>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = "/v1/props"
                val req = Request.Builder().url("$baseUrl$path").get().build()
                val cTimeout = if (fastProbe) 2L else 5L
                val rTimeout = if (fastProbe) 3L else 8L
                executeLogged("GET", path, rTimeout, cTimeout, req) { _, bodyStr ->
                    val json = JSONObject(bodyStr)
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
                        wbMode = json.optString("WBMode", "").ifBlank { json.optString("wbMode", defaultParams.wbMode) },
                        exposureMode = json.optString("exposureMode", defaultParams.exposureMode),
                        avList = json.optJSONArray("avList").toStringList(defaultParams.avList),
                        tvList = json.optJSONArray("tvList").toStringList(defaultParams.tvList),
                        svList = json.optJSONArray("svList").toStringList(defaultParams.svList),
                        xvList = json.optJSONArray("xvList").toStringList(defaultParams.xvList),
                        wbList = (json.optJSONArray("WBModeList") ?: json.optJSONArray("wbModeList")).toStringList(defaultParams.wbList)
                    )

                    val info = CameraInfo(
                        connected = true, isMock = false, model = model, firmwareVersion = fw,
                        batteryPercent = battery.coerceIn(0, 100), storages = storagesList, ssid = "PENTAX K-1"
                    )
                    info to params
                }
            }
        }

    suspend fun fetchCameraParams(current: ShootingParams): Result<ShootingParams> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/v1/params/camera"
            val req = Request.Builder().url("$baseUrl$path").get().build()
            executeLogged("GET", path, 8, 5, req) { _, bodyStr ->
                val json = JSONObject(bodyStr)
                current.copy(
                    av = json.optString("av", current.av),
                    tv = json.optString("tv", current.tv),
                    sv = json.optString("sv", current.sv),
                    xv = json.optString("xv", current.xv),
                    wbMode = json.optString("WBMode", "").ifBlank { json.optString("wbMode", current.wbMode) },
                    exposureMode = json.optString("exposureMode", current.exposureMode)
                )
            }
        }
    }

    suspend fun updateCameraParam(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val wireKey = if (key.equals("wbMode", ignoreCase = true)) "WBMode" else key
            val primary = runCatching {
                val body = FormBody.Builder().add(wireKey, value).build()
                val req = Request.Builder().url("$baseUrl/v1/params/camera").put(body).build()
                executeLogged("PUT", "/v1/params/camera ($wireKey=$value)", 8, 5, req) { _, _ -> Unit }
            }
            if (primary.isSuccess) return@runCatching
            if (wireKey == "WBMode") {
                val fallbackBody = FormBody.Builder().add("wbMode", value).build()
                val fallbackReq = Request.Builder().url("$baseUrl/v1/params/camera").put(fallbackBody).build()
                executeLogged("PUT", "/v1/params/camera (wbMode=$value)", 8, 5, fallbackReq) { _, _ -> Unit }
                return@runCatching
            }
            throw primary.exceptionOrNull()!!
        }
    }

    suspend fun syncPhoneTimeToCamera(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val formatted = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            val body = FormBody.Builder().add("datetime", formatted).build()
            val req = Request.Builder().url("$baseUrl/v1/params/device").put(body).build()
            executeLogged("PUT", "/v1/params/device", 8, 5, req) { _, _ -> formatted }
        }
    }

    suspend fun listPhotos(slot: StorageSlot, silentLog: Boolean = false): Result<List<CameraPhoto>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/v1/photos?storage=${slot.apiName}"
            val req = Request.Builder().url("$baseUrl$path").get().build()
            val readSec = if (silentLog) 10L else 20L
            executeLogged("GET", path, readSec, 5, req, silentLog = silentLog) { _, bodyStr ->
                val json = JSONObject(bodyStr)
                val dirs = json.optJSONArray("dirs") ?: JSONArray()
                val result = mutableListOf<CameraPhoto>()
                for (i in 0 until dirs.length()) {
                    val dirObj = dirs.optJSONObject(i) ?: continue
                    val dirName = dirObj.optString("name", "")
                    val files = dirObj.optJSONArray("files") ?: continue
                    val dateLabel = CameraPhoto.formatUnifiedDateGroup(dirName)
                    for (j in 0 until files.length()) {
                        val fileName = files.optString(j, "")
                        if (fileName.isBlank()) continue
                        val upper = fileName.uppercase(Locale.US)
                        val isRaw = upper.endsWith(".PEF") || upper.endsWith(".DNG")
                        val isImg = isRaw || upper.endsWith(".JPG") || upper.endsWith(".JPEG")
                        if (!isImg) continue
                        val sortKey = CameraPhoto.computeChronologicalSortKey(dirName, fileName)
                        result += CameraPhoto(
                            dir = dirName,
                            file = fileName,
                            slot = slot,
                            dateGroup = dateLabel,
                            isRaw = isRaw,
                            sortKey = sortKey
                        )
                    }
                }
                result.reversed()
            }
        }
    }

    suspend fun fetchPhotoInfo(photo: CameraPhoto): Result<PhotoExif> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/v1/photos/${photo.dir}/${photo.file}/info?storage=${photo.slot.apiName}"
            val req = Request.Builder().url("$baseUrl$path").get().build()
            executeLogged("GET", path, 10, 5, req) { _, bodyStr ->
                val json = JSONObject(bodyStr)
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
            val path = "/v1/photos/${photo.dir}/${photo.file}?size=$size&storage=${photo.slot.apiName}"
            val timeout = if (size == "full") 120L else 25L
            val reqBuilder = Request.Builder().url("$baseUrl$path").get()
            if (size == "full") reqBuilder.header("Connection", "close")
            val req = reqBuilder.build()
            val t0 = System.currentTimeMillis()
            buildClient(timeout).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ApiDebugLogger.record("GET", path, resp.code, System.currentTimeMillis() - t0, "download failed")
                    error("下载失败: HTTP ${resp.code}")
                }
                val body = resp.body ?: error("响应内容为空")
                val total = body.contentLength()
                val input = body.byteStream()
                val out = ByteArrayOutputStream(if (total > 0 && total < 80_000_000) total.toInt() else 65536)
                val buffer = ByteArray(64 * 1024)
                var readTotal = 0L
                var lastProgressMs = 0L
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    out.write(buffer, 0, count)
                    readTotal += count
                    if (onProgress != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs >= 150L || (total > 0L && readTotal >= total)) {
                            lastProgressMs = now
                            onProgress.invoke(readTotal, total)
                        }
                    }
                }
                onProgress?.invoke(readTotal, if (total > 0L) total else readTotal)
                if (size != "thumb") {
                    ApiDebugLogger.record("GET", path, resp.code, System.currentTimeMillis() - t0, "bytes=$readTotal")
                }
                out.toByteArray()
            }
        }
    }

    /**
     * Triggers Live View AF on Pentax K-1.
     * - Uses addEncoded("pos", "X,Y") so the wire payload is literal `pos=50,50` (NOT `pos=50%2C50`,
     *   which Pentax K-1's embedded C parser rejects!).
     * - Checks JSON `errCode` and `focused` honestly instead of treating every HTTP 200 as success.
     * - Returns Result<Boolean>: true = coordinate pos=X,Y accepted & focused, false = center-point fallback focused.
     */
    suspend fun triggerAutoFocus(xPercent: Int? = null, yPercent: Int? = null): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (xPercent != null && yPercent != null) {
                    // Clamp to K-1 real hardware focusEffectiveArea [70, 64] (X: 15..85, Y: 18..82)
                    val posVal = "${xPercent.coerceIn(15, 85)},${yPercent.coerceIn(18, 82)}"
                    // CRITICAL: addEncoded keeps the literal comma ',' unencoded (`pos=50,50`)!
                    val form = FormBody.Builder().addEncoded("pos", posVal).build()
                    val req = Request.Builder().url("$baseUrl/v1/lens/focus").post(form).build()
                    val firstTry = runCatching {
                        executeLogged("POST", "/v1/lens/focus (pos=$posVal)", 12, 5, req) { _, bodyStr ->
                            parseFocusResult(bodyStr)
                        }
                    }
                    if (firstTry.isSuccess) {
                        val focused = firstTry.getOrThrow()
                        if (!focused) error("相机未合焦 (focused=false)，请点选高反差边缘或确认机身已开启 LV")
                        return@runCatching true
                    }
                    // If K-1 rejected `pos` (e.g. LV AF mode not set to Select), fall through to center AF
                    val msg = firstTry.exceptionOrNull()?.message.orEmpty()
                    if (msg.contains("focused=false")) throw firstTry.exceptionOrNull()!!
                }

                // Fallback: trigger AF at camera's active focus point
                val fallbackReq = Request.Builder()
                    .url("$baseUrl/v1/lens/focus")
                    .post(FormBody.Builder().build())
                    .build()
                val focused = executeLogged("POST", "/v1/lens/focus (active-point)", 12, 5, fallbackReq) { _, bodyStr ->
                    parseFocusResult(bodyStr)
                }
                if (!focused) error("相机未合焦 (focused=false)，请确认机身已处于 Live View 且对焦拨杆在 AF")
                false
            }
        }

    private fun parseFocusResult(bodyStr: String): Boolean {
        val trimmed = bodyStr.trim()
        if (!trimmed.startsWith("{")) return true
        val json = try { JSONObject(trimmed) } catch (_: Exception) { return true }
        return if (json.has("focused")) json.optBoolean("focused", true) else true
    }

    /**
     * Triggers shutter release on Pentax K-1.
     * Honestly inspects JSON `errCode`, `captured`, and `focused` so the app NEVER falsely reports
     * "快门已释放" when the camera actually refused to take a photo!
     */
    suspend fun triggerShutter(afEnabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val afVal = if (afEnabled) "on" else "off"
            val req = Request.Builder()
                .url("$baseUrl/v1/camera/shoot")
                .post(FormBody.Builder().add("af", afVal).build())
                .build()

            val primary = runCatching {
                executeLogged("POST", "/v1/camera/shoot (af=$afVal)", 20, 5, req) { _, bodyStr ->
                    validateShootBody(bodyStr, afEnabled)
                }
            }
            if (primary.isSuccess) return@runCatching

            // Some K-1 firmware states reject `af=off` with errCode 400; retry once with empty POST body
            val primaryMsg = primary.exceptionOrNull()?.message.orEmpty()
            if (!afEnabled && primaryMsg.contains("400")) {
                val emptyReq = Request.Builder()
                    .url("$baseUrl/v1/camera/shoot")
                    .post(FormBody.Builder().build())
                    .build()
                executeLogged("POST", "/v1/camera/shoot (default-fallback)", 20, 5, emptyReq) { _, bodyStr ->
                    validateShootBody(bodyStr, afEnabled = false)
                }
                return@runCatching
            }

            throw primary.exceptionOrNull()!!
        }
    }

    /**
     * Pentax K-1 returns `errCode: 200` asynchronously (~28ms) with `captured: false` even when
     * the mechanical shutter releases normally (true rejections return `errCode: 412/503`, already
     * caught in executeLogged). Only treat as failure when AF was requested and explicitly failed.
     */
    private fun validateShootBody(bodyStr: String, afEnabled: Boolean) {
        val trimmed = bodyStr.trim()
        if (!trimmed.startsWith("{")) return
        val json = try { JSONObject(trimmed) } catch (_: Exception) { return }
        val focused = if (json.has("focused")) json.optBoolean("focused", true) else true
        val captured = if (json.has("captured")) json.optBoolean("captured", true) else true
        if (afEnabled && !focused && !captured) {
            error("快门未释放：自动对焦未合焦 (focused=false)，请点选高反差边缘后重试")
        }
    }

    suspend fun startBulbExposure(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/camera/shoot/start")
                .post(FormBody.Builder().add("af", "off").build())
                .build()
            executeLogged("POST", "/v1/camera/shoot/start", 15, 5, req) { _, _ -> Unit }
        }
    }

    suspend fun finishBulbExposure(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/camera/shoot/finish")
                .post(FormBody.Builder().build())
                .build()
            executeLogged("POST", "/v1/camera/shoot/finish", 15, 5, req) { _, _ -> Unit }
        }
    }

    /**
     * Hardware-compatible Pentax K-1 Live View MJPEG stream reader:
     * 1. Immediately closes any prior blocking TCP socket so retries never collide with an orphaned stream.
     * 2. Uses `Connection: keep-alive` (fixes Ricoh/Pentax embedded servers rejecting `Connection: close` streams).
     * 3. Auto-probes `/v1/liveview` first and falls back to `/v1/display` if 404/405/501 or 0 frames.
     * 4. Uses Content-Length fast path for multipart MJPEG AND resilient marker-aware JPEG framing as fallback.
     */
    suspend fun streamLiveView(onFrame: (Bitmap) -> Unit, onError: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            closeActiveLiveViewSocket()

            val uri = try { URI(baseUrl) } catch (_: Exception) { URI("http://192.168.0.1") }
            val host = uri.host ?: "192.168.0.1"
            val port = if (uri.port > 0) uri.port else 80
            val endpoints = listOf("/v1/liveview", "/v1/display")
            var lastDiagnostic = ""

            for ((epIdx, endpoint) in endpoints.withIndex()) {
                if (!currentCoroutineContext().isActive) return@withContext
                val socket = wifiBinder.getSocketFactory().createSocket()
                activeLiveViewSocket = socket
                var totalBytesRead = 0L
                var decodedFrames = 0

                try {
                    socket.soTimeout = 10_000
                    socket.connect(InetSocketAddress(host, port), 5_000)

                    val reqStr = buildString {
                        append("GET ").append(endpoint).append(" HTTP/1.1\r\n")
                        append("Host: ").append(host).append("\r\n")
                        append("Accept: multipart/x-mixed-replace, image/jpeg, */*\r\n")
                        append("Connection: keep-alive\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(reqStr.toByteArray(Charsets.US_ASCII))
                        flush()
                    }

                    val rawIn = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                    val statusLine = readAsciiLine(rawIn)
                    if (statusLine.isNullOrBlank()) {
                        lastDiagnostic = "$endpoint 空响应"
                        ApiDebugLogger.record("GET", endpoint, 0, 0, "empty HTTP status")
                        if (epIdx < endpoints.lastIndex) continue
                        onError("相机未响应取景流 ($lastDiagnostic)。请在 K-1 机身关闭「构图微调」并退出回放模式后重试。")
                        return@withContext
                    }

                    val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readAsciiLine(rawIn) ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                                line.substring(colon + 1).trim()
                        }
                    }

                    val contentType = headers["content-type"].orEmpty()
                    val transferEnc = headers["transfer-encoding"].orEmpty()
                    val isChunked = transferEnc.contains("chunked", ignoreCase = true)
                    ApiDebugLogger.record(
                        "GET", endpoint, statusCode, 0,
                        "ct='$contentType' te='$transferEnc' cl='${headers["content-length"].orEmpty()}'"
                    )

                    if (statusCode !in 200..299) {
                        val bodySnippet = ByteArray(256).let { buf ->
                            val n = try { rawIn.read(buf) } catch (_: Exception) { -1 }
                            if (n > 0) String(buf, 0, n, Charsets.UTF_8).trim() else ""
                        }
                        lastDiagnostic = "$endpoint HTTP $statusCode $bodySnippet"
                        ApiDebugLogger.record("GET", endpoint, statusCode, 0, "errBody=$bodySnippet")
                        if (statusCode in listOf(404, 405, 501) && epIdx < endpoints.lastIndex) {
                            continue
                        }
                        val hint = when (statusCode) {
                            412, 503 -> "K-1 拒绝实时取景 ($endpoint HTTP $statusCode)：请在机身菜单关闭「构图微调」并退出回放模式。"
                            else -> "实时取景启动失败 ($endpoint HTTP $statusCode) $bodySnippet"
                        }
                        onError(hint)
                        return@withContext
                    }

                    // Guard against HTTP 200 JSON error body instead of MJPEG stream!
                    if (contentType.contains("json", ignoreCase = true)) {
                        val bodySnippet = ByteArray(256).let { buf ->
                            val n = try { rawIn.read(buf) } catch (_: Exception) { -1 }
                            if (n > 0) String(buf, 0, n, Charsets.UTF_8).trim() else ""
                        }
                        lastDiagnostic = "$endpoint JSON: $bodySnippet"
                        ApiDebugLogger.record("GET", endpoint, statusCode, 0, "jsonBody=$bodySnippet")
                        if (epIdx < endpoints.lastIndex) continue
                        onError("K-1 返回错误而非视频流 ($bodySnippet)。请先在机身关闭「构图微调」并开启 LV。")
                        return@withContext
                    }

                    val bodyStream: InputStream = if (isChunked) ChunkedHttpInputStream(rawIn) else rawIn
                    val bufferedBody = BufferedInputStream(bodyStream, 64 * 1024)
                    val isMultipart = contentType.contains("multipart", ignoreCase = true)

                    while (currentCoroutineContext().isActive) {
                        val jpegBytes = if (isMultipart) {
                            readNextMultipartOrJpegFrame(bufferedBody) { n -> totalBytesRead += n }
                        } else {
                            readNextCompleteJpeg(bufferedBody) { n -> totalBytesRead += n }
                        } ?: break

                        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bmp != null) {
                            decodedFrames++
                            if (decodedFrames == 1) {
                                ApiDebugLogger.record(
                                    "GET", endpoint, statusCode, 0,
                                    "first frame decoded ${bmp.width}x${bmp.height} (${jpegBytes.size} B)"
                                )
                            }
                            onFrame(bmp)
                        }
                    }

                    if (decodedFrames > 0) {
                        // Stream ran normally and then ended or was cancelled
                        if (currentCoroutineContext().isActive) {
                            onError("实时取景流已断开（共收到 $decodedFrames 帧），点击下方重新连接。")
                        }
                        return@withContext
                    } else {
                        lastDiagnostic = "$endpoint HTTP $statusCode 0帧 (${totalBytesRead}B, ct=$contentType)"
                        ApiDebugLogger.record("GET", endpoint, statusCode, 0, "EOF 0 frames ($lastDiagnostic)")
                        if (epIdx < endpoints.lastIndex) continue
                        if (currentCoroutineContext().isActive) {
                            onError("取景流未返回有效画面 ($lastDiagnostic)。请在 K-1 机身关闭「构图微调」并手动按 LV 键后重试。")
                        }
                        return@withContext
                    }
                } catch (e: Exception) {
                    if (!currentCoroutineContext().isActive) {
                        ApiDebugLogger.record("GET", endpoint, 200, 0, "closed cleanly (frames=$decodedFrames)")
                        return@withContext
                    }
                    val msg = e.localizedMessage ?: e.javaClass.simpleName
                    lastDiagnostic = "$endpoint $msg (frames=$decodedFrames, bytes=$totalBytesRead)"
                    ApiDebugLogger.record("GET", endpoint, 0, 0, "ex=$lastDiagnostic")
                    if (decodedFrames == 0 && epIdx < endpoints.lastIndex) {
                        continue
                    }
                    onError("实时取景中断 ($endpoint): $msg（如刚重连或机身开启了「构图微调」，请关闭后重试）")
                    return@withContext
                } finally {
                    if (activeLiveViewSocket === socket) activeLiveViewSocket = null
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    /**
     * Multipart MJPEG frame reader: reads part headers looking for `Content-Length`,
     * and if present reads the exact JPEG byte slice directly (fastest & immune to marker quirks).
     * If a part omits `Content-Length`, seamlessly falls back to `readNextCompleteJpeg()`.
     */
    private fun readNextMultipartOrJpegFrame(input: InputStream, onBytes: (Int) -> Unit): ByteArray? {
        var contentLength = -1
        var linesScanned = 0
        while (linesScanned < 32) {
            input.mark(4)
            val b0 = input.read()
            val b1 = input.read()
            if (b0 == -1) return null
            if (b0 == 0xFF && b1 == 0xD8) {
                input.reset()
                return readNextCompleteJpeg(input, onBytes)
            }
            input.reset()

            val line = readAsciiLine(input) ?: return null
            onBytes(line.length + 2)
            linesScanned++
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (contentLength in 256..4_000_000) {
                    val buf = ByteArray(contentLength)
                    if (!readFully(input, buf)) return null
                    onBytes(contentLength)
                    return buf
                }
                if (linesScanned > 1) {
                    // Passed headers without Content-Length -> parse raw JPEG markers
                    return readNextCompleteJpeg(input, onBytes)
                }
            } else if (trimmed.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = trimmed.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
        return readNextCompleteJpeg(input, onBytes)
    }

    /**
     * Reads one complete JPEG image from a raw concatenated JPEG stream.
     * Skips over APPn / DQT / DHT / SOF / COM segment lengths cleanly so an embedded EXIF
     * thumbnail (which has its own FF D8 ... FF D9) can NEVER cause false early termination.
     */
    private fun readNextCompleteJpeg(input: InputStream, onBytes: (Int) -> Unit): ByteArray? {
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return null
            onBytes(1)
            if (prev == 0xFF && b == 0xD8) break
            prev = b
        }

        val out = ByteArrayOutputStream(64 * 1024)
        out.write(0xFF)
        out.write(0xD8)

        while (true) {
            val b = input.read()
            if (b == -1) return null
            onBytes(1)
            out.write(b)

            if (b != 0xFF) continue
            var marker = input.read()
            while (marker == 0xFF) {
                onBytes(1)
                out.write(marker)
                marker = input.read()
            }
            if (marker == -1) return null
            onBytes(1)
            out.write(marker)

            if (marker == 0x00 || marker == 0x01 || (marker in 0xD0..0xD8)) {
                continue
            }
            if (marker == 0xD9) {
                return out.toByteArray()
            }

            val lenHi = input.read()
            val lenLo = input.read()
            if (lenHi == -1 || lenLo == -1) return null
            onBytes(2)
            out.write(lenHi)
            out.write(lenLo)

            val segLen = ((lenHi shl 8) or lenLo) - 2
            if (segLen < 0 || segLen > 2_000_000) {
                // Resync to next JPEG frame instead of killing the entire Live View stream
                return ByteArray(0)
            }
            if (segLen > 0) {
                val payload = ByteArray(segLen)
                if (!readFully(input, payload)) return null
                onBytes(segLen)
                out.write(payload)
            }

            if (marker == 0xDA) {
                var scanPrev = -1
                while (true) {
                    val sb = input.read()
                    if (sb == -1) return null
                    onBytes(1)
                    out.write(sb)
                    if (scanPrev == 0xFF && sb == 0xD9) {
                        return out.toByteArray()
                    }
                    scanPrev = if (scanPrev == 0xFF && sb == 0x00) -1 else sb
                    if (out.size() > 4_000_000) return ByteArray(0)
                }
            }
        }
    }

    private fun readFully(input: InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val n = input.read(dest, offset, dest.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private class ChunkedHttpInputStream(private val raw: InputStream) : InputStream() {
        private var chunkRemaining = 0
        private var eof = false

        private fun nextChunk(): Boolean {
            if (eof) return false
            var line: String
            while (true) {
                val sb = StringBuilder(16)
                var b = raw.read()
                if (b == -1) { eof = true; return false }
                while (b != -1 && b != '\n'.code) {
                    if (b != '\r'.code) sb.append(b.toChar())
                    b = raw.read()
                }
                line = sb.toString().substringBefore(';').trim()
                if (line.isNotEmpty()) break
                if (b == -1) { eof = true; return false }
            }
            val size = line.toIntOrNull(16) ?: throw EOFException("Invalid chunk size: $line")
            if (size <= 0) {
                eof = true
                return false
            }
            chunkRemaining = size
            return true
        }

        override fun read(): Int {
            if (eof) return -1
            if (chunkRemaining == 0 && !nextChunk()) return -1
            val b = raw.read()
            if (b == -1) { eof = true; return -1 }
            chunkRemaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            if (chunkRemaining == 0 && !nextChunk()) return -1
            val toRead = minOf(len, chunkRemaining)
            val n = raw.read(b, off, toRead)
            if (n == -1) { eof = true; return -1 }
            chunkRemaining -= n
            return n
        }
    }

    fun startChangesListener(
        onNewPhoto: (dir: String, file: String, slot: StorageSlot) -> Unit,
        onCaptureOrStorageChanged: () -> Unit,
        onStateChanged: () -> Unit,
        onDisconnected: () -> Unit = {}
    ) {
        stopChangesListener()
        val wsUrl = baseUrl.replace("http://", "ws://") + "/v1/changes"
        val req = Request.Builder().url(wsUrl).build()
        val wsClient = getSharedBaseClient().newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        activeWebSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ApiDebugLogger.record("WS", "/v1/changes", response.code, 0, "connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                ApiDebugLogger.record("WS", "/v1/changes", 200, 0, text)
                try {
                    val json = JSONObject(text)
                    // 1. Check direct or nested dir+file photo payload
                    val photoObj = when {
                        json.has("dir") && json.has("file") -> json
                        json.optJSONObject("photo") != null -> json.optJSONObject("photo")
                        json.optJSONObject("image") != null -> json.optJSONObject("image")
                        json.optJSONObject("latest") != null -> json.optJSONObject("latest")
                        else -> null
                    }
                    val dir = photoObj?.optString("dir", "").orEmpty()
                    val file = photoObj?.optString("file", "").orEmpty()
                    if (dir.isNotBlank() && file.isNotBlank()) {
                        val storageName = photoObj?.optString("storage", json.optString("storage", "sd1")).orEmpty()
                        val slot = if (storageName.contains("2")) StorageSlot.SD2 else StorageSlot.SD1
                        onNewPhoto(dir, file, slot)
                        return
                    }

                    // 2. Pentax K-1 often emits storage/remain/captured deltas instead of dir+file when shutter fires
                    val lower = text.lowercase(Locale.US)
                    val looksLikeCapture = json.has("storages") ||
                        json.has("remain") ||
                        json.has("captured") ||
                        json.has("dirs") ||
                        lower.contains("storage") ||
                        lower.contains("capture") ||
                        lower.contains("photo")
                    if (looksLikeCapture) {
                        onCaptureOrStorageChanged()
                    } else {
                        onStateChanged()
                    }
                } catch (_: Exception) {
                    onCaptureOrStorageChanged()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeWebSocket === webSocket) {
                    activeWebSocket = null
                    onDisconnected()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ApiDebugLogger.record("WS", "/v1/changes", response?.code ?: 0, 0, t.localizedMessage ?: "ws error")
                if (activeWebSocket === webSocket) {
                    activeWebSocket = null
                    onDisconnected()
                }
            }
        })
    }

    fun stopChangesListener() {
        try { activeWebSocket?.close(1000, "Stopped") } catch (_: Exception) {}
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

}
