package com.cactus.needle3.agent.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads needle3.cact (8-29 MB), whistle.cact (16.9 MB), and the android-arm64 engine
 * directly from Cactus-Compute on Hugging Face onto the phone's internal storage.
 */
class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val NEEDLE3_CACT_URL = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact"
        const val WHISTLE_CACT_URL = "https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact"
        const val ANDROID_ENGINE_URL = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle"
    }

    val modelsDir: File
        get() = File(context.filesDir, "needle3_models").apply { mkdirs() }

    val needleModelFile: File
        get() = File(modelsDir, "needle3.cact")

    val whistleModelFile: File
        get() = File(modelsDir, "whistle.cact")

    val engineBinaryFile: File
        get() = File(modelsDir, "needle")

    fun isNeedleReady(): Boolean = needleModelFile.exists() && needleModelFile.length() > 1_000_000L
    fun isWhistleReady(): Boolean = whistleModelFile.exists() && whistleModelFile.length() > 1_000_000L

    suspend fun downloadAllAssets(
        includeWhistle: Boolean = true,
        onProgress: (stage: String, downloadedMb: Float, totalMb: Float, percent: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Download android-arm64 CLI runner (~1.5 MB)
            if (!engineBinaryFile.exists() || engineBinaryFile.length() < 100_000L) {
                downloadFile(ANDROID_ENGINE_URL, engineBinaryFile, "Engine (android-arm64)", onProgress)
                engineBinaryFile.setExecutable(true, true)
            }
            // 2. Download needle3.cact (29 MB CQ2-bit 20-layer archive)
            if (!isNeedleReady()) {
                downloadFile(NEEDLE3_CACT_URL, needleModelFile, "Needle 3 Model (needle3.cact)", onProgress)
            }
            // 3. Optionally download whistle.cact (16.9 MB Speech-to-Text archive)
            if (includeWhistle && !isWhistleReady()) {
                downloadFile(WHISTLE_CACT_URL, whistleModelFile, "Whistle ASR (whistle.cact)", onProgress)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFile(
        url: String,
        dest: File,
        label: String,
        onProgress: (String, Float, Float, Int) -> Unit
    ) {
        val tempFile = File(dest.parentFile, "${dest.name}.part")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} downloading $label")
            val body = resp.body ?: throw IllegalStateException("Empty body for $label")
            val totalBytes = body.contentLength().coerceAtLeast(1L)
            val totalMb = totalBytes / (1024f * 1024f)

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(label, downloaded / (1024f * 1024f), totalMb, pct)
                    }
                }
            }
        }
        tempFile.renameTo(dest)
    }
}
