package com.pentax.k1sync.network

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.pentax.k1sync.model.CameraInfo
import com.pentax.k1sync.model.CameraPhoto
import com.pentax.k1sync.model.PhotoExif
import com.pentax.k1sync.model.ShootingParams
import com.pentax.k1sync.model.StorageSlot
import com.pentax.k1sync.model.StorageStatus
import java.io.ByteArrayOutputStream
import kotlin.math.sin

class MockPentaxK1Repository {

    private var currentParams = ShootingParams(
        av = "1.8",
        tv = "1.500",
        sv = "100",
        xv = "0.0",
        wbMode = "daylight",
        exposureMode = "AV"
    )

    private val sd1Photos = mutableListOf(
        CameraPhoto(
            dir = "105_1122",
            file = "IMGP4821.JPG",
            slot = StorageSlot.SD1,
            dateGroup = "11月22日 · 105_1122",
            isRaw = false,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "HD PENTAX-FA 77mmF1.8 Limited",
                focalLength = "77mm",
                aperture = "1.8",
                shutterSpeed = "1/500s",
                iso = "100",
                exposureComp = "0.0 EV",
                dateTime = "2026.11.22 17:42:18",
                width = 7360,
                height = 4912
            )
        ),
        CameraPhoto(
            dir = "105_1122",
            file = "IMGP4821.PEF",
            slot = StorageSlot.SD1,
            dateGroup = "11月22日 · 105_1122",
            isRaw = true,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "HD PENTAX-FA 77mmF1.8 Limited",
                focalLength = "77mm",
                aperture = "1.8",
                shutterSpeed = "1/500s",
                iso = "100",
                exposureComp = "0.0 EV",
                dateTime = "2026.11.22 17:42:18",
                width = 7360,
                height = 4912
            )
        ),
        CameraPhoto(
            dir = "105_1122",
            file = "IMGP4822.JPG",
            slot = StorageSlot.SD1,
            dateGroup = "11月22日 · 105_1122",
            isRaw = false,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "smc PENTAX-FA 31mmF1.8AL Limited",
                focalLength = "31mm",
                aperture = "4.0",
                shutterSpeed = "1/1250s",
                iso = "100",
                exposureComp = "-0.3 EV",
                dateTime = "2026.11.22 16:15:04",
                width = 7360,
                height = 4912
            )
        ),
        CameraPhoto(
            dir = "105_1122",
            file = "IMGP4823.JPG",
            slot = StorageSlot.SD1,
            dateGroup = "11月22日 · 105_1122",
            isRaw = false,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "HD PENTAX-D FA* 50mmF1.4 SDM AW",
                focalLength = "50mm",
                aperture = "1.4",
                shutterSpeed = "1/800s",
                iso = "200",
                exposureComp = "+0.3 EV",
                dateTime = "2026.11.22 15:08:39",
                width = 7360,
                height = 4912
            )
        ),
        CameraPhoto(
            dir = "104_1119",
            file = "IMGP4790.JPG",
            slot = StorageSlot.SD1,
            dateGroup = "11月19日 · 104_1119",
            isRaw = false,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "HD PENTAX-D FA 15-30mmF2.8ED SDM WR",
                focalLength = "15mm",
                aperture = "2.8",
                shutterSpeed = "25s",
                iso = "3200",
                exposureComp = "0.0 EV",
                dateTime = "2026.11.19 22:36:11",
                width = 7360,
                height = 4912
            )
        ),
        CameraPhoto(
            dir = "104_1119",
            file = "IMGP4790.DNG",
            slot = StorageSlot.SD1,
            dateGroup = "11月19日 · 104_1119",
            isRaw = true,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "HD PENTAX-D FA 15-30mmF2.8ED SDM WR",
                focalLength = "15mm",
                aperture = "2.8",
                shutterSpeed = "25s",
                iso = "3200",
                exposureComp = "0.0 EV",
                dateTime = "2026.11.19 22:36:11",
                width = 7360,
                height = 4912
            )
        )
    )

    private val sd2Photos = mutableListOf(
        CameraPhoto(
            dir = "201_1115",
            file = "IMGP4610.PEF",
            slot = StorageSlot.SD2,
            dateGroup = "11月15日 · 201_1115",
            isRaw = true,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "smc PENTAX-FA 43mmF1.9 Limited",
                focalLength = "43mm",
                aperture = "2.8",
                shutterSpeed = "1/320s",
                iso = "400",
                exposureComp = "0.0 EV",
                dateTime = "2026.11.15 09:20:45",
                width = 7360,
                height = 4912
            )
        ),
        CameraPhoto(
            dir = "201_1115",
            file = "IMGP4611.JPG",
            slot = StorageSlot.SD2,
            dateGroup = "11月15日 · 201_1115",
            isRaw = false,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "smc PENTAX-FA 43mmF1.9 Limited",
                focalLength = "43mm",
                aperture = "5.6",
                shutterSpeed = "1/640s",
                iso = "100",
                exposureComp = "-0.7 EV",
                dateTime = "2026.11.15 10:12:03",
                width = 7360,
                height = 4912
            )
        )
    )

    private var shotCounter = 4824

    fun getCameraInfo(): CameraInfo = CameraInfo(
        connected = true,
        isMock = true,
        model = "PENTAX K-1",
        firmwareVersion = "2.40 (Mock)",
        batteryPercent = 86,
        storages = listOf(
            StorageStatus(StorageSlot.SD1, available = true, remainShots = 642, format = "RAW+"),
            StorageStatus(StorageSlot.SD2, available = true, remainShots = 1280, format = "RAW")
        ),
        ssid = "PENTAX_K1_DEMO"
    )

    fun getParams(): ShootingParams = currentParams

    fun setParam(key: String, value: String): ShootingParams {
        currentParams = when (key) {
            "av" -> currentParams.copy(av = value)
            "tv" -> currentParams.copy(tv = value)
            "sv" -> currentParams.copy(sv = value)
            "xv" -> currentParams.copy(xv = value)
            "wbMode" -> currentParams.copy(wbMode = value)
            else -> currentParams
        }
        return currentParams
    }

    fun listPhotos(slot: StorageSlot): List<CameraPhoto> {
        return if (slot == StorageSlot.SD1) sd1Photos.toList() else sd2Photos.toList()
    }

    fun shootNewPhoto(): CameraPhoto {
        val fileName = "IMGP${shotCounter++}.JPG"
        val newPhoto = CameraPhoto(
            dir = "105_1122",
            file = fileName,
            slot = StorageSlot.SD1,
            dateGroup = "11月22日 · 105_1122",
            isRaw = false,
            exif = PhotoExif(
                cameraModel = "PENTAX K-1",
                lensModel = "HD PENTAX-FA 77mmF1.8 Limited",
                focalLength = "77mm",
                aperture = currentParams.av,
                shutterSpeed = ShootingParams.formatTvDisplay(currentParams.tv),
                iso = currentParams.sv,
                exposureComp = "${currentParams.xv} EV",
                dateTime = "2026.11.22 19:25:00",
                width = 7360,
                height = 4912
            )
        )
        sd1Photos.add(0, newPhoto)
        return newPhoto
    }

    fun renderMockPhotoBytes(photo: CameraPhoto, width: Int, height: Int): ByteArray {
        val bmp = renderMockBitmap(photo.file.hashCode(), width, height, 0f)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    fun renderLiveViewFrame(tick: Int): Bitmap {
        return renderMockBitmap(seed = 2026, width = 960, height = 640, phase = tick * 0.08f)
    }

    private fun renderMockBitmap(seed: Int, width: Int, height: Int, phase: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val variant = kotlin.math.abs(seed) % 4
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (variant) {
            0 -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height * 0.58f,
                    intArrayOf(Color.rgb(34, 58, 86), Color.rgb(112, 146, 174), Color.rgb(218, 198, 176)),
                    floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height * 0.58f, paint)
                paint.shader = LinearGradient(
                    0f, height * 0.55f, 0f, height.toFloat(),
                    intArrayOf(Color.rgb(25, 58, 82), Color.rgb(12, 34, 54)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), paint)
                paint.shader = RadialGradient(
                    width * (0.68f + 0.02f * sin(phase)), height * 0.44f, width * 0.25f,
                    intArrayOf(Color.argb(230, 255, 224, 170), Color.argb(0, 255, 190, 120)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(width * 0.68f, height * 0.44f, width * 0.25f, paint)
                paint.shader = null
                paint.color = Color.rgb(24, 34, 46)
                val path = Path().apply {
                    moveTo(0f, height * 0.58f)
                    lineTo(width * 0.18f, height * 0.46f)
                    lineTo(width * 0.36f, height * 0.53f)
                    lineTo(width * 0.54f, height * 0.48f)
                    lineTo(width * 0.72f, height * 0.56f)
                    lineTo(width.toFloat(), height * 0.52f)
                    lineTo(width.toFloat(), height * 0.60f)
                    lineTo(0f, height * 0.60f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            1 -> {
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.rgb(8, 13, 28), Color.rgb(18, 32, 58), Color.rgb(12, 22, 34)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = RadialGradient(
                    width * 0.48f, height * 0.36f, width * 0.46f,
                    intArrayOf(Color.argb(115, 118, 178, 255), Color.argb(40, 62, 184, 146), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(width * 0.48f, height * 0.36f, width * 0.46f, paint)
                paint.shader = null
                for (i in 1..90) {
                    val sx = ((i * 7919 + seed * 31) and 0xFFFF) / 65535f * width
                    val sy = ((i * 3571 + seed * 17) and 0xFFFF) / 65535f * (height * 0.72f)
                    val r = (i % 3 + 1) * (width / 900f).coerceAtLeast(1f)
                    paint.color = Color.argb(160 + (i * 19) % 95, 235, 244, 255)
                    canvas.drawCircle(sx, sy, r, paint)
                }
                paint.color = Color.rgb(10, 14, 18)
                val ridge = Path().apply {
                    moveTo(0f, height * 0.76f)
                    lineTo(width * 0.25f, height * 0.65f)
                    lineTo(width * 0.48f, height * 0.72f)
                    lineTo(width * 0.78f, height * 0.62f)
                    lineTo(width.toFloat(), height * 0.74f)
                    lineTo(width.toFloat(), height.toFloat())
                    lineTo(0f, height.toFloat())
                    close()
                }
                canvas.drawPath(ridge, paint)
            }
            2 -> {
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.rgb(22, 62, 42), Color.rgb(38, 108, 66), Color.rgb(16, 42, 28)),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                for (i in 0..18) {
                    val bx = ((i * 4211 + 13) % 100) / 100f * width
                    val by = ((i * 2377 + 41) % 85) / 100f * height
                    val rad = width * (0.04f + (i % 5) * 0.02f)
                    paint.shader = RadialGradient(
                        bx, by, rad,
                        intArrayOf(Color.argb(95, 212, 255, 180), Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(bx, by, rad, paint)
                }
            }
            else -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(Color.rgb(42, 74, 126), Color.rgb(138, 174, 214), Color.rgb(214, 166, 126)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                paint.color = Color.rgb(66, 38, 30)
                val roof = Path().apply {
                    moveTo(0f, height * 0.35f)
                    lineTo(width.toFloat(), height * 0.72f)
                    lineTo(width.toFloat(), height.toFloat())
                    lineTo(0f, height.toFloat())
                    close()
                }
                canvas.drawPath(roof, paint)
                paint.color = Color.rgb(176, 118, 44)
                paint.strokeWidth = (height * 0.012f).coerceAtLeast(3f)
                canvas.drawLine(0f, height * 0.35f, width.toFloat(), height * 0.72f, paint)
            }
        }
        return bmp
    }
}
