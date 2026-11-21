#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define LOG_TAG "KestrelVirtualHAL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace kestrel::hal {

struct VirtualDisplayProfile {
    int32_t width_px = 1080;
    int32_t height_px = 2340;
    int32_t dpi = 420;
    int32_t refresh_hz = 120;
};

struct VirtualSensorFrame {
    float accel_x = 0.0f;
    float accel_y = 9.81f;
    float accel_z = 0.0f;
    float gyro_x = 0.0f;
    float gyro_y = 0.0f;
    float gyro_z = 0.0f;
};

struct VirtualSurfaceState {
    ANativeWindow* window = nullptr;
    int32_t width = 0;
    int32_t height = 0;
    uint64_t frames_presented = 0;
    int64_t last_blit_us = 0;
};

// Minimal stable AAudio C ABI declarations (loaded dynamically from libaaudio.so on API 26+)
struct AAudioStreamBuilderOpaque;
struct AAudioStreamOpaque;
using AAudioResult = int32_t;
constexpr AAudioResult kAAudioOk = 0;
constexpr int32_t kAAudioFormatPcmI16 = 1;
constexpr int32_t kAAudioPerfLowLatency = 12;

struct AAudioFunctions {
    void* handle = nullptr;
    bool attempted = false;
    bool available = false;
    AAudioResult (*createStreamBuilder)(AAudioStreamBuilderOpaque**) = nullptr;
    void (*setSampleRate)(AAudioStreamBuilderOpaque*, int32_t) = nullptr;
    void (*setChannelCount)(AAudioStreamBuilderOpaque*, int32_t) = nullptr;
    void (*setFormat)(AAudioStreamBuilderOpaque*, int32_t) = nullptr;
    void (*setPerformanceMode)(AAudioStreamBuilderOpaque*, int32_t) = nullptr;
    AAudioResult (*openStream)(AAudioStreamBuilderOpaque*, AAudioStreamOpaque**) = nullptr;
    void (*deleteBuilder)(AAudioStreamBuilderOpaque*) = nullptr;
    AAudioResult (*requestStart)(AAudioStreamOpaque*) = nullptr;
    AAudioResult (*requestStop)(AAudioStreamOpaque*) = nullptr;
    AAudioResult (*closeStream)(AAudioStreamOpaque*) = nullptr;
    int32_t (*write)(AAudioStreamOpaque*, const void*, int32_t, int64_t) = nullptr;
    int32_t (*getSampleRate)(AAudioStreamOpaque*) = nullptr;
};

struct VirtualAudioState {
    AAudioStreamOpaque* stream = nullptr;
    bool active = false;
    int32_t sample_rate = 48000;
    uint64_t frames_written = 0;
    std::vector<int16_t> ring_scratch;
};

std::mutex g_hal_mutex;
std::unordered_map<std::string, VirtualDisplayProfile> g_displays;
std::unordered_map<std::string, VirtualSensorFrame> g_sensors;
std::unordered_map<std::string, VirtualSurfaceState> g_surfaces;
std::unordered_map<std::string, VirtualAudioState> g_audio;
AAudioFunctions g_aaudio;

std::string jstring_to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* raw = env->GetStringUTFChars(s, nullptr);
    if (!raw) return {};
    std::string out(raw);
    env->ReleaseStringUTFChars(s, raw);
    return out;
}

