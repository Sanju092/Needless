package com.cactus.needle3.agent.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.cactus.needle3.agent.engine.ModelDownloadManager
import com.cactus.needle3.agent.service.TelegramBotForegroundService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloader = ModelDownloadManager(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val scope = rememberCoroutineScope()
                var botToken by remember { mutableStateOf("") }
                var chatId by remember { mutableStateOf("") }
                var selectedLayers by remember { mutableIntStateOf(20) }
                var downloadStatus by remember {
                    mutableStateOf(
                        if (downloader.isNeedleReady()) "Ready: needle3.cact on device"
                        else "Not downloaded yet (29 MB needle3.cact + 16.9 MB whistle.cact)"
                    )
                }
                var downloadProgress by remember { mutableIntStateOf(if (downloader.isNeedleReady()) 100 else 0) }
                var serviceRunning by remember { mutableStateOf(false) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("🌵 Needle 3 Mobile Telegram Agent", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Download Cactus Compute's 8-29 MB Needle 3 & Whistle models directly onto your phone and execute 28 hardware, app, SMS, and UI automation tools from Telegram.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Card 1: Model Downloader
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("1. On-Device Model Weights (.cact)", style = MaterialTheme.typography.titleMedium)
                                Text(downloadStatus, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Laddered SAN Subnetwork Depth: $selectedLayers Layers")
                                Slider(
                                    value = selectedLayers.toFloat(),
                                    onValueChange = { selectedLayers = it.toInt() },
                                    valueRange = 2f..20f,
                                    steps = 8
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            downloadStatus = "Starting download from Hugging Face..."
                                            val res = downloader.downloadAllAssets(includeWhistle = true) { stage, mb, total, pct ->
                                                downloadStatus = "$stage: %.1f / %.1f MB (%d%%)".format(mb, total, pct)
                                                downloadProgress = pct
                                            }
                                            downloadStatus = if (res.isSuccess) {
                                                "✅ Ready: needle3.cact (29 MB) & whistle.cact (16.9 MB) cached offline!"
                                            } else {
                                                "❌ Error: ${res.exceptionOrNull()?.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Download Needle 3 + Whistle to Phone")
                                }
                            }
                        }

                        // Card 2: Telegram Bot Configuration
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("2. Telegram Bot Bridge", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = botToken,
                                    onValueChange = { botToken = it },
                                    label = { Text("Telegram Bot Token (@BotFather)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = chatId,
                                    onValueChange = { chatId = it },
                                    label = { Text("Authorized Telegram User/Chat ID") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        val svcIntent = Intent(this@MainActivity, TelegramBotForegroundService::class.java).apply {
                                            putExtra("BOT_TOKEN", botToken.trim())
                                            putExtra("ALLOWED_CHAT_ID", chatId.trim().toLongOrNull() ?: 0L)
                                            putExtra("LAYERS", selectedLayers)
                                        }
                                        if (!serviceRunning) {
                                            startForegroundService(svcIntent)
                                            serviceRunning = true
                                        } else {
                                            stopService(svcIntent)
                                            serviceRunning = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (serviceRunning) "Stop Telegram Foreground Service" else "Start Telegram Bot Listener")
                                }
                            }
                        }

                        // Card 3: Accessibility & System Permissions
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("3. Full Device Control Permissions", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(
                                    onClick = {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Enable Needle 3 Accessibility Service (UI Automation)")
                                }
                                OutlinedButton(
                                    onClick = {
                                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Grant Write System Settings (Brightness / Audio)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
