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
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PentaxK1ApiClient(
    private val wifiBinder: WifiNetworkBinder,
    private val baseUrl: String = "http://192.168.0.1"
) {
    private var activeWebSocket: WebSocket? = null

    private fun buildClient(readTimeoutSec: Long = 15L, connectTimeoutSec: Long = 5L): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(wifiBinder.getSocketFactory())
            .dns(wifiBinder.getDns())
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private inline fun <T> executeLogged(
        method: String,
        path: String,
        readTimeoutSec: Long = 10L,
        connectTimeoutSec: Long = 5L,
        request: Request,
        parser: (Response, String) -> T
    ): T {
        val t0 = System.currentTimeMillis()
        try {
            buildClient(readTimeoutSec, connectTimeoutSec).newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - t0
                ApiDebugLogger.record(method, path, resp.code, elapsed, text.ifBlank { "(empty)" })
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(80)}")
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
                        wbMode = json.optString("wbMode", defaultParams.wbMode),
                        exposureMode = json.optString("exposureMode", defaultParams.exposureMode),
                        avList = json.optJSONArray("avList").toStringList(defaultParams.avList),
                        tvList = json.optJSONArray("tvList").toStringList(defaultParams.tvList),
                        svList = json.optJSONArray("svList").toStringList(defaultParams.svList),
                        xvList = json.optJSONArray("xvList").toStringList(defaultParams.xvList),
                        wbList = json.optJSONArray("wbModeList").toStringList(defaultParams.wbList)
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
                    wbMode = json.optString("wbMode", current.wbMode),
                    exposureMode = json.optString("exposureMode", current.exposureMode)
                )
            }
        }
    }

    suspend fun updateCameraParam(key: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/v1/params/camera ($key=$value)"
            val body = FormBody.Builder().add(key, value).build()
            val req = Request.Builder().url("$baseUrl/v1/params/camera").put(body).build()
            executeLogged("PUT", path, 8, 5, req) { _, _ -> Unit }
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

    suspend fun listPhotos(slot: StorageSlot): Result<List<CameraPhoto>> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/v1/photos?storage=${slot.apiName}"
            val req = Request.Builder().url("$baseUrl$path").get().build()
            executeLogged("GET", path, 20, 5, req) { _, bodyStr ->
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
            val req = Request.Builder().url("$baseUrl$path").get().build()
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
                val buffer = ByteArray(16 * 1024)
                var readTotal = 0L
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    out.write(buffer, 0, count)
                    readTotal += count
                    onProgress?.invoke(readTotal, total)
                }
                if (size != "thumb") {
                    ApiDebugLogger.record("GET", path, resp.code, System.currentTimeMillis() - t0, "bytes=$readTotal")
                }
                out.toByteArray()
            }
        }
    }

    /**
     * Triggers Live View AF on Pentax K-1.
     * Returns Result<Boolean>: true = coordinate pos=X,Y accepted, false = fell back to camera's active AF point.
     */
    suspend fun triggerAutoFocus(xPercent: Int? = null, yPercent: Int? = null): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (xPercent != null && yPercent != null) {
                    val posVal = "${xPercent.coerceIn(5, 95)},${yPercent.coerceIn(5, 95)}"
                    val form = FormBody.Builder().add("pos", posVal).build()
                    val req = Request.Builder().url("$baseUrl/v1/lens/focus").post(form).build()
                    val firstTry = runCatching {
                        executeLogged("POST", "/v1/lens/focus?pos=$posVal", 12, 5, req) { _, _ -> true }
                    }
                    if (firstTry.isSuccess) return@runCatching true
                }
                // Fallback: trigger AF at camera's current focus point (works regardless of LV AF area mode)
                val fallbackReq = Request.Builder()
                    .url("$baseUrl/v1/lens/focus")
                    .post(FormBody.Builder().build())
                    .build()
                executeLogged("POST", "/v1/lens/focus (center-fallback)", 12, 5, fallbackReq) { _, _ -> false }
            }
        }

    suspend fun triggerShutter(afEnabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val form = FormBody.Builder().add("af", if (afEnabled) "on" else "off").build()
            val req = Request.Builder().url("$baseUrl/v1/camera/shoot").post(form).build()
            executeLogged("POST", "/v1/camera/shoot?af=$afEnabled", 20, 5, req) { _, _ -> Unit }
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
     * Hardware-compatible Pentax K-1 Live View MJPEG stream reader over a direct TCP socket
     * (bypasses OkHttp connection pooling/gzip quirks that break Ricoh/Pentax embedded HTTP streams).
     *
     * Handles:
     * 1. Raw HTTP/1.1 streaming (with or without Transfer-Encoding: chunked).
     * 2. Multipart/x-mixed-replace with per-frame Content-Length OR concatenated raw JPEGs.
     * 3. JPEG Marker-Aware framing: jumps cleanly over APP1/EXIF embedded thumbnails so an inner
     *    thumbnail EOI (FF D9) never truncates the outer Live View JPEG frame!
     */
    suspend fun streamLiveView(onFrame: (Bitmap) -> Unit, onError: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val uri = try { URI(baseUrl) } catch (_: Exception) { URI("http://192.168.0.1") }
            val host = uri.host ?: "192.168.0.1"
            val port = if (uri.port > 0) uri.port else 80
            val socket = wifiBinder.getSocketFactory().createSocket()
            var totalBytesRead = 0L
            var decodedFrames = 0

            try {
                socket.soTimeout = 10_000
                socket.connect(InetSocketAddress(host, port), 5_000)

                val reqStr = buildString {
                    append("GET /v1/liveview HTTP/1.1\r\n")
                    append("Host: ").append(host).append("\r\n")
                    append("Accept: */*\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().apply {
                    write(reqStr.toByteArray(Charsets.US_ASCII))
                    flush()
                }

                val rawIn = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val statusLine = readAsciiLine(rawIn)
                if (statusLine.isNullOrBlank()) {
                    ApiDebugLogger.record("GET", "/v1/liveview", 0, 0, "empty HTTP status")
                    onError("相机未响应实时取景流（空响应）。请先在 K-1 机身关闭「构图微调」后重试。")
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
                    "GET", "/v1/liveview", statusCode, 0,
                    "ct='$contentType' te='$transferEnc' cl='${headers["content-length"].orEmpty()}'"
                )

                if (statusCode !in 200..299) {
                    val bodySnippet = ByteArray(256).let { buf ->
                        val n = try { rawIn.read(buf) } catch (_: Exception) { -1 }
                        if (n > 0) String(buf, 0, n, Charsets.UTF_8).trim() else ""
                    }
                    ApiDebugLogger.record("GET", "/v1/liveview", statusCode, 0, "errBody=$bodySnippet")
                    val hint = when (statusCode) {
                        412, 503 -> "K-1 当前拒绝实时取景 (HTTP $statusCode)：请在机身菜单关闭「构图微调 (Composition Adjustment)」并退出回放模式。"
                        else -> "实时取景启动失败 (HTTP $statusCode) $bodySnippet"
                    }
                    onError(hint)
                    return@withContext
                }

                val bodyStream: InputStream = if (isChunked) ChunkedHttpInputStream(rawIn) else rawIn
                val bufferedBody = BufferedInputStream(bodyStream, 64 * 1024)

                // Read frames continuously using JPEG Marker-Aware parser (immune to APP1 EXIF inner FF D9!)
                while (currentCoroutineContext().isActive) {
                    val jpegBytes = readNextCompleteJpeg(bufferedBody) { n -> totalBytesRead += n }
                        ?: break
                    val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bmp != null) {
                        decodedFrames++
                        if (decodedFrames == 1) {
                            ApiDebugLogger.record(
                                "GET", "/v1/liveview", statusCode, 0,
                                "first frame decoded ${bmp.width}x${bmp.height} (${jpegBytes.size} B)"
                            )
                        }
                        onFrame(bmp)
                    }
                }

                if (currentCoroutineContext().isActive && decodedFrames == 0) {
                    ApiDebugLogger.record(
                        "GET", "/v1/liveview", statusCode, 0,
                        "stream EOF with 0 frames (bytesRead=$totalBytesRead, ct=$contentType)"
                    )
                    onError("取景流已结束但未收到有效画面 (HTTP $statusCode, ${totalBytesRead}B)。请在机身关闭「构图微调」并开启 LV 后点击重试。")
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    val msg = e.localizedMessage ?: e.javaClass.simpleName
                    ApiDebugLogger.record("GET", "/v1/liveview", 0, 0, "ex=$msg frames=$decodedFrames bytes=$totalBytesRead")
                    onError("实时取景中断: $msg（如 K-1 开启了「构图微调」，请先在机身关闭）")
                }
            } finally {
                try { socket.close() } catch (_: Exception) {}
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
     * Reads one complete JPEG image from an MJPEG or raw concatenated JPEG stream.
     * Skips over APPn / DQT / DHT / SOF / COM segment lengths cleanly so an embedded EXIF
     * thumbnail (which has its own FF D8 ... FF D9) can NEVER cause false early termination.
     */
    private fun readNextCompleteJpeg(input: InputStream, onBytes: (Int) -> Unit): ByteArray? {
        // 1. Sync to JPEG SOI (FF D8)
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

        // 2. Parse JPEG markers up to Start Of Scan (SOS = FF DA)
        while (true) {
            var b = input.read()
            if (b == -1) return null
            onBytes(1)
            out.write(b)

            // Skip any non-FF padding before a marker
            if (b != 0xFF) continue
            // Consume fill 0xFF bytes
            var marker = input.read()
            while (marker == 0xFF) {
                onBytes(1)
                out.write(marker)
                marker = input.read()
            }
            if (marker == -1) return null
            onBytes(1)
            out.write(marker)

            // Standalone markers with no payload length
            if (marker == 0x00 || marker == 0x01 || (marker in 0xD0..0xD8)) {
                continue
            }
            if (marker == 0xD9) {
                // Unexpected EOI before SOS -> return whatever we have
                return out.toByteArray()
            }

            // Read 2-byte big-endian segment length (includes the 2 length bytes themselves)
            val lenHi = input.read()
            val lenLo = input.read()
            if (lenHi == -1 || lenLo == -1) return null
            onBytes(2)
            out.write(lenHi)
            out.write(lenLo)

            val segLen = ((lenHi shl 8) or lenLo) - 2
            if (segLen < 0 || segLen > 2_000_000) return null
            if (segLen > 0) {
                val payload = ByteArray(segLen)
                if (!readFully(input, payload)) return null
                onBytes(segLen)
                out.write(payload)
            }

            if (marker == 0xDA) {
                // Entered entropy-coded scan data: scan until unescaped EOI (FF D9).
                // Inside scan data, literal 0xFF is byte-stuffed as FF 00, and restart markers are FF D0..D7.
                var scanPrev = -1
                while (true) {
                    val sb = input.read()
                    if (sb == -1) return null
                    onBytes(1)
                    out.write(sb)
                    if (scanPrev == 0xFF && sb == 0xD9) {
                        return out.toByteArray()
                    }
                    // Reset scanPrev on stuffed FF 00 so FF 00 doesn't carry over
                    scanPrev = if (scanPrev == 0xFF && sb == 0x00) -1 else sb
                    if (out.size() > 4_000_000) return null
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
            // Read chunk size line (skip empty CRLF from previous chunk)
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
        onStateChanged: () -> Unit
    ) {
        stopChangesListener()
        val wsUrl = baseUrl.replace("http://", "ws://") + "/v1/changes"
        val req = Request.Builder().url(wsUrl).build()
        activeWebSocket = buildClient(0).newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                ApiDebugLogger.record("WS", "/v1/changes", 200, 0, text)
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

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ApiDebugLogger.record("WS", "/v1/changes", response?.code ?: 0, 0, t.localizedMessage ?: "ws error")
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