bool ensure_aaudio_loaded_locked() {
    if (g_aaudio.attempted) return g_aaudio.available;
    g_aaudio.attempted = true;
    g_aaudio.handle = dlopen("libaaudio.so", RTLD_NOW);
    if (!g_aaudio.handle) {
        LOGW("libaaudio.so unavailable on this API level; audio bridge running in ring-buffer fallback");
        return false;
    }

    auto sym = [&](const char* name) -> void* { return dlsym(g_aaudio.handle, name); };
    g_aaudio.createStreamBuilder = reinterpret_cast<decltype(g_aaudio.createStreamBuilder)>(sym("AAudio_createStreamBuilder"));
    g_aaudio.setSampleRate = reinterpret_cast<decltype(g_aaudio.setSampleRate)>(sym("AAudioStreamBuilder_setSampleRate"));
    g_aaudio.setChannelCount = reinterpret_cast<decltype(g_aaudio.setChannelCount)>(sym("AAudioStreamBuilder_setChannelCount"));
    g_aaudio.setFormat = reinterpret_cast<decltype(g_aaudio.setFormat)>(sym("AAudioStreamBuilder_setFormat"));
    g_aaudio.setPerformanceMode = reinterpret_cast<decltype(g_aaudio.setPerformanceMode)>(sym("AAudioStreamBuilder_setPerformanceMode"));
    g_aaudio.openStream = reinterpret_cast<decltype(g_aaudio.openStream)>(sym("AAudioStreamBuilder_openStream"));
    g_aaudio.deleteBuilder = reinterpret_cast<decltype(g_aaudio.deleteBuilder)>(sym("AAudioStreamBuilder_delete"));
    g_aaudio.requestStart = reinterpret_cast<decltype(g_aaudio.requestStart)>(sym("AAudioStream_requestStart"));
    g_aaudio.requestStop = reinterpret_cast<decltype(g_aaudio.requestStop)>(sym("AAudioStream_requestStop"));
    g_aaudio.closeStream = reinterpret_cast<decltype(g_aaudio.closeStream)>(sym("AAudioStream_close"));
    g_aaudio.write = reinterpret_cast<decltype(g_aaudio.write)>(sym("AAudioStream_write"));
    g_aaudio.getSampleRate = reinterpret_cast<decltype(g_aaudio.getSampleRate)>(sym("AAudioStream_getSampleRate"));

    g_aaudio.available = g_aaudio.createStreamBuilder && g_aaudio.setSampleRate &&
                         g_aaudio.setChannelCount && g_aaudio.setFormat &&
                         g_aaudio.setPerformanceMode && g_aaudio.openStream &&
                         g_aaudio.deleteBuilder && g_aaudio.requestStart &&
                         g_aaudio.requestStop && g_aaudio.closeStream &&
                         g_aaudio.write;
    LOGI("AAudio dynamic resolve: %s", g_aaudio.available ? "ACTIVE" : "PARTIAL_FALLBACK");
    return g_aaudio.available;
}

void release_surface_locked(VirtualSurfaceState& st) {
    if (st.window) {
        ANativeWindow_release(st.window);
        st.window = nullptr;
    }
    st.width = 0;
    st.height = 0;
}

void close_audio_locked(VirtualAudioState& audio) {
    if (audio.stream && g_aaudio.available) {
        g_aaudio.requestStop(audio.stream);
        g_aaudio.closeStream(audio.stream);
    }
    audio.stream = nullptr;
    audio.active = false;
}

} // namespace kestrel::hal

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_engine_NativeEngineBridge_nativeGetHalVersion(
        JNIEnv* env,
        jclass /* cls */) {
    return env->NewStringUTF("kestrel_hal-cpp17/0.2.0 (ANativeWindow+GLES20+AAudio)");
}

JNIEXPORT void JNICALL
Java_com_example_engine_NativeEngineBridge_nativePushDisplayProfile(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id,
        jint width_px,
        jint height_px,
        jint dpi,
        jint refresh_hz) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    kestrel::hal::g_displays[id] = {width_px, height_px, dpi, refresh_hz};
    LOGI("HWC profile updated for %s: %dx%d @ %ddpi (%dHz)",
         id.c_str(), width_px, height_px, dpi, refresh_hz);
}

JNIEXPORT void JNICALL
Java_com_example_engine_NativeEngineBridge_nativePushSensorSample(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id,
        jfloat ax, jfloat ay, jfloat az,
        jfloat gx, jfloat gy, jfloat gz) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    kestrel::hal::g_sensors[id] = {ax, ay, az, gx, gy, gz};
}

