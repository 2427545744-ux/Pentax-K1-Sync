package com.pentax.k1sync.spektra

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object SpektraResourceManager {

    private const val ASSET_ROOT = "spektra_engine"
    private const val STAMP_VALUE = "spektra_v6_0_0"

    suspend fun ensureExtracted(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDir = File(context.filesDir, ASSET_ROOT)
            val stampFile = File(targetDir, ".ready_stamp")
            val binCheck = File(targetDir, "spektralab_constants.bin")

            if (stampFile.exists() && stampFile.readText().trim() == STAMP_VALUE && binCheck.exists()) {
                return@runCatching targetDir
            }

            onProgress("首次启动正在解压 81 波长光谱数据库与 65 个 Vulkan 着色器...")
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            val am = context.assets
            val manifestLines = try {
                am.open("$ASSET_ROOT/manifest.txt").bufferedReader().use { it.readLines() }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "manifest.txt" }
            } catch (_: Exception) {
                error("APK 中缺少 SpektraLab 光谱资源包，请使用 GitHub Actions 完整构建")
            }

            val total = manifestLines.size.coerceAtLeast(1)
            val buffer = ByteArray(64 * 1024)
            manifestLines.forEachIndexed { idx, relPath ->
                val outFile = File(targetDir, relPath)
                outFile.parentFile?.mkdirs()
                am.open("$ASSET_ROOT/$relPath").use { input ->
                    FileOutputStream(outFile).use { output ->
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (idx % 25 == 0 || idx == total - 1) {
                    onProgress("初始化光谱暗房资源 (${idx + 1}/$total)...")
                }
            }

            stampFile.writeText(STAMP_VALUE)
            targetDir
        }
    }
}
