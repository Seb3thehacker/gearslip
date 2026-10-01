// The bridge between Kotlin's SpeechEngine and whisper.cpp: load a model, turn one recorded
// phrase into text, free the model. Recording and deciding when a phrase ends stay in Kotlin.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <string>
#include "whisper.h"

#define TAG "GearslipSpeech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static whisper_context_params context_params() {
    whisper_context_params p = whisper_context_default_params();
    p.use_gpu = false;
    return p;
}

// Streams a model straight out of the APK, so the bundled one is never copied to storage.
static size_t asset_read(void *ctx, void *output, size_t size) {
    int n = AAsset_read(static_cast<AAsset *>(ctx), output, size);
    return n > 0 ? static_cast<size_t>(n) : 0;
}
static bool asset_eof(void *ctx) { return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0; }
static void asset_close(void *ctx) { AAsset_close(static_cast<AAsset *>(ctx)); }

extern "C" JNIEXPORT jlong JNICALL
Java_app_seb3thehacker_gearslip_speech_SpeechEngine_nativeInitAsset(JNIEnv *env, jobject, jobject assets, jstring jpath) {
    AAssetManager *manager = AAssetManager_fromJava(env, assets);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    AAsset *asset = AAssetManager_open(manager, path, AASSET_MODE_STREAMING);
    env->ReleaseStringUTFChars(jpath, path);
    if (!asset) return 0;
    whisper_model_loader loader = {asset, asset_read, asset_eof, asset_close};
    return reinterpret_cast<jlong>(whisper_init_with_params(&loader, context_params()));
}

// Bytes, not a jstring: NewStringUTF wants modified UTF-8 and aborts on some of what Whisper can
// write (four-byte characters), so Kotlin decodes the plain UTF-8 instead.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_seb3thehacker_gearslip_speech_SpeechEngine_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray jsamples, jstring jlang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewByteArray(0);
    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    const char *lang = env->GetStringUTFChars(jlang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    // The ACFT models are trained for a context cut to the audio's own length (1500 frames is
    // 30 s, so 50 per second), which is what makes a three-second phrase fast. A little extra
    // keeps the last word from being clipped.
    p.audio_ctx = std::min(1500, static_cast<int>(std::ceil(n / 320.0)) + 32);

    int rc = whisper_full(ctx, p, samples, n);
    env->ReleaseStringUTFChars(jlang, lang);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return env->NewByteArray(0);
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(ctx); i++) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_seb3thehacker_gearslip_speech_SpeechEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
