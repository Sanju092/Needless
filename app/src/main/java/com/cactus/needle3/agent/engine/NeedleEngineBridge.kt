package com.cactus.needle3.agent.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object NeedleNativeBridge {
    init {
        try {
            System.loadLibrary("needle3_jni")
        } catch (_: Throwable) {}
    }
    external fun nativeLoadCactFile(filePath: String): Int
    external fun nativeInit(systemPrompt: String, toolsJson: String): Int
    external fun nativeComplete(inputPrompt: String, maxNewTokens: Int): String
    external fun nativeEmbed(text: String): FloatArray
    external fun nativeReset()
}

data class ToolCallSpec(
    val name: String,
    val arguments: JSONObject
)

data class NeedleTurnResult(
    val functionCalls: List<ToolCallSpec>,
    val suppressedCalls: List<ToolCallSpec>,
    val reasoning: String,
    val confidence: Double,
    val prefillTps: Double,
    val decodeTps: Double,
    val peakRamMb: Double,
    val transcript: String? = null
)

/**
 * High-level runtime bridge for Cactus Compute Needle 3 on Android.
 * Supports both in-process JNI (libneedle.a) and local subprocess runner (`needle --serve`).
 */
class NeedleEngineBridge(
    private val context: Context,
    private val downloadManager: ModelDownloadManager
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var serverProcess: Process? = null
    var activeLayers: Int = 20
    var confidenceThreshold: Double = 0.70

    suspend fun initializeEngine(layers: Int = 20): Result<Int> = withContext(Dispatchers.IO) {
        try {
            activeLayers = layers.coerceIn(2, 20)
            val toolsJson = context.assets.open("tools.json").bufferedReader().use { it.readText() }
            val toolsFile = File(downloadManager.modelsDir, "tools.json").apply { writeText(toolsJson) }

            // Try JNI load first
            val rc = NeedleNativeBridge.nativeLoadCactFile(downloadManager.needleModelFile.absolutePath)
            if (rc > 0 && downloadManager.isWhistleReady()) {
                NeedleNativeBridge.nativeLoadCactFile(downloadManager.whistleModelFile.absolutePath)
            }
            val systemPrompt = "Device: Android Smartphone. Environment: Standalone Needle 3 Telegram Agent."
            val prefixTokens = NeedleNativeBridge.nativeInit(systemPrompt, toolsJson)

            // Also ensure local runner server is started if binary exists
            if (downloadManager.engineBinaryFile.exists() && serverProcess == null) {
                val cmd = mutableListOf(
                    downloadManager.engineBinaryFile.absolutePath,
                    "--model", downloadManager.needleModelFile.absolutePath,
                    "--tools", toolsFile.absolutePath,
                    "--serve", "--port", "8082"
                )
                if (downloadManager.isWhistleReady()) {
                    cmd.addAll(listOf("--model", downloadManager.whistleModelFile.absolutePath))
                }
                val pb = ProcessBuilder(cmd)
                pb.environment()["NEEDLE_TELEMETRY"] = "0"
                pb.environment()["DO_NOT_TRACK"] = "1"
                serverProcess = pb.start()
            }
            Result.success(prefixTokens)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeInstruction(inputOrAudioPath: String, maxNewTokens: Int = 256): NeedleTurnResult =
        withContext(Dispatchers.IO) {
            // 1. Try native JNI completion first
            val rawJni = try {
                NeedleNativeBridge.nativeComplete(inputOrAudioPath, maxNewTokens)
            } catch (_: Throwable) {
                "{\"shim\":true}"
            }

            val jsonStr = if (!rawJni.contains("\"shim\":true")) {
                rawJni
            } else {
                // 2. Fallback to local `needle --serve` HTTP daemon on 127.0.0.1:8082
                val payload = JSONObject().put("input", inputOrAudioPath).toString()
                val req = Request.Builder()
                    .url("http://127.0.0.1:8082/complete")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    resp.body?.string() ?: "{}"
                }
            }

            parseNeedleResponse(jsonStr)
        }

    private fun parseNeedleResponse(jsonStr: String): NeedleTurnResult {
        val obj = JSONObject(jsonStr)
        val calls = parseCallList(obj.optJSONArray("function_calls"))
        val suppressed = parseCallList(obj.optJSONArray("suppressed_calls"))
        return NeedleTurnResult(
            functionCalls = calls,
            suppressedCalls = suppressed,
            reasoning = obj.optString("reasoning", ""),
            confidence = obj.optDouble("confidence", 0.0),
            prefillTps = obj.optDouble("prefill_tps", 0.0),
            decodeTps = obj.optDouble("decode_tps", 0.0),
            peakRamMb = obj.optDouble("peak_ram_mb", 28.5),
            transcript = obj.optString("audio_text", "").takeIf { it.isNotEmpty() }
        )
    }

    private fun parseCallList(arr: JSONArray?): List<ToolCallSpec> {
        if (arr == null) return emptyList()
        val list = mutableListOf<ToolCallSpec>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            list.add(
                ToolCallSpec(
                    name = item.getString("name"),
                    arguments = item.optJSONObject("arguments") ?: JSONObject()
                )
            )
        }
        return list
    }

    /**
     * Logs confirmed user queries and tool calls into train.jsonl so the user can run
     * `needle finetune train.jsonl --epochs 10 --out adapter.safetensors`
     */
    fun logForFineTuning(query: String, calls: List<ToolCallSpec>) {
        val logFile = File(downloadManager.modelsDir, "train.jsonl")
        val answers = JSONArray()
        calls.forEach { call ->
            answers.put(JSONObject().put("name", call.name).put("arguments", call.arguments))
        }
        val row = JSONObject()
            .put("query", query)
            .put("answers", answers)
            .toString()
        logFile.appendText("$row\n")
    }
}
