# Needle 3 Standalone Android Phone Agent + Telegram Bot

A standalone Android application (Kotlin + Jetpack Compose + NDK/JNI) that downloads **Cactus Compute's Needle 3** (`needle3.cact`, 8–29 MB 2-bit Laddered Simple Attention Network) and **Whistle** (`whistle.cact`, 16.9 MB speech-to-text) directly onto your phone and lets you execute **28 on-device hardware, app, messaging, and screen-automation actions** remotely via a **Telegram Bot**.

## Architecture & Key Components

1. **On-Device Model Downloader (`ModelDownloadManager.kt`)**
   - Streams `needle3.cact` (29 MB), `whistle.cact` (16.9 MB), and the `android-arm64/needle` engine directly from [Cactus-Compute/needle3 on Hugging Face](https://huggingface.co/Cactus-Compute/needle3) into the app's private storage (`filesDir/needle3_models`).
   - Supports dynamic intelligence laddering from **2 layers (25M params, ~8 MB)** up to **20 layers (121M params, 29 MB)**.

2. **Native C API / JNI & Runner Bridge (`needle.h`, `needle_jni.cpp`, `NeedleEngineBridge.kt`)**
   - Binds `needle_load`, `needle_init`, `needle_complete`, `needle_transcribe`, `needle_embed`, and `needle_reset` from `libneedle.a`.
   - Enforces **Confidence Gating**:
     - `confidence >= 0.70`: Executes tool call chain immediately on the phone.
     - `0.10 <= confidence < 0.70`: Sends a confirmation prompt to Telegram (`/confirm` or `/cancel`).
     - `confidence < 0.10` or empty `function_calls`: Refuses safely without guessing.

3. **28 Phone Action Tools (`assets/tools.json` & `PhoneToolExecutor.kt` & `NeedleAccessibilityService.kt`)**
   - **Hardware & Sensors**: `toggle_flashlight`, `get_battery_status`, `set_screen_brightness`, `set_volume`, `adjust_setting`, `toggle_setting`, `vibrate_phone`, `read_hardware_sensors`
   - **Connectivity & GPS**: `get_wifi_and_network_info`, `get_gps_location`
   - **SMS, OTPs, Calls & Contacts**: `send_sms`, `read_recent_sms`, `extract_latest_otp` (auto-copies 2FA codes to clipboard), `call_contact`, `search_contacts`
   - **Audio, Speech & Camera**: `speak_aloud` (Android TTS), `control_media`, `take_photo`, `record_audio_clip`
   - **Apps, System & UI Automation**: `open_app`, `open_website`, `set_alarm_or_timer`, `take_screenshot`, `ui_screen_action` (clicks buttons by text, types into fields, scrolls, presses Home/Back/Lock via `AccessibilityService`), `clipboard_manager`
   - **Needle 3 Local Intelligence**: `extract_structured_data` (grammar-constrained JSON parsing), `save_memory_note` & `search_memory_vault` (3,072-dim `needle_embed` local vector search)

4. **Telegram Bot Foreground Service & Fine-Tuning Logger (`TelegramBotForegroundService.kt`)**
   - Runs as a persistent Android `ForegroundService` polling `getUpdates` for your whitelisted Telegram `chat_id`.
   - Logs every verified command to `train.jsonl` so you can export and fine-tune Needle 3 on your own phrasing:
     ```bash
     pip install "cactus-needle[train]"
     needle finetune train.jsonl --epochs 10 --out adapter.safetensors
     needle build --lora adapter.safetensors --layers 8 --out tuned.cact
     ```

## Quick Build Instructions

1. Fetch the prebuilt `android-arm64` native library (`libneedle.a` and `needle.h`):
   ```bash
   chmod +x scripts/fetch_needle_android.sh
   ./scripts/fetch_needle_android.sh
   ```
2. Open the folder in **Android Studio** (Ladybug or newer) with NDK 26+ installed, or build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install the APK on your Android phone, tap **Download Needle 3 + Whistle to Phone**, paste your `@BotFather` token and Telegram User ID, enable the **Needle 3 Accessibility Service**, and tap **Start Telegram Bot Listener**.