JNIEXPORT jint JNICALL
Java_com_example_engine_NativeEngineBridge_nativeAttachSurface(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id,
        jobject surface) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty() || !surface) return -1;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return -2;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    auto& st = kestrel::hal::g_surfaces[id];
    kestrel::hal::release_surface_locked(st);

    int32_t w = ANativeWindow_getWidth(window);
    int32_t h = ANativeWindow_getHeight(window);
    int32_t target_w = w > 0 ? std::min(w, 640) : 360;
    int32_t target_h = h > 0 ? std::min(h, 480) : 240;
    ANativeWindow_setBuffersGeometry(window, target_w, target_h, WINDOW_FORMAT_RGBA_8888);

    st.window = window;
    st.width = target_w;
    st.height = target_h;
    LOGI("Attached ANativeWindow for %s (%dx%d)", id.c_str(), target_w, target_h);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_engine_NativeEngineBridge_nativeDetachSurface(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    auto it = kestrel::hal::g_surfaces.find(id);
    if (it != kestrel::hal::g_surfaces.end()) {
        kestrel::hal::release_surface_locked(it->second);
    }
}

JNIEXPORT jint JNICALL
Java_com_example_engine_NativeEngineBridge_nativeBlitGuestFrame(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id,
        jint frame_index,
        jint accent_rgb) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return -1;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    auto it = kestrel::hal::g_surfaces.find(id);
    if (it == kestrel::hal::g_surfaces.end() || !it->second.window) return -2;

    auto t0 = std::chrono::steady_clock::now();
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(it->second.window, &buffer, nullptr) != 0) {
        return -3;
    }
    if (!buffer.bits || buffer.width <= 0 || buffer.height <= 0 || buffer.stride < buffer.width) {
        LOGW("Invalid locked ANativeWindow_Buffer for %s (bits=%p, %dx%d stride=%d)",
             id.c_str(), buffer.bits, buffer.width, buffer.height, buffer.stride);
        ANativeWindow_unlockAndPost(it->second.window);
        return -4;
    }

    auto* pixels = reinterpret_cast<uint32_t*>(buffer.bits);
    const int32_t w = buffer.width;
    const int32_t h = buffer.height;
    const int32_t stride = buffer.stride;
    const auto sensor_it = kestrel::hal::g_sensors.find(id);
    const kestrel::hal::VirtualSensorFrame sensor =
            (sensor_it != kestrel::hal::g_sensors.end())
                    ? sensor_it->second
                    : kestrel::hal::VirtualSensorFrame{};

    const uint8_t ar = static_cast<uint8_t>((accent_rgb >> 16) & 0xFF);
    const uint8_t ag = static_cast<uint8_t>((accent_rgb >> 8) & 0xFF);
    const uint8_t ab = static_cast<uint8_t>(accent_rgb & 0xFF);
    // Pack ABGR for little-endian ANativeWindow RGBA_8888
    const uint32_t accent_abgr = (0xFFu << 24) | (static_cast<uint32_t>(ab) << 16) |
                                 (static_cast<uint32_t>(ag) << 8) | static_cast<uint32_t>(ar);
    const float phase = static_cast<float>(frame_index) * 0.14f + sensor.accel_x * 0.35f;

    for (int32_t y = 0; y < h; ++y) {
        uint32_t* row = pixels + y * stride;
        const bool is_grid_y = (y % 24 == 0);
        const float wave = 0.5f * h + std::sin(phase + static_cast<float>(y) * 0.04f) * (h * 0.18f);
        for (int32_t x = 0; x < w; ++x) {
            uint32_t bg = (0xFFu << 24) | (0x14u << 16) | (0x10u << 8) | 0x0Cu;
            if (is_grid_y || (x % 24 == 0)) {
                bg = (0xFFu << 24) | (0x24u << 16) | (0x1Eu << 8) | 0x16u;
            }
            const float target_y = 0.5f * h +
                std::sin(phase + static_cast<float>(x) * 0.032f + sensor.gyro_y) * (h * 0.22f);
            if (std::abs(static_cast<float>(y) - target_y) < 2.2f ||
                std::abs(static_cast<float>(x) - wave * 0.4f - w * 0.3f) < 1.5f) {
                row[x] = accent_abgr;
            } else {
                row[x] = bg;
            }
        }
    }

    ANativeWindow_unlockAndPost(it->second.window);
    auto t1 = std::chrono::steady_clock::now();
    it->second.frames_presented += 1;
    it->second.last_blit_us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_engine_NativeEngineBridge_nativeStartAudioStream(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id,
        jint sample_rate_hz) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return -1;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    auto& audio = kestrel::hal::g_audio[id];
    kestrel::hal::close_audio_locked(audio);
    audio.sample_rate = sample_rate_hz > 8000 ? sample_rate_hz : 48000;

    if (!kestrel::hal::ensure_aaudio_loaded_locked()) {
        audio.active = true; // Ring buffer staged in software mode
        return 1;
    }

    kestrel::hal::AAudioStreamBuilderOpaque* builder = nullptr;
    if (kestrel::hal::g_aaudio.createStreamBuilder(&builder) != kestrel::hal::kAAudioOk || !builder) {
        LOGW("AAudio createStreamBuilder failed for %s; activating software ring-buffer fallback", id.c_str());
        audio.active = true;
        return 1;
    }
    kestrel::hal::g_aaudio.setSampleRate(builder, audio.sample_rate);
    kestrel::hal::g_aaudio.setChannelCount(builder, 2);
    kestrel::hal::g_aaudio.setFormat(builder, kestrel::hal::kAAudioFormatPcmI16);
    kestrel::hal::g_aaudio.setPerformanceMode(builder, kestrel::hal::kAAudioPerfLowLatency);

    kestrel::hal::AAudioStreamOpaque* stream = nullptr;
    auto open_rc = kestrel::hal::g_aaudio.openStream(builder, &stream);
    kestrel::hal::g_aaudio.deleteBuilder(builder);
    if (open_rc != kestrel::hal::kAAudioOk || !stream) {
        LOGW("AAudio openStream failed (rc=%d) for %s; activating software ring-buffer fallback", open_rc, id.c_str());
        audio.active = true;
        return 1;
    }
    auto start_rc = kestrel::hal::g_aaudio.requestStart(stream);
    if (start_rc != kestrel::hal::kAAudioOk) {
        LOGW("AAudio requestStart failed (rc=%d) for %s; activating software ring-buffer fallback", start_rc, id.c_str());
        kestrel::hal::g_aaudio.closeStream(stream);
        audio.active = true;
        return 1;
    }

    if (kestrel::hal::g_aaudio.getSampleRate) {
        audio.sample_rate = kestrel::hal::g_aaudio.getSampleRate(stream);
    }
    audio.stream = stream;
    audio.active = true;
    LOGI("AAudio low-latency stream started for %s @ %dHz", id.c_str(), audio.sample_rate);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_engine_NativeEngineBridge_nativePlayAudioPulse(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id,
        jint freq_hz,
        jint duration_ms) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return -1;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    auto& audio = kestrel::hal::g_audio[id];
    const int32_t rate = audio.sample_rate > 0 ? audio.sample_rate : 48000;
    const int32_t clamped_ms = std::clamp(duration_ms, 20, 250);
    const int32_t num_frames = (rate * clamped_ms) / 1000;
    const float freq = static_cast<float>(std::clamp(freq_hz, 120, 2400));

    audio.ring_scratch.resize(static_cast<size_t>(num_frames) * 2);
    for (int32_t i = 0; i < num_frames; ++i) {
        const float env_gain = 1.0f - (static_cast<float>(i) / static_cast<float>(num_frames));
        const float s = std::sin(2.0f * 3.14159265f * freq * static_cast<float>(i) / static_cast<float>(rate));
        const auto pcm = static_cast<int16_t>(s * env_gain * 5400.0f);
        audio.ring_scratch[static_cast<size_t>(i) * 2] = pcm;
        audio.ring_scratch[static_cast<size_t>(i) * 2 + 1] = pcm;
    }

    if (audio.stream && kestrel::hal::g_aaudio.available) {
        const int32_t written = kestrel::hal::g_aaudio.write(
                audio.stream, audio.ring_scratch.data(), num_frames, 50000000LL);
        if (written > 0) {
            audio.frames_written += static_cast<uint64_t>(written);
            return written;
        }
    }
    audio.frames_written += static_cast<uint64_t>(num_frames);
    return num_frames;
}

