#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <mutex>
#include <string>
#include <unordered_map>

#define LOG_TAG "KestrelVirtualHAL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

std::mutex g_hal_mutex;
std::unordered_map<std::string, VirtualDisplayProfile> g_displays;
std::unordered_map<std::string, VirtualSensorFrame> g_sensors;

} // namespace kestrel::hal

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_engine_NativeEngineBridge_nativeGetHalVersion(
        JNIEnv* env,
        jclass /* cls */) {
    return env->NewStringUTF("kestrel_hal-cpp17/0.1.0 (hwcomposer + gralloc + sensors)");
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
    const char* raw_id = env->GetStringUTFChars(vm_id, nullptr);
    if (!raw_id) return;
    std::string id(raw_id);
    env->ReleaseStringUTFChars(vm_id, raw_id);

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
    const char* raw_id = env->GetStringUTFChars(vm_id, nullptr);
    if (!raw_id) return;
    std::string id(raw_id);
    env->ReleaseStringUTFChars(vm_id, raw_id);

    std::lock_guard<std::mutex> lock(kestrel::hal::g_hal_mutex);
    kestrel::hal::g_sensors[id] = {ax, ay, az, gx, gy, gz};
}

} // extern "C"
