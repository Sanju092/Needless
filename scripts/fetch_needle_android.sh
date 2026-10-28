#!/usr/bin/env bash
set -euo pipefail

echo "=== Fetching Cactus Compute Needle 3 Android Engine & Weights ==="
mkdir -p app/src/main/cpp/lib/arm64-v8a
mkdir -p app/src/main/assets

if command -v needle &> /dev/null; then
    echo "[1/3] Using cactus-needle CLI to fetch android-arm64 platform folder..."
    needle build --platform android-arm64 --out ./build_needle_android
    cp ./build_needle_android/needle.h app/src/main/cpp/needle.h
    cp ./build_needle_android/libneedle.a app/src/main/cpp/lib/arm64-v8a/libneedle.a
    cp ./build_needle_android/needle app/src/main/assets/needle_runner
    cp ./build_needle_android/needle3.cact app/src/main/assets/needle3.cact
else
    echo "[1/3] Downloading directly from Hugging Face (Cactus-Compute/needle3 & whistle)..."
    curl -L "https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle.h" -o app/src/main/cpp/needle.h
    curl -L "https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/libneedle.a" -o app/src/main/cpp/lib/arm64-v8a/libneedle.a
    curl -L "https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle" -o app/src/main/assets/needle_runner
fi

echo "[2/3] Note: needle3.cact (29 MB) and whistle.cact (16.9 MB) can also be downloaded directly inside the app UI!"
echo "Done. Open this project in Android Studio and build the APK."
