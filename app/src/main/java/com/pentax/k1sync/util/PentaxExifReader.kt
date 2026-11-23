package com.pentax.k1sync.util

import androidx.exifinterface.media.ExifInterface
import com.pentax.k1sync.model.PhotoExif
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Multi-layer metadata extractor tailored for Pentax K-1 JPG & DNG/PEF files:
 * 1. Standard EXIF (ExifInterface)
 * 2. Embedded XMP (aux:Lens / exifEX:LensModel)
 * 3. Pentax MakerNote binary parser (AOC\0 -> Tag 0x003F LensType lookup table)
 *
 * Returns empty string for lensModel if unavailable (e.g., vintage manual lens without contacts),
 * so watermark templates cleanly hide the lens row without displaying stale/fake defaults.
 */
object PentaxExifReader {

    fun parse(rawBytes: ByteArray): PhotoExif {
        var model = ""
        var focal = ""
        var aperture = ""
        var shutter = ""
        var iso = ""
        var dt = ""
        var lens = ""
        var width = 0
        var height = 0

        try {
            val exif = ExifInterface(ByteArrayInputStream(rawBytes))
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            focal = parseFocal(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
            aperture = parseAperture(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
            shutter = parseShutter(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
            iso = (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif.getAttribute("ISOSpeedRatings")
                ?: "").trim()
            dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.trim()
                ?.replaceFirst(":", ".")
                ?.replaceFirst(":", ".")
                .orEmpty()
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim().orEmpty()
            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        } catch (_: Exception) {
        }

        if (lens.isBlank()) {
            lens = extractLensFromXmp(rawBytes).orEmpty()
        }

        if (lens.isBlank()) {
            lens = extractLensFromPentaxMakerNote(rawBytes).orEmpty()
        }

        return PhotoExif(
            cameraModel = model.ifBlank { "PENTAX K-1" },
            lensModel = lens.trim(),
            focalLength = focal,
            aperture = aperture,
            shutterSpeed = shutter,
            iso = iso,
            dateTime = dt,
            width = width,
            height = height
        )
    }

    private fun extractLensFromXmp(bytes: ByteArray): String? {
        return try {
            val scanLen = minOf(bytes.size, 196_608)
            val text = String(bytes, 0, scanLen, StandardCharsets.ISO_8859_1)
            val patterns = listOf(
                Regex("<aux:Lens>([^<]+)</aux:Lens>"),
                Regex("aux:Lens=\"([^\"]+)\""),
                Regex("<exifEX:LensModel>([^<]+)</exifEX:LensModel>"),
                Regex("exifEX:LensModel=\"([^\"]+)\"")
            )
            for (regex in patterns) {
                val found = regex.find(text)?.groupValues?.getOrNull(1)?.trim()
                if (!found.isNullOrBlank() && !found.equals("Unknown", ignoreCase = true)) {
                    return found
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLensFromPentaxMakerNote(bytes: ByteArray): String? {
        return try {
            val scanLimit = minOf(bytes.size - 24, 131_072)
            if (scanLimit <= 0) return null

            var aocPos = -1
            for (i in 0 until scanLimit) {
                if (bytes[i] == 'A'.code.toByte() &&
                    bytes[i + 1] == 'O'.code.toByte() &&
                    bytes[i + 2] == 'C'.code.toByte() &&
                    bytes[i + 3] == 0.toByte()
                ) {
                    aocPos = i
                    break
                }
            }
            if (aocPos < 0) return null

            val bo0 = bytes[aocPos + 4].toInt() and 0xFF
            val bo1 = bytes[aocPos + 5].toInt() and 0xFF
            val bigEndian = when {
                bo0 == 'M'.code && bo1 == 'M'.code -> true
                bo0 == 'I'.code && bo1 == 'I'.code -> false
                else -> true
            }

            fun readU16(offset: Int): Int {
                if (offset + 1 >= bytes.size) return 0
                val b0 = bytes[offset].toInt() and 0xFF
                val b1 = bytes[offset + 1].toInt() and 0xFF
                return if (bigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
            }

            val ifdStart = aocPos + 6
            val entryCount = readU16(ifdStart)
            if (entryCount <= 0 || entryCount > 250) return null

            var pos = ifdStart + 2
            for (e in 0 until entryCount) {
                if (pos + 12 > bytes.size) break
                val tag = readU16(pos)
                if (tag == 0x003F) {
                    val count = if (bigEndian) {
                        ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                            ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                            ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                            (bytes[pos + 7].toInt() and 0xFF)
                    } else {
                        (bytes[pos + 4].toInt() and 0xFF) or
                            ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                            ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                            ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                    }
                    if (count in 2..4) {
                        val b0 = bytes[pos + 8].toInt() and 0xFF
                        val b1 = bytes[pos + 9].toInt() and 0xFF
                        val lensId = (b0 shl 8) or b1
                        return PENTAX_LENS_TABLE[lensId]
                    }
                }
                pos += 12
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFocal(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val mm = if (raw.contains("/")) {
                val p = raw.split("/")
                val num = p[0].toDouble()
                val den = p[1].toDouble()
                if (den != 0.0) num / den else num
            } else {
                raw.toDouble()
            }
            if (mm <= 0.0) "" else if (mm % 1.0 == 0.0) "${mm.toInt()}mm" else "${"%.1f".format(mm)}mm"
        } catch (_: Exception) {
            raw
        }
    }

    private fun parseAperture(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val v = raw.toDouble()
            if (v <= 0.0) "" else if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(v)
        } catch (_: Exception) {
            raw.trim()
        }
    }

    private fun parseShutter(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val sec = raw.toDouble()
            when {
                sec <= 0.0 -> ""
                sec >= 1.0 -> if (sec % 1.0 == 0.0) "${sec.toInt()}s" else "${"%.1f".format(sec)}s"
                else -> "1/${kotlin.math.round(1.0 / sec).toInt()}s"
            }
        } catch (_: Exception) {
            raw.trim()
        }
    }

    private val PENTAX_LENS_TABLE: Map<Int, String> = mapOf(
        0x0319 to "smc PENTAX-FA 50mmF1.4",
        0x031A to "smc PENTAX-FA 50mmF1.7",
        0x031B to "smc PENTAX-FA 28mmF2.8 AL",
        0x031C to "smc PENTAX-FA 135mmF2.8 [IF]",
        0x0322 to "smc PENTAX-FA* 85mmF1.4 [IF]",
        0x0323 to "smc PENTAX-FA* 200mmF2.8 ED [IF]",
        0x0324 to "smc PENTAX-FA* 300mmF2.8 ED [IF]",
        0x0325 to "smc PENTAX-FA* 28-70mmF2.8 AL",
        0x0326 to "smc PENTAX-FA* 80-200mmF2.8 ED [IF]",
        0x0327 to "smc PENTAX-FA* 28-70mmF2.8 AL",
        0x032C to "smc PENTAX-FA* 24mmF2 AL [IF]",
        0x032E to "smc PENTAX-FA 50mmF2.8 Macro",
        0x032F to "smc PENTAX-FA 100mmF2.8 Macro",
        0x041A to "smc PENTAX-FA* 250-600mmF5.6 ED [IF]",
        0x0423 to "smc PENTAX-FA 43mmF1.9 Limited",
        0x042D to "smc PENTAX-FA 77mmF1.8 Limited",
        0x0502 to "smc PENTAX-FA 35mmF2 AL",
        0x050D to "smc PENTAX-FA 31mmF1.8AL Limited",
        0x050E to "smc PENTAX-FA 20-35mmF4 AL",
        0x060E to "smc PENTAX-D FA 100mmF2.8 Macro",
        0x060F to "smc PENTAX-D FA 50mmF2.8 Macro",
        0x07E4 to "smc PENTAX-DA* 16-50mmF2.8ED AL [IF] SDM",
        0x07E5 to "smc PENTAX-DA* 50-135mmF2.8ED [IF] SDM",
        0x07E6 to "smc PENTAX-DA* 200mmF2.8ED [IF] SDM",
        0x07E7 to "smc PENTAX-DA* 300mmF4ED [IF] SDM",
        0x07E9 to "smc PENTAX-DA 35mmF2.8 Macro Limited",
        0x07EA to "smc PENTAX-DA* 55mmF1.4 SDM",
        0x07EE to "smc PENTAX-DA 15mmF4ED AL Limited",
        0x0804 to "smc PENTAX-D FA Macro 100mmF2.8 WR",
        0x0808 to "smc PENTAX-DA 35mmF2.4 AL",
        0x080C to "smc PENTAX-DA 50mmF1.8",
        0x0817 to "HD PENTAX-DA 20-40mmF2.8-4ED Limited DC WR",
        0x0819 to "HD PENTAX-DA 15mmF4ED AL Limited",
        0x081A to "HD PENTAX-DA 35mmF2.8 Macro Limited",
        0x081B to "HD PENTAX-DA 70mmF2.4 Limited",
        0x081C to "HD PENTAX-DA 21mmF3.2AL Limited",
        0x081D to "HD PENTAX-DA 40mmF2.8 Limited",
        0x0822 to "HD PENTAX-D FA 150-450mmF4.5-5.6ED DC AW",
        0x0823 to "HD PENTAX-D FA* 70-200mmF2.8ED DC AW",
        0x0824 to "HD PENTAX-D FA 28-105mmF3.5-5.6ED DC WR",
        0x0825 to "HD PENTAX-D FA 24-70mmF2.8ED SDM WR",
        0x0826 to "HD PENTAX-D FA 15-30mmF2.8ED SDM WR",
        0x0827 to "HD PENTAX-D FA* 50mmF1.4 SDM AW",
        0x0828 to "HD PENTAX-D FA 70-210mmF4ED SDM WR",
        0x0829 to "HD PENTAX-D FA* 85mmF1.4ED SDM AW",
        0x082A to "HD PENTAX-FA 35mmF2",
        0x082B to "HD PENTAX-D FA 21mmF2.4ED Limited DC WR",
        0x082C to "HD PENTAX-FA 31mmF1.8 Limited",
        0x082D to "HD PENTAX-FA 43mmF1.9 Limited",
        0x082E to "HD PENTAX-FA 77mmF1.8 Limited",
        0x082F to "HD PENTAX-D FA Macro 100mmF2.8ED AW",
        0x0830 to "HD PENTAX-FA 50mmF1.4",
        0x0831 to "smc PENTAX-FA 50mmF1.4 Classic"
    )
}
