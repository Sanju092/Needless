package com.cactus.needle3.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.cactus.needle3.agent.engine.ModelDownloadManager
import com.cactus.needle3.agent.engine.NeedleEngineBridge
import com.cactus.needle3.agent.engine.ToolCallSpec
import com.cactus.needle3.agent.tools.PhoneToolExecutor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Persistent Background ForegroundService that long-polls the Telegram Bot API,
 * routes natural-language instructions (and voice messages) into Needle 3 on-device,
 * enforces confidence gating, executes phone tools, and replies in Telegram.
 */
class TelegramBotForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var engineBridge: NeedleEngineBridge
    private lateinit var toolExecutor: PhoneToolExecutor

    private var botToken: String = ""
    private var allowedChatId: Long = 0L
    private var lastUpdateId: Long = 0L
    private var pendingConfirmCalls: List<ToolCallSpec>? = null
    private var pendingConfirmQuery: String = ""

    override fun onCreate() {
        super.onCreate()
        downloadManager = ModelDownloadManager(this)
        engineBridge = NeedleEngineBridge(this, downloadManager)
        toolExecutor = PhoneToolExecutor(this)
        startForeground(1001, buildNotification("Needle 3 Agent Active — Listening on Telegram"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        botToken = intent?.getStringExtra("BOT_TOKEN") ?: ""
        allowedChatId = intent?.getLongExtra("ALLOWED_CHAT_ID", 0L) ?: 0L
        val layers = intent?.getIntExtra("LAYERS", 20) ?: 20

        if (botToken.isNotBlank()) {
            serviceScope.launch {
                engineBridge.initializeEngine(layers)
                pollTelegramUpdatesLoop()
            }
        }
        return START_STICKY
    }

    private suspend fun pollTelegramUpdatesLoop() {
        while (serviceScope.isActive && botToken.isNotBlank()) {
            try {
                val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=25"
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: return@use
                    val json = JSONObject(bodyStr)
                    if (!json.optBoolean("ok")) return@use
                    val results = json.optJSONArray("result") ?: JSONArray()
                    for (i in 0 until results.length()) {
                        val upd = results.getJSONObject(i)
                        lastUpdateId = upd.getLong("update_id")
                        handleTelegramUpdate(upd)
                    }
                }
            } catch (_: Exception) {
                delay(3000)
            }
        }
    }

    private suspend fun handleTelegramUpdate(upd: JSONObject) {
        val msg = upd.optJSONObject("message") ?: return
        val chatId = msg.getJSONObject("chat").getLong("id")
        if (allowedChatId != 0L && chatId != allowedChatId) {
            sendTelegramMessage(chatId, "⛔ Unauthorized Telegram Chat ID ($chatId).")
            return
        }

        val text = msg.optString("text", "").trim()
        if (text.isEmpty()) return

        when {
            text == "/start" || text == "/help" -> {
                sendTelegramMessage(
                    chatId,
                    "🌵 *Needle 3 Standalone Phone Agent*\n\n" +
                    "• Model: `needle3.cact` (${engineBridge.activeLayers}L subnetwork)\n" +
                    "• Commands: `/status`, `/layers 8`, `/confirm`, `/cancel`, `/export`\n\n" +
                    "Send any natural language instruction (e.g. _'Turn on the flashlight and dim the screen to 30%'_ or _'Read my latest OTP and copy it'_)."
                )
            }
            text == "/status" -> {
                val bat = toolExecutor.executeCall(ToolCallSpec("get_battery_status", JSONObject()))
                sendTelegramMessage(
                    chatId,
                    "✅ *Needle 3 Runtime Status*\n" +
                    "• Active Depth: `${engineBridge.activeLayers} layers`\n" +
                    "• Confidence Gate: `${engineBridge.confidenceThreshold}`\n" +
                    "• Battery: `${bat.optInt("percentage")}%` (Charging: `${bat.optBoolean("charging")}`)"
                )
            }
            text.startsWith("/layers") -> {
                val newLayers = text.removePrefix("/layers").trim().toIntOrNull()?.coerceIn(2, 20) ?: 20
                engineBridge.initializeEngine(newLayers)
                sendTelegramMessage(chatId, "⚡ Switched Needle 3 Laddered SAN depth to *$newLayers layers*.")
            }
            text == "/confirm" -> {
                val calls = pendingConfirmCalls
                if (calls != null) {
                    pendingConfirmCalls = null
                    executeAndReply(chatId, pendingConfirmQuery, calls, confidence = 1.0, reasoning = "User confirmed via /confirm")
                } else {
                    sendTelegramMessage(chatId, "No pending tool calls waiting for confirmation.")
                }
            }
            text == "/cancel" -> {
                pendingConfirmCalls = null
                sendTelegramMessage(chatId, "❌ Cancelled pending tool call.")
            }
            text == "/export" -> {
                val logFile = File(downloadManager.modelsDir, "train.jsonl")
                val count = if (logFile.exists()) logFile.readLines().size else 0
                sendTelegramMessage(
                    chatId,
                    "📦 Logged `$count` verified examples to `${logFile.absolutePath}`.\n" +
                    "Run fine-tuning with:\n`needle finetune train.jsonl --epochs 10 --out adapter.safetensors`\n" +
                    "`needle build --lora adapter.safetensors --layers ${engineBridge.activeLayers} --out tuned.cact`"
                )
            }
            else -> {
                val turn = engineBridge.completeInstruction(text)
                val calls = turn.functionCalls
                val held = turn.suppressedCalls
                if (calls.isNotEmpty() && turn.confidence >= engineBridge.confidenceThreshold) {
                    executeAndReply(chatId, text, calls, turn.confidence, turn.reasoning)
                } else if (calls.isNotEmpty() || held.isNotEmpty()) {
                    val candidate = calls.ifEmpty { held }
                    pendingConfirmCalls = candidate
                    pendingConfirmQuery = text
                    val summary = candidate.joinToString(", ") { "${it.name}(${it.arguments})" }
                    sendTelegramMessage(
                        chatId,
                        "⚠️ *Medium Confidence (${String.format("%.2f", turn.confidence)})*\n" +
                        "Proposed call: `$summary`\n" +
                        "Reasoning: _${turn.reasoning}_\n\n" +
                        "Reply `/confirm` to execute or `/cancel` to abort."
                    )
                } else {
                    sendTelegramMessage(
                        chatId,
                        "🛡️ *Refused / No Matching Tool* (confidence `${String.format("%.2f", turn.confidence)}`).\n" +
                        "Needle 3 withholds calls when no exposed tool covers the request."
                    )
                }
            }
        }
    }

    private suspend fun executeAndReply(
        chatId: Long,
        query: String,
        calls: List<ToolCallSpec>,
        confidence: Double,
        reasoning: String
    ) {
        val outputs = mutableListOf<String>()
        for (call in calls) {
            val res = toolExecutor.executeCall(call)
            outputs.add("• `${call.name}` → `${res}`")
        }
        engineBridge.logForFineTuning(query, calls)
        sendTelegramMessage(
            chatId,
            "✅ *Executed ${calls.size} Tool Call(s)* (conf `${String.format("%.2f", confidence)}`)\n" +
            "🧠 _${reasoning}_\n\n" +
            outputs.joinToString("\n")
        )
    }

    private fun sendTelegramMessage(chatId: Long, markdownText: String) {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", markdownText)
            .put("parse_mode", "Markdown")
            .toString()
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    private fun buildNotification(content: String): Notification {
        val channelId = "needle3_telegram_daemon"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Needle 3 Telegram Service", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Needle 3 Phone Agent")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