JNIEXPORT void JNICALL
Java_com_example_engine_NativeEngineBridge_nativeStopAudioStream(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    if (id.empty()) return;

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    auto it = kestrel::hal::g_audio.find(id);
    if (it != kestrel::hal::g_audio.end()) {
        kestrel::hal::close_audio_locked(it->second);
    }
}

JNIEXPORT jstring JNICALL
Java_com_example_engine_NativeEngineBridge_nativeGetHalTelemetry(
        JNIEnv* env,
        jclass /* cls */,
        jstring vm_id) {
    std::string id = kestrel::hal::jstring_to_std(env, vm_id);
    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);

    const auto disp_it = kestrel::hal::g_displays.find(id);
    const kestrel::hal::VirtualDisplayProfile disp =
            (disp_it != kestrel::hal::g_displays.end())
                    ? disp_it->second
                    : kestrel::hal::VirtualDisplayProfile{};

    const auto surf_it = kestrel::hal::g_surfaces.find(id);
    const bool surf_attached = (surf_it != kestrel::hal::g_surfaces.end() && surf_it->second.window != nullptr);
    const int32_t surf_w = (surf_it != kestrel::hal::g_surfaces.end()) ? surf_it->second.width : 0;
    const int32_t surf_h = (surf_it != kestrel::hal::g_surfaces.end()) ? surf_it->second.height : 0;
    const uint64_t surf_frames = (surf_it != kestrel::hal::g_surfaces.end()) ? surf_it->second.frames_presented : 0ULL;
    const int64_t surf_blit_us = (surf_it != kestrel::hal::g_surfaces.end()) ? surf_it->second.last_blit_us : 0LL;

    const auto audio_it = kestrel::hal::g_audio.find(id);
    const bool audio_active = (audio_it != kestrel::hal::g_audio.end()) && audio_it->second.active;
    const int32_t audio_rate = (audio_it != kestrel::hal::g_audio.end()) ? audio_it->second.sample_rate : 48000;
    const uint64_t audio_frames = (audio_it != kestrel::hal::g_audio.end()) ? audio_it->second.frames_written : 0ULL;
    const bool aaudio_ok = kestrel::hal::g_aaudio.available;

    char buf[512];
    std::snprintf(
            buf, sizeof(buf),
            "{\"surfaceAttached\":%s,\"surfaceWidth\":%d,\"surfaceHeight\":%d,"
            "\"framesPresented\":%llu,\"lastBlitUs\":%lld,"
            "\"audioActive\":%s,\"aaudioHardware\":%s,\"audioSampleRate\":%d,\"audioFramesWritten\":%llu,"
            "\"displayWidth\":%d,\"displayHeight\":%d,\"displayHz\":%d}",
            surf_attached ? "true" : "false",
            surf_w, surf_h,
            static_cast<unsigned long long>(surf_frames),
            static_cast<long long>(surf_blit_us),
            audio_active ? "true" : "false",
            aaudio_ok ? "true" : "false",
            audio_rate,
            static_cast<unsigned long long>(audio_frames),
            disp.width_px, disp.height_px, disp.refresh_hz
    );
    return env->NewStringUTF(buf);
}

} // extern "C"
