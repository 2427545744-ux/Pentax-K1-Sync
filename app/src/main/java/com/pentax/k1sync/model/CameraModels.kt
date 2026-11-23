package com.pentax.k1sync.model

enum class StorageSlot(val apiName: String, val displayName: String) {
    SD1("sd1", "SD1"),
    SD2("sd2", "SD2")
}

enum class PhotoFormatFilter(val label: String) {
    ALL("全部"),
    JPG("JPG"),
    RAW("RAW")
}

enum class DownloadQuality(val apiSize: String, val label: String, val desc: String) {
    VIEW("view", "预览图", "~1600px · 极速发社交"),
    FULL_JPG("full", "原图 JPG", "完整 36MP 无损压缩"),
    RAW("full", "无损 RAW", "PEF / DNG 原始底片")
}

data class StorageStatus(
    val slot: StorageSlot,
    val available: Boolean = false,
    val remainShots: Int = 0,
    val format: String = ""
)

data class CameraInfo(
    val connected: Boolean = false,
    val isMock: Boolean = false,
    val model: String = "PENTAX K-1",
    val firmwareVersion: String = "",
    val batteryPercent: Int = 0,
    val storages: List<StorageStatus> = listOf(
        StorageStatus(StorageSlot.SD1, available = true, remainShots = 0),
        StorageStatus(StorageSlot.SD2, available = false, remainShots = 0)
    ),
    val ssid: String = ""
)

data class PhotoExif(
    val cameraModel: String = "PENTAX K-1",
    val lensModel: String = "",
    val focalLength: String = "",
    val aperture: String = "",
    val shutterSpeed: String = "",
    val iso: String = "",
    val exposureComp: String = "",
    val dateTime: String = "",
    val width: Int = 0,
    val height: Int = 0
) {
    fun formatParamsSingleLine(uppercaseF: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (focalLength.isNotBlank()) {
            parts += if (focalLength.endsWith("mm", ignoreCase = true)) focalLength else "${focalLength}mm"
        }
        if (aperture.isNotBlank()) {
            val clean = aperture.removePrefix("f/").removePrefix("F/").removePrefix("F").removePrefix("f")
            parts += if (uppercaseF) "F$clean" else "f/$clean"
        }
        if (shutterSpeed.isNotBlank()) {
            val clean = if (shutterSpeed.startsWith("1.") && !shutterSpeed.contains("/")) {
                "1/${shutterSpeed.removePrefix("1.")}"
            } else shutterSpeed
            parts += if (clean.endsWith("s", ignoreCase = true)) clean else "${clean}s"
        }
        if (iso.isNotBlank()) {
            val clean = iso.removePrefix("ISO").removePrefix("iso").trim()
            parts += "ISO$clean"
        }
        return parts.joinToString("  ")
    }
}

data class CameraPhoto(
    val dir: String,
    val file: String,
    val slot: StorageSlot,
    val dateGroup: String,
    val isRaw: Boolean,
    val isDownloaded: Boolean = false,
    val exif: PhotoExif? = null
) {
    val uniqueId: String get() = "${slot.apiName}/$dir/$file"
    val extension: String get() = file.substringAfterLast('.', "").uppercase()
}

data class ShootingParams(
    val av: String = "2.8",
    val tv: String = "1.250",
    val sv: String = "200",
    val xv: String = "0.0",
    val wbMode: String = "auto",
    val exposureMode: String = "AV",
    val avList: List<String> = listOf("1.4", "1.8", "2.0", "2.8", "4.0", "5.6", "8.0", "11", "16", "22"),
    val tvList: List<String> = listOf("30", "15", "8", "4", "2", "1", "1.2", "1.4", "1.8", "1.15", "1.30", "1.60", "1.125", "1.250", "1.500", "1.1000", "1.2000", "1.4000", "1.8000"),
    val svList: List<String> = listOf("100", "200", "400", "800", "1600", "3200", "6400", "12800", "25600"),
    val xvList: List<String> = listOf("-2.0", "-1.7", "-1.3", "-1.0", "-0.7", "-0.3", "0.0", "+0.3", "+0.7", "+1.0", "+1.3", "+1.7", "+2.0"),
    val wbList: List<String> = listOf("auto", "multiAuto", "daylight", "shade", "cloud", "fluorescentD", "tungsten", "cte")
) {
    companion object {
        fun formatTvDisplay(rawTv: String): String {
            return if (rawTv.startsWith("1.")) {
                "1/${rawTv.removePrefix("1.")}s"
            } else {
                "${rawTv}s"
            }
        }

        fun formatWbDisplay(wb: String): String = when (wb.lowercase()) {
            "auto" -> "AWB"
            "multiauto" -> "多区"
            "daylight" -> "日光"
            "shade" -> "阴影"
            "cloud" -> "阴天"
            "fluorescentd" -> "荧光"
            "tungsten" -> "钨丝"
            "cte" -> "CTE"
            else -> wb.uppercase()
        }
    }
}

enum class TransferStatus {
    QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

data class TransferTask(
    val id: String,
    val photo: CameraPhoto,
    val quality: DownloadQuality,
    val autoWatermark: Boolean = false,
    val progress: Float = 0f,
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val savedPath: String? = null,
    val errorMessage: String? = null
)

enum class GridOverlayMode(val label: String) {
    NONE("关闭"),
    THIRDS("三分"),
    CROSSHAIR("十字"),
    GOLDEN("黄金")
}

enum class RemoteShotMode(val label: String) {
    SINGLE("单拍"),
    INTERVAL("间隔延时"),
    BULB("B门长曝")
}

data class HistogramData(
    val luma: FloatArray,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramData) return false
        return luma.contentEquals(other.luma)
    }

    override fun hashCode(): Int = luma.contentHashCode()
}
