package com.pentax.k1sync.model

import java.util.Calendar

enum class StorageSlot(val apiName: String, val displayName: String) {
    SD1("sd1", "SD1"),
    SD2("sd2", "SD2")
}

enum class GallerySlotFilter(val displayName: String) {
    ALL("双卡"),
    SD1("SD1"),
    SD2("SD2")
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
    val isStarred: Boolean = false,
    val isRejected: Boolean = false,
    val sortKey: Long = 0L,
    val exif: PhotoExif? = null,
    val companionRaw: CameraPhoto? = null
) {
    val uniqueId: String get() = "${slot.apiName}/$dir/$file"
    val baseName: String get() = file.substringBeforeLast('.')
    val extension: String get() = file.substringAfterLast('.', "").uppercase()
    val hasRawCompanion: Boolean get() = companionRaw != null
    val hasAnyRaw: Boolean get() = isRaw || companionRaw != null
    val badgeText: String
        get() = when {
            companionRaw != null -> "RAW+JPG"
            isRaw -> extension
            else -> ""
        }

    companion object {
        private val EXIF_DT_REGEX = Regex("""(\d{4})[.:/-](\d{2})[.:/-](\d{2})[T\s]+(\d{2}):(\d{2}):(\d{2})""")
        private val FILE_NUM_REGEX = Regex("""(\d+)(?!.*\d)""")
        private val PENTAX_DATE_DIR_REGEX = Regex("""^(\d{3})_(\d{2})(\d{2})$""")

        /**
         * Produces a unified descending-friendly chronological sort key across BOTH SD1 & SD2:
         * 1. Exact EXIF YYYY.MM.DD HH:MM:SS when available.
         * 2. Pentax default folder date (NNN_MMDD) + year wraparound inference + sequential file counter.
         * 3. Fallback to DCF folder sequence + sequential file counter.
         */
        fun computeChronologicalSortKey(
            dirName: String,
            fileName: String,
            exifDateTime: String? = null
        ): Long {
            val fileNum = FILE_NUM_REGEX.find(fileName.substringBeforeLast('.'))
                ?.groupValues?.getOrNull(1)?.toLongOrNull()?.coerceIn(0L, 99999L) ?: 0L

            if (!exifDateTime.isNullOrBlank()) {
                val m = EXIF_DT_REGEX.find(exifDateTime.trim())
                if (m != null) {
                    val yyyy = m.groupValues[1].toLong()
                    val mm = m.groupValues[2].toLong()
                    val dd = m.groupValues[3].toLong()
                    val hh = m.groupValues[4].toLong()
                    val min = m.groupValues[5].toLong()
                    val ss = m.groupValues[6].toLong()
                    val ymdhms = ((((yyyy * 100 + mm) * 100 + dd) * 100 + hh) * 100 + min) * 100 + ss
                    return ymdhms * 10000L + (fileNum % 10000L)
                }
            }

            val dirMatch = PENTAX_DATE_DIR_REGEX.matchEntire(dirName.trim())
            if (dirMatch != null) {
                val mm = dirMatch.groupValues[2].toIntOrNull() ?: 1
                val dd = dirMatch.groupValues[3].toIntOrNull() ?: 1
                if (mm in 1..12 && dd in 1..31) {
                    val cal = Calendar.getInstance()
                    val curYear = cal.get(Calendar.YEAR)
                    val curMd = (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
                    val dirMd = mm * 100 + dd
                    // Handle Dec -> Jan year boundary gracefully across SD1 & SD2
                    val inferredYear = if (dirMd > curMd + 100) curYear - 1 else curYear
                    val ymdBase = (((inferredYear.toLong() * 100L + mm) * 100L + dd) * 1_000_000L)
                    // Pack fileNum inside the day's seconds slot (0..999999) so higher shot numbers sort newer on the same day
                    return (ymdBase + fileNum) * 10000L
                }
            }

            val dirNum = Regex("""^(\d+)""").find(dirName)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            return dirNum * 1_000_000L + fileNum
        }

        fun formatUnifiedDateGroup(dirName: String, exifDateTime: String? = null): String {
            if (!exifDateTime.isNullOrBlank()) {
                val m = EXIF_DT_REGEX.find(exifDateTime.trim())
                if (m != null) {
                    return "${m.groupValues[1]}年${m.groupValues[2]}月${m.groupValues[3]}日"
                }
            }
            val dirMatch = PENTAX_DATE_DIR_REGEX.matchEntire(dirName.trim())
            if (dirMatch != null) {
                return "${dirMatch.groupValues[2]}月${dirMatch.groupValues[3]}日"
            }
            return dirName.ifBlank { "相机相册" }
        }
    }
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
            val clean = rawTv.trim()
            if (clean.contains(".")) {
                val parts = clean.split(".")
                val num = parts.getOrNull(0)?.toIntOrNull()
                val den = parts.getOrNull(1)?.toIntOrNull()
                if (num != null && den != null && den > 0) {
                    return when {
                        num == 1 && den > 1 -> "1/${den}s"
                        den == 1 -> "${num}s"
                        else -> {
                            val sec = num.toDouble() / den.toDouble()
                            if (sec >= 1.0 && sec % 1.0 == 0.0) "${sec.toInt()}s" else "${"%.1f".format(sec)}s"
                        }
                    }
                }
            }
            return "${clean}s"
        }

        fun formatWbDisplay(wb: String): String = when (wb.lowercase()) {
            "auto" -> "AWB"
            "multiauto" -> "多区AWB"
            "daylight" -> "日光"
            "shade" -> "阴影"
            "cloud" -> "阴天"
            "fluorescentd", "daylightfluorescent" -> "日光荧光"
            "daywhitefluorescent" -> "昼白荧光"
            "whitefluorescent" -> "白色荧光"
            "warmwhitefluorescent" -> "暖白荧光"
            "tungsten" -> "钨丝灯"
            "flash" -> "闪光灯"
            "cte" -> "CTE"
            "manual", "manual1" -> "手动1"
            "manual2" -> "手动2"
            "manual3" -> "手动3"
            "colortemp", "colortemp1" -> "色温1"
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
    val speedBytesPerSec: Long = 0L,
    val retryAttempt: Int = 0,
    val status: TransferStatus = TransferStatus.QUEUED,
    val savedPath: String? = null,
    val errorMessage: String? = null
) {
    fun formatSpeedAndEta(): String {
        if (speedBytesPerSec <= 0L) return ""
        val mbps = speedBytesPerSec / (1024.0 * 1024.0)
        val speedStr = if (mbps >= 0.1) "${"%.1f".format(mbps)} MB/s" else "${speedBytesPerSec / 1024} KB/s"
        val remain = (totalBytes - bytesRead).coerceAtLeast(0L)
        return if (totalBytes > 0L && remain > 0L) {
            val etaSec = (remain / speedBytesPerSec).coerceAtLeast(1L)
            "$speedStr · 约 ${etaSec}s"
        } else speedStr
    }
}

enum class GridOverlayMode(val label: String, val maskRatio: Float? = null) {
    NONE("关闭"),
    THIRDS("三分"),
    CROSSHAIR("十字"),
    GOLDEN("黄金"),
    MASK_3_4("3:4 竖版", 3f / 4f),
    MASK_4_5("4:5 IG", 4f / 5f),
    MASK_235("2.35 电影", 2.35f)
}

enum class RemoteShotMode(val label: String) {
    SINGLE("单拍"),
    AEB("包围曝光"),
    INTERVAL("间隔延时"),
    BULB("B门长曝")
}

enum class FocusPeakingColor(val label: String, val argb: Int) {
    RED("红", 0xFFFF2A3C.toInt()),
    GREEN("绿", 0xFF22E568.toInt()),
    YELLOW("黄", 0xFFFFD60A.toInt())
}

enum class PeakingSensitivity(val label: String, val gradientThreshold: Int) {
    LOW("低", 95),
    MEDIUM("中", 62),
    HIGH("高", 38)
}

data class LiveViewAssistConfig(
    val peakingEnabled: Boolean = false,
    val peakingColor: FocusPeakingColor = FocusPeakingColor.RED,
    val peakingSensitivity: PeakingSensitivity = PeakingSensitivity.MEDIUM,
    val zebraEnabled: Boolean = false,
    val zebraThresholdPercent: Int = 92
)

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
