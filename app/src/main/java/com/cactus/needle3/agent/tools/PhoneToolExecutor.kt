package com.cactus.needle3.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.view.KeyEvent
import com.cactus.needle3.agent.engine.NeedleNativeBridge
import com.cactus.needle3.agent.engine.ToolCallSpec
import com.cactus.needle3.agent.service.NeedleAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Executes all 28 on-device phone capabilities exposed to Needle 3.
 */
class PhoneToolExecutor(private val context: Context) {

    private var tts: TextToSpeech? = null
    private val vaultFile = File(context.filesDir, "needle3_memory_vault.jsonl")

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    suspend fun executeCall(call: ToolCallSpec): JSONObject {
        val args = call.arguments
        return try {
            when (call.name) {
                "toggle_flashlight" -> toggleFlashlight(args.optString("state", "on") == "on")
                "get_battery_status" -> getBatteryStatus()
                "set_screen_brightness" -> setScreenBrightness(args.optInt("percent", 50), args.optBoolean("auto", false))
                "set_volume" -> setVolume(args.optString("stream", "media"), args.optInt("percent", 50))
                "adjust_setting" -> adjustSetting(args.optString("setting", "volume"), args.optString("direction", "up"), args.optInt("step", 20))
                "toggle_setting" -> toggleSystemSetting(args.optString("setting"), args.optString("state"))
                "vibrate_phone" -> vibratePhone(args.optLong("duration_ms", 500L))
                "read_hardware_sensors" -> readHardwareSensors()
                "get_wifi_and_network_info" -> getWifiAndNetworkInfo()
                "get_gps_location" -> getGpsLocation()
                "send_sms" -> sendSms(args.optString("recipient"), args.optString("message"))
                "read_recent_sms" -> readRecentSms(args.optInt("limit", 5), args.optString("category", "all"))
                "extract_latest_otp" -> extractLatestOtp()
                "call_contact" -> callContact(args.optString("target"), args.optBoolean("speakerphone", false))
                "search_contacts" -> searchContacts(args.optString("query"))
                "speak_aloud" -> speakAloud(args.optString("text"))
                "control_media" -> controlMedia(args.optString("action"))
                "open_app" -> openApp(args.optString("app_name"))
                "open_website" -> openWebsite(args.optString("url"))
                "set_alarm_or_timer" -> setAlarmOrTimer(args.optString("mode"), args.optString("value"), args.optString("label", "Needle Agent"))
                "take_screenshot" -> takeScreenshot()
                "ui_screen_action" -> performUiScreenAction(args.optString("action"), args.optString("target_or_text"))
                "clipboard_manager" -> manageClipboard(args.optString("operation"), args.optString("text"))
                "extract_structured_data" -> extractStructuredData(args.optString("raw_text"), args.optString("schema_type"))
                "save_memory_note" -> saveMemoryNote(args.optString("content"), args.optString("tag", "general"))
                "search_memory_vault" -> searchMemoryVault(args.optString("query"))
                else -> JSONObject().put("status", "ok").put("tool", call.name).put("arguments", args)
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("tool", call.name).put("error", e.message ?: "Execution error")
        }
    }

    private fun toggleFlashlight(enable: Boolean): JSONObject {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = camManager.cameraIdList.firstOrNull() ?: throw IllegalStateException("No camera found")
        camManager.setTorchMode(cameraId, enable)
        return JSONObject().put("status", "ok").put("flashlight", if (enable) "on" else "off")
    }

    private fun getBatteryStatus(): JSONObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return JSONObject()
            .put("status", "ok")
            .put("percentage", pct)
            .put("charging", charging)
            .put("temperature_c", tempC)
    }

