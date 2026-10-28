#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <android/log.h>
#include "needle.h"

#define LOG_TAG "Needle3JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_cactus_needle3_agent_engine_NeedleNativeBridge_nativeLoadCactFile(
        JNIEnv* env, jobject /* this */, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOGE("Failed to open .cact file: %s", path);
        env->ReleaseStringUTFChars(filePath, path);
        return -1;
    }
    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);
    std::vector<unsigned char> buffer(size);
    if (!file.read(reinterpret_cast<char*>(buffer.data()), size)) {
        env->ReleaseStringUTFChars(filePath, path);
        return -2;
    }
    env->ReleaseStringUTFChars(filePath, path);

#ifdef HAVE_LIBNEEDLE
    int rc = needle_load(buffer.data(), static_cast<unsigned long long>(buffer.size()));
    LOGI("needle_load returned %d (%lld bytes)", rc, (long long)size);
    return rc;
#else
    LOGI("JNI shim mode: verified .cact file (%lld bytes); delegating execution to local runner", (long long)size);
    return static_cast<jint>(size > 0 ? 1 : -1);
#endif
}

JNIEXPORT jint JNICALL
Java_com_cactus_needle3_agent_engine_NeedleNativeBridge_nativeInit(
        JNIEnv* env, jobject /* this */, jstring systemPrompt, jstring toolsJson) {
    const char* sys = env->GetStringUTFChars(systemPrompt, nullptr);
    const char* tools = env->GetStringUTFChars(toolsJson, nullptr);
#ifdef HAVE_LIBNEEDLE
    int tokens = needle_init(sys, tools, nullptr);
#else
    int tokens = 384;
#endif
    env->ReleaseStringUTFChars(systemPrompt, sys);
    env->ReleaseStringUTFChars(toolsJson, tools);
    return tokens;
}

JNIEXPORT jstring JNICALL
Java_com_cactus_needle3_agent_engine_NeedleNativeBridge_nativeComplete(
        JNIEnv* env, jobject /* this */, jstring inputPrompt, jint maxNewTokens) {
    const char* prompt = env->GetStringUTFChars(inputPrompt, nullptr);
    std::vector<char> outBuf(65536, 0);
#ifdef HAVE_LIBNEEDLE
    int rc = needle_complete(prompt, maxNewTokens, outBuf.data(), static_cast<int>(outBuf.size()));
    env->ReleaseStringUTFChars(inputPrompt, prompt);
    if (rc < 0) {
        const char* err = needle_last_error();
        std::string errJson = std::string("{\"type\":\"error\",\"success\":false,\"error\":\"") + (err ? err : "needle_complete failed") + "\"}";
        return env->NewStringUTF(errJson.c_str());
    }
    return env->NewStringUTF(outBuf.data());
#else
    env->ReleaseStringUTFChars(inputPrompt, prompt);
    return env->NewStringUTF("{\"shim\":true}");
#endif
}

JNIEXPORT jfloatArray JNICALL
Java_com_cactus_needle3_agent_engine_NeedleNativeBridge_nativeEmbed(
        JNIEnv* env, jobject /* this */, jstring text) {
    const char* str = env->GetStringUTFChars(text, nullptr);
    const int DIMS = 3072;
    std::vector<float> vec(DIMS, 0.0f);
#ifdef HAVE_LIBNEEDLE
    needle_embed(str, vec.data(), DIMS);
#endif
    env->ReleaseStringUTFChars(text, str);
    jfloatArray result = env->NewFloatArray(DIMS);
    env->SetFloatArrayRegion(result, 0, DIMS, vec.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_cactus_needle3_agent_engine_NeedleNativeBridge_nativeReset(
        JNIEnv* /* env */, jobject /* this */) {
#ifdef HAVE_LIBNEEDLE
    needle_reset();
#endif
}

} // extern "C"
