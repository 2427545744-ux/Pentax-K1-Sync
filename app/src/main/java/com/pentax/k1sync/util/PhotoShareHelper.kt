package com.pentax.k1sync.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PhotoShareHelper {

    suspend fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
                // Keep share cache clean (retain last 6 files)
                shareDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }

                val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val outFile = File(shareDir, if (cleanName.endsWith(".jpg", true)) cleanName else "$cleanName.jpg")
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "分享照片").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
            }
        }
}