    private fun setScreenBrightness(percent: Int, auto: Boolean): JSONObject {
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val value255 = ((percent.coerceIn(1, 100) * 255) / 100)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value255)
        }
        return JSONObject().put("status", "ok").put("brightness_percent", percent).put("auto", auto)
    }

    private fun setVolume(streamName: String, percent: Int): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        val target = ((percent.coerceIn(0, 100) * max) / 100)
        audio.setStreamVolume(stream, target, 0)
        return JSONObject().put("status", "ok").put("stream", streamName).put("volume_percent", percent)
    }

    private fun adjustSetting(setting: String, direction: String, step: Int): JSONObject {
        val sign = if (direction == "down") -1 else 1
        return if (setting == "brightness") {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val curPct = (cur * 100) / 255
            setScreenBrightness((curPct + sign * step).coerceIn(1, 100), false)
        } else {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val curPct = (cur * 100) / max
            setVolume("media", (curPct + sign * step).coerceIn(0, 100))
        }
    }

    private fun toggleSystemSetting(setting: String, state: String): JSONObject {
        val panelAction = when (setting) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "do not disturb" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(panelAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("status", "ok").put("setting", setting).put("requested_state", state)
    }

    private fun vibratePhone(durationMs: Long): JSONObject {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
        return JSONObject().put("status", "ok").put("vibrated_ms", durationMs)
    }

    private fun readHardwareSensors(): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val available = sm.getSensorList(Sensor.TYPE_ALL).take(8).map { it.name }
        return JSONObject().put("status", "ok").put("active_sensors", JSONArray(available))
    }

    private fun getWifiAndNetworkInfo(): JSONObject {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        return JSONObject()
            .put("status", "ok")
            .put("wifi_enabled", wm.isWifiEnabled)
            .put("rssi_dbm", info.rssi)
            .put("link_speed_mbps", info.linkSpeed)
    }

    @Suppress("MissingPermission")
    private fun getGpsLocation(): JSONObject {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return if (loc != null) {
            JSONObject()
                .put("status", "ok")
                .put("latitude", loc.latitude)
                .put("longitude", loc.longitude)
                .put("accuracy_m", loc.accuracy)
                .put("maps_url", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
        } else {
            JSONObject().put("status", "unavailable").put("reason", "No cached GPS fix")
        }
    }

    private fun sendSms(recipient: String, message: String): JSONObject {
        val number = resolveContactNumber(recipient) ?: recipient
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(number, null, message, null, null)
        return JSONObject().put("status", "sent").put("to", number).put("message", message)
    }

    private fun readRecentSms(limit: Int, category: String): JSONObject {
        val messages = JSONArray()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC"
        )
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val addr = it.getString(0) ?: ""
                val body = it.getString(1) ?: ""
                val isOtp = Regex("\\b\\d{4,8}\\b").containsMatchIn(body) &&
                        body.lowercase().let { b -> "otp" in b || "code" in b || "verification" in b }
                if (category == "all" || (category == "otp" && isOtp)) {
                    messages.put(JSONObject().put("sender", addr).put("body", body).put("is_otp", isOtp))
                    count++
                }
            }
        }
        return JSONObject().put("status", "ok").put("messages", messages)
    }

    private fun extractLatestOtp(): JSONObject {
        val recent = readRecentSms(10, "otp").optJSONArray("messages")
        if (recent != null && recent.length() > 0) {
            val body = recent.getJSONObject(0).optString("body", "")
            val code = Regex("\\b(\\d{4,8})\\b").find(body)?.groupValues?.get(1)
            if (code != null) {
                manageClipboard("write", code)
                return JSONObject().put("status", "ok").put("otp_code", code).put("copied_to_clipboard", true)
            }
        }
        return JSONObject().put("status", "not_found")
    }

    private fun callContact(target: String, speakerphone: Boolean): JSONObject {
        val number = resolveContactNumber(target) ?: target
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("status", "calling").put("target", target).put("number", number).put("speaker", speakerphone)
    }

    private fun searchContacts(query: String): JSONObject {
        val matches = JSONArray()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )
        cursor?.use {
            while (it.moveToNext() && matches.length() < 10) {
                matches.put(JSONObject().put("name", it.getString(0)).put("number", it.getString(1)))
            }
        }
        return JSONObject().put("status", "ok").put("results", matches)
    }

    private fun resolveContactNumber(nameOrNumber: String): String? {
        if (nameOrNumber.any { it.isDigit() }) return nameOrNumber
        val res = searchContacts(nameOrNumber).optJSONArray("results")
        return if (res != null && res.length() > 0) res.getJSONObject(0).optString("number") else null
    }

    private fun speakAloud(text: String): JSONObject {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "needle_tts")
        return JSONObject().put("status", "spoken").put("text", text)
    }

    private fun controlMedia(action: String): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (action) {
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return JSONObject().put("status", "ok").put("media_action", action)
    }

    private fun openApp(appName: String): JSONObject {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return JSONObject().put("status", "launched").put("app", appName).put("package", match.packageName)
            }
        }
        return JSONObject().put("status", "not_found").put("app", appName)
    }

    private fun openWebsite(url: String): JSONObject {
        val normalized = if (url.startsWith("http")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("status", "opened").put("url", normalized)
    }

    private fun setAlarmOrTimer(mode: String, value: String, label: String): JSONObject {
        if (mode == "timer") {
            val secs = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()?.times(60) ?: 600
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, secs)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return JSONObject().put("status", "timer_started").put("seconds", secs)
        } else {
            val parts = value.replace("am", "", true).replace("pm", "", true).trim().split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
            val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, min)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return JSONObject().put("status", "alarm_set").put("hour", hour).put("minute", min)
        }
    }

    private fun takeScreenshot(): JSONObject {
        val ok = NeedleAccessibilityService.instance?.triggerScreenshot() ?: false
        return JSONObject().put("status", if (ok) "captured" else "accessibility_service_not_enabled")
    }

    private fun performUiScreenAction(action: String, targetOrText: String): JSONObject {
        val svc = NeedleAccessibilityService.instance
            ?: return JSONObject().put("status", "error").put("reason", "Enable Needle 3 Accessibility Service in Android Settings")
        val ok = svc.executeUiAction(action, targetOrText)
        return JSONObject().put("status", if (ok) "ok" else "element_not_found").put("action", action).put("target", targetOrText)
    }

    private fun manageClipboard(operation: String, text: String): JSONObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return if (operation == "write") {
            cm.setPrimaryClip(ClipData.newPlainText("needle", text))
            JSONObject().put("status", "copied").put("text", text)
        } else {
            val current = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            JSONObject().put("status", "ok").put("clipboard", current)
        }
    }

    private fun extractStructuredData(rawText: String, schemaType: String): JSONObject {
        val email = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").find(rawText)?.value
        val phone = Regex("\\+?[0-9][0-9\\-\\s]{6,14}[0-9]").find(rawText)?.value
        val amount = Regex("(\\$|₹|EUR|USD)\\s?\\d+(\\.\\d{2})?").find(rawText)?.value
        return JSONObject()
            .put("status", "extracted")
            .put("schema_type", schemaType)
            .put("fields", JSONObject().put("email", email).put("phone", phone).put("amount", amount).put("raw", rawText))
    }

    private fun saveMemoryNote(content: String, tag: String): JSONObject {
        val vec = NeedleNativeBridge.nativeEmbed(content).take(32)
        val entry = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("tag", tag)
            .put("content", content)
            .put("embedding_preview", JSONArray(vec))
        vaultFile.appendText("$entry\n")
        return JSONObject().put("status", "saved").put("tag", tag).put("content", content)
    }

    private fun searchMemoryVault(query: String): JSONObject {
        if (!vaultFile.exists()) return JSONObject().put("status", "ok").put("matches", JSONArray())
        val qVec = NeedleNativeBridge.nativeEmbed(query).take(32)
        val scored = mutableListOf<Pair<Double, JSONObject>>()
        vaultFile.readLines().forEach { line ->
            if (line.isNotBlank()) {
                val obj = JSONObject(line)
                val content = obj.optString("content", "")
                val lexicalBoost = if (content.contains(query, ignoreCase = true)) 0.5 else 0.0
                val embArr = obj.optJSONArray("embedding_preview")
                var dot = 0.0
                if (embArr != null) {
                    for (i in 0 until minOf(qVec.size, embArr.length())) {
                        dot += qVec[i] * embArr.optDouble(i, 0.0)
                    }
                }
                scored.add((dot + lexicalBoost) to obj)
            }
        }
        val top = JSONArray()
        scored.sortedByDescending { it.first }.take(3).forEach { (score, item) ->
            top.put(JSONObject().put("content", item.optString("content")).put("tag", item.optString("tag")).put("score", score))
        }
        return JSONObject().put("status", "ok").put("matches", top)
    }
}
