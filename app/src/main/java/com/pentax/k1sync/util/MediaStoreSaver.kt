package com.pentax.k1sync.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.pentax.k1sync.model.PhotoExif
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

object MediaStoreSaver {

    /**
     * Zero-heap streaming writer: opens a MediaStore / File OutputStream and passes it directly to `writer`
     * so 45MB RAW / 15MB JPG downloads stream straight from TCP socket to flash storage using only a 64KB buffer!
     */
    suspend fun saveStreamDirectly(
        context: Context,
        fileName: String,
        isRaw: Boolean = false,
        subFolder: String = "PentaxK1",
        writer: suspend (OutputStream) -> Unit
    ): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val (collectionUri, values, displayPath) = if (isRaw) {
                Triple(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PentaxK1_RAW")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    },
                    "Download/PentaxK1_RAW/$fileName"
                )
            } else {
                Triple(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$subFolder")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    },
                    "Pictures/$subFolder/$fileName"
                )
            }
            val uri = resolver.insert(collectionUri, values)
                ?: error(if (isRaw) "无法创建 RAW 文件" else "无法写入系统相册")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    writer(out)
                    out.flush()
                } ?: error("无法打开系统存储输出流")
                values.clear()
                values.put(if (isRaw) MediaStore.Downloads.IS_PENDING else MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw t
            }
            displayPath
        } else {
            @Suppress("DEPRECATION")
            val baseDir = if (isRaw) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
            val dir = File(baseDir, if (isRaw) "PentaxK1_RAW" else subFolder).apply { mkdirs() }
            val outFile = File(dir, fileName)
            try {
                FileOutputStream(outFile).use { fos ->
                    writer(fos)
                    fos.flush()
                }
            } catch (t: Throwable) {
                try { outFile.delete() } catch (_: Exception) {}
                throw t
            }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            outFile.absolutePath
        }
    }

    fun saveImageBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        isRaw: Boolean = false,
        subFolder: String = "PentaxK1"
    ): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            if (isRaw) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PentaxK1_RAW")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建 RAW 文件")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/PentaxK1_RAW/$fileName"
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$subFolder")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法写入系统相册")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Pictures/$subFolder/$fileName"
            }
        } else {
            @Suppress("DEPRECATION")
            val baseDir = if (isRaw) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
            val dir = File(baseDir, if (isRaw) "PentaxK1_RAW" else subFolder).apply { mkdirs() }
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { it.write(bytes) }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            outFile.absolutePath
        }
    }

    /**
     * Compresses Bitmap to JPEG and writes original EXIF (date/time, camera, lens, focal, aperture, shutter, ISO)
     * into a temporary file before committing to MediaStore, guaranteeing accurate gallery timeline & metadata.
     */
    fun saveBitmapAsJpeg(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        quality: Int = 97,
        subFolder: String = "PentaxK1_Watermark",
        exifToEmbed: PhotoExif? = null,
        artist: String = "",
        copyright: String = ""
    ): Result<String> = runCatching {
        val tempFile = File(context.cacheDir, "export_tmp_${System.nanoTime()}.jpg")
        try {
            FileOutputStream(tempFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(75, 100), fos)
            }
            if (exifToEmbed != null || artist.isNotBlank() || copyright.isNotBlank()) {
                writeExifToFile(tempFile, exifToEmbed ?: PhotoExif(), artist, copyright)
            }
            saveFileToGallery(context, tempFile, fileName, subFolder = subFolder).getOrThrow()
        } finally {
            tempFile.delete()
        }
    }

    private fun saveFileToGallery(
        context: Context,
        srcFile: File,
        fileName: String,
        subFolder: String = "PentaxK1_Watermark"
    ): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$subFolder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法写入系统相册")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    srcFile.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                } ?: error("无法打开系统相册输出流")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw t
            }
            "Pictures/$subFolder/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val dir = File(baseDir, subFolder).apply { mkdirs() }
            val outFile = File(dir, fileName)
            srcFile.inputStream().use { input ->
                FileOutputStream(outFile).use { out ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            outFile.absolutePath
        }
    }

    private fun writeExifToFile(file: File, exif: PhotoExif, artist: String, copyright: String) {
        try {
            val ei = ExifInterface(file.absolutePath)
            val model = exif.cameraModel.trim().ifBlank { "PENTAX K-1" }
            if (model.contains("PENTAX", ignoreCase = true) || model.contains("RICOH", ignoreCase = true)) {
                ei.setAttribute(ExifInterface.TAG_MAKE, "RICOH IMAGING COMPANY, LTD.")
            }
            ei.setAttribute(ExifInterface.TAG_MODEL, model)
            ei.setAttribute(ExifInterface.TAG_SOFTWARE, "Pentax K-1 Sync v7.5.1")
            if (artist.isNotBlank()) {
                ei.setAttribute(ExifInterface.TAG_ARTIST, artist.trim())
            }
            if (copyright.isNotBlank()) {
                ei.setAttribute(ExifInterface.TAG_COPYRIGHT, copyright.trim())
            }

            if (exif.lensModel.isNotBlank()) {
                ei.setAttribute(ExifInterface.TAG_LENS_MODEL, exif.lensModel.trim())
            }
            formatExifDateTime(exif.dateTime)?.let { dt ->
                ei.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
                ei.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dt)
                ei.setAttribute(ExifInterface.TAG_DATETIME, dt)
            }
            parseFocalRational(exif.focalLength)?.let { f ->
                ei.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, f)
            }
            parseApertureNumber(exif.aperture)?.let { a ->
                ei.setAttribute(ExifInterface.TAG_F_NUMBER, a)
            }
            parseExposureSeconds(exif.shutterSpeed)?.let { s ->
                ei.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, s)
            }
            val isoDigits = exif.iso.filter { it.isDigit() }
            if (isoDigits.isNotBlank()) {
                ei.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, isoDigits)
            }
            ei.saveAttributes()
        } catch (_: Exception) {
        }
    }

    private fun formatExifDateTime(raw: String): String? {
        val clean = raw.trim().replace(".", ":").replace("-", ":").replace("T", " ")
        // Match YYYY:MM:DD HH:MM:SS
        val regex = Regex("""^(\d{4}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2})""")
        val m = regex.find(clean)
        return if (m != null) "${m.groupValues[1]} ${m.groupValues[2]}" else null
    }

    private fun parseFocalRational(raw: String): String? {
        val num = raw.lowercase().replace("mm", "").trim().toDoubleOrNull() ?: return null
        if (num <= 0.0) return null
        return "${(num * 10.0).roundToInt()}/10"
    }

    private fun parseApertureNumber(raw: String): String? {
        val clean = raw.trim().removePrefix("f/").removePrefix("F/").removePrefix("f").removePrefix("F")
        val num = clean.toDoubleOrNull() ?: return null
        return if (num > 0.0) num.toString() else null
    }

    private fun parseExposureSeconds(raw: String): String? {
        val clean = raw.trim().lowercase().removeSuffix("s")
        return try {
            if (clean.contains("/")) {
                val p = clean.split("/")
                val num = p[0].toDouble()
                val den = p[1].toDouble()
                if (den > 0.0) (num / den).toString() else null
            } else {
                val v = clean.toDouble()
                if (v > 0.0) v.toString() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
