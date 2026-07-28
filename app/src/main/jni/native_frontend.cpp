/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Minimal Android/libretro frontend for the Ultima Controller shell.
 * DOSBox Pure is built unmodified from third_party/dosbox-pure.
 */

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include "libretro.h"

namespace {

constexpr const char* kLogTag = "UltimaNative";
constexpr float kFallbackAspectRatio = 4.0f / 3.0f;
constexpr double kFallbackFramesPerSecond = 60.0;

JavaVM* javaVm = nullptr;
jobject callbackTarget = nullptr;
jmethodID statusMethod = nullptr;
jmethodID audioConfiguredMethod = nullptr;
jmethodID audioSamplesMethod = nullptr;

std::mutex lifecycleMutex;
std::thread emulationThread;
std::atomic<bool> running{false};
std::atomic<bool> stopRequested{false};
std::atomic<bool> paused{false};
std::atomic<bool> coreRequestedShutdown{false};
std::condition_variable pauseCondition;
std::mutex pauseMutex;

std::mutex surfaceMutex;
ANativeWindow* nativeWindow = nullptr;
std::atomic<float> displayAspectRatio{kFallbackAspectRatio};
std::atomic<double> framesPerSecond{kFallbackFramesPerSecond};

std::string contentPath;
std::string systemDirectory;
std::string saveDirectory;

std::atomic<bool> contentLoaded{false};
std::atomic<bool> videoPresented{false};
std::atomic<bool> extendedVideoWaitReported{false};
std::atomic<unsigned> videoCallbackCount{0};
std::atomic<unsigned> videoLockFailureCount{0};
std::atomic<unsigned> emulationRunCount{0};
std::atomic<int> surfaceWidth{0};
std::atomic<int> surfaceHeight{0};
std::atomic<unsigned> lastSourceWidth{0};
std::atomic<unsigned> lastSourceHeight{0};
std::atomic<int> lastDestinationWidth{0};
std::atomic<int> lastDestinationHeight{0};

std::atomic<retro_keyboard_event_t> keyboardEvent{nullptr};
std::atomic<int16_t> joypadState[16];

int androidLogPriority(enum retro_log_level level) {
    switch (level) {
        case RETRO_LOG_DEBUG:
            return ANDROID_LOG_DEBUG;
        case RETRO_LOG_WARN:
            return ANDROID_LOG_WARN;
        case RETRO_LOG_ERROR:
            return ANDROID_LOG_ERROR;
        case RETRO_LOG_INFO:
        default:
            return ANDROID_LOG_INFO;
    }
}

void RETRO_CALLCONV frontendLog(enum retro_log_level level, const char* format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(androidLogPriority(level), kLogTag, format, args);
    va_end(args);
}

JNIEnv* currentEnvironment(bool* attachedHere) {
    *attachedHere = false;
    if (javaVm == nullptr) {
        return nullptr;
    }

    JNIEnv* environment = nullptr;
    const jint result = javaVm->GetEnv(
        reinterpret_cast<void**>(&environment),
        JNI_VERSION_1_6
    );
    if (result == JNI_OK) {
        return environment;
    }
    if (result != JNI_EDETACHED ||
        javaVm->AttachCurrentThread(&environment, nullptr) != JNI_OK) {
        return nullptr;
    }
    *attachedHere = true;
    return environment;
}

void clearJavaException(JNIEnv* environment) {
    if (environment != nullptr && environment->ExceptionCheck()) {
        environment->ExceptionDescribe();
        environment->ExceptionClear();
    }
}

void notifyStatus(const std::string& status) {
    if (callbackTarget == nullptr || statusMethod == nullptr) {
        return;
    }

    bool attachedHere = false;
    JNIEnv* environment = currentEnvironment(&attachedHere);
    if (environment == nullptr) {
        return;
    }

    jstring value = environment->NewStringUTF(status.c_str());
    if (value != nullptr) {
        environment->CallVoidMethod(callbackTarget, statusMethod, value);
        environment->DeleteLocalRef(value);
    }
    clearJavaException(environment);
    if (attachedHere) {
        javaVm->DetachCurrentThread();
    }
}

std::string videoWaitStatus() {
    return std::string("DOSBox Pure loaded ULTIMA.EXE; waiting for the first ") +
        "posted video frame (surface " +
        std::to_string(surfaceWidth.load()) + "x" +
        std::to_string(surfaceHeight.load()) + ", callbacks " +
        std::to_string(videoCallbackCount.load()) + ", lock failures " +
        std::to_string(videoLockFailureCount.load()) + ")";
}

void notifyVideoRunning() {
    notifyStatus(
        std::string("running:") +
        std::to_string(lastSourceWidth.load()) + "x" +
        std::to_string(lastSourceHeight.load()) + " to " +
        std::to_string(lastDestinationWidth.load()) + "x" +
        std::to_string(lastDestinationHeight.load())
    );
}

void configureAudio(unsigned sampleRate) {
    if (callbackTarget == nullptr || audioConfiguredMethod == nullptr) {
        return;
    }

    bool attachedHere = false;
    JNIEnv* environment = currentEnvironment(&attachedHere);
    if (environment == nullptr) {
        return;
    }
    environment->CallVoidMethod(
        callbackTarget,
        audioConfiguredMethod,
        static_cast<jint>(sampleRate)
    );
    clearJavaException(environment);
    if (attachedHere) {
        javaVm->DetachCurrentThread();
    }
}

void updateGeometry(const retro_game_geometry& geometry) {
    const float aspect = geometry.aspect_ratio > 0.0f
        ? geometry.aspect_ratio
        : (
            geometry.base_height > 0
                ? static_cast<float>(geometry.base_width) /
                    static_cast<float>(geometry.base_height)
                : kFallbackAspectRatio
        );
    displayAspectRatio.store(aspect);
}

bool RETRO_CALLCONV environmentCallback(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = systemDirectory.c_str();
            return true;

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = saveDirectory.c_str();
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return data != nullptr &&
                *static_cast<retro_pixel_format*>(data) ==
                    RETRO_PIXEL_FORMAT_XRGB8888;

        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: {
            const auto* callback =
                static_cast<const retro_keyboard_callback*>(data);
            keyboardEvent.store(callback != nullptr ? callback->callback : nullptr);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* variable = static_cast<retro_variable*>(data);
            if (variable == nullptr || variable->key == nullptr) {
                return false;
            }
            if (std::strcmp(variable->key, "dosbox_pure_voodoo_perf") == 0) {
                variable->value = "0";
                return true;
            }
            if (std::strcmp(variable->key, "dosbox_pure_aspect_correction") == 0) {
                variable->value = "true";
                return true;
            }
            if (std::strcmp(variable->key, "dosbox_pure_audiorate") == 0) {
                variable->value = "48000";
                return true;
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = frontendLog;
            return true;

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 2;
            return true;

        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_NONE;
            return true;

        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            *static_cast<unsigned*>(data) = 1;
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const auto* message = static_cast<const retro_message_ext*>(data);
            if (message != nullptr && message->msg != nullptr) {
                __android_log_print(
                    androidLogPriority(message->level),
                    kLogTag,
                    "%s",
                    message->msg
                );
                if (message->level == RETRO_LOG_ERROR) {
                    notifyStatus(std::string("error:") + message->msg);
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            updateGeometry(*static_cast<retro_game_geometry*>(data));
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const auto* avInfo = static_cast<const retro_system_av_info*>(data);
            updateGeometry(avInfo->geometry);
            if (avInfo->timing.fps > 1.0) {
                framesPerSecond.store(avInfo->timing.fps);
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
            auto* throttle = static_cast<retro_throttle_state*>(data);
            throttle->mode = RETRO_THROTTLE_NONE;
            throttle->rate = static_cast<float>(framesPerSecond.load());
            return true;
        }

        case RETRO_ENVIRONMENT_SHUTDOWN:
            coreRequestedShutdown.store(true);
            pauseCondition.notify_all();
            return true;

        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return true;

        case RETRO_ENVIRONMENT_SET_HW_RENDER:
        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE:
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
        case RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE:
            return false;

        default:
            return false;
    }
}

void RETRO_CALLCONV videoCallback(
    const void* data,
    unsigned width,
    unsigned height,
    size_t pitch
) {
    if (data == nullptr || data == RETRO_HW_FRAME_BUFFER_VALID ||
        width == 0 || height == 0) {
        return;
    }

    videoCallbackCount.fetch_add(1);
    std::lock_guard<std::mutex> lock(surfaceMutex);
    if (nativeWindow == nullptr) {
        return;
    }

    ANativeWindow_Buffer windowBuffer;
    const int lockResult =
        ANativeWindow_lock(nativeWindow, &windowBuffer, nullptr);
    if (lockResult != 0) {
        videoLockFailureCount.fetch_add(1);
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "ANativeWindow_lock failed: %d",
            lockResult
        );
        return;
    }

    auto* destination = static_cast<uint32_t*>(windowBuffer.bits);
    const int destinationWidth = windowBuffer.width;
    const int destinationHeight = windowBuffer.height;
    const int destinationStride = windowBuffer.stride;

    for (int y = 0; y < destinationHeight; ++y) {
        std::fill_n(
            destination + y * destinationStride,
            destinationStride,
            0xff000000u
        );
    }

    float contentAspect = displayAspectRatio.load();
    if (!std::isfinite(contentAspect) || contentAspect <= 0.0f) {
        contentAspect = static_cast<float>(width) / static_cast<float>(height);
    }

    int renderWidth = destinationWidth;
    int renderHeight = static_cast<int>(
        std::lround(static_cast<double>(renderWidth) / contentAspect)
    );
    if (renderHeight > destinationHeight) {
        renderHeight = destinationHeight;
        renderWidth = static_cast<int>(
            std::lround(static_cast<double>(renderHeight) * contentAspect)
        );
    }
    renderWidth = std::max(1, std::min(renderWidth, destinationWidth));
    renderHeight = std::max(1, std::min(renderHeight, destinationHeight));

    const int offsetX = (destinationWidth - renderWidth) / 2;
    const int offsetY = (destinationHeight - renderHeight) / 2;
    const auto* sourceBytes = static_cast<const uint8_t*>(data);

    for (int y = 0; y < renderHeight; ++y) {
        const unsigned sourceY =
            static_cast<unsigned>(
                static_cast<uint64_t>(y) * height /
                static_cast<unsigned>(renderHeight)
            );
        const auto* sourceRow = reinterpret_cast<const uint32_t*>(
            sourceBytes + static_cast<size_t>(sourceY) * pitch
        );
        auto* destinationRow =
            destination + (offsetY + y) * destinationStride + offsetX;

        for (int x = 0; x < renderWidth; ++x) {
            const unsigned sourceX =
                static_cast<unsigned>(
                    static_cast<uint64_t>(x) * width /
                    static_cast<unsigned>(renderWidth)
                );
            const uint32_t xrgb = sourceRow[sourceX];
            const uint32_t red = (xrgb >> 16u) & 0xffu;
            const uint32_t green = (xrgb >> 8u) & 0xffu;
            const uint32_t blue = xrgb & 0xffu;
            destinationRow[x] =
                0xff000000u | (blue << 16u) | (green << 8u) | red;
        }
    }

    const int postResult = ANativeWindow_unlockAndPost(nativeWindow);
    if (postResult != 0) {
        videoLockFailureCount.fetch_add(1);
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "ANativeWindow_unlockAndPost failed: %d",
            postResult
        );
        return;
    }

    lastSourceWidth.store(width);
    lastSourceHeight.store(height);
    lastDestinationWidth.store(destinationWidth);
    lastDestinationHeight.store(destinationHeight);
    if (!videoPresented.exchange(true)) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "First video frame posted: %ux%u to %dx%d",
            width,
            height,
            destinationWidth,
            destinationHeight
        );
        if (contentLoaded.load()) {
            notifyVideoRunning();
        }
    }
}

size_t RETRO_CALLCONV audioCallback(const int16_t* samples, size_t frames) {
    if (samples == nullptr || frames == 0 ||
        callbackTarget == nullptr || audioSamplesMethod == nullptr) {
        return frames;
    }

    bool attachedHere = false;
    JNIEnv* environment = currentEnvironment(&attachedHere);
    if (environment == nullptr) {
        return frames;
    }

    const size_t byteCount = frames * 2u * sizeof(int16_t);
    jobject buffer = environment->NewDirectByteBuffer(
        const_cast<int16_t*>(samples),
        static_cast<jlong>(byteCount)
    );
    if (buffer != nullptr) {
        environment->CallVoidMethod(
            callbackTarget,
            audioSamplesMethod,
            buffer,
            static_cast<jint>(byteCount)
        );
        environment->DeleteLocalRef(buffer);
    }
    clearJavaException(environment);
    if (attachedHere) {
        javaVm->DetachCurrentThread();
    }
    return frames;
}

void RETRO_CALLCONV inputPollCallback() {
}

int16_t RETRO_CALLCONV inputStateCallback(
    unsigned port,
    unsigned device,
    unsigned,
    unsigned id
) {
    if (port != 0 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD ||
        id >= 16) {
        return 0;
    }
    return joypadState[id].load();
}

void releaseCallbackTarget(JNIEnv* environment) {
    if (callbackTarget != nullptr) {
        environment->DeleteGlobalRef(callbackTarget);
        callbackTarget = nullptr;
    }
    statusMethod = nullptr;
    audioConfiguredMethod = nullptr;
    audioSamplesMethod = nullptr;
}

void runEmulator() {
    bool attachedHere = false;
    JNIEnv* environment = currentEnvironment(&attachedHere);
    bool coreInitialized = false;
    bool gameLoaded = false;

    try {
        retro_set_environment(environmentCallback);
        retro_set_video_refresh(videoCallback);
        retro_set_audio_sample_batch(audioCallback);
        retro_set_audio_sample(nullptr);
        retro_set_input_poll(inputPollCallback);
        retro_set_input_state(inputStateCallback);

        retro_init();
        coreInitialized = true;

        retro_game_info gameInfo{};
        gameInfo.path = contentPath.c_str();
        if (!retro_load_game(&gameInfo)) {
            notifyStatus("error:DOSBox Pure rejected the imported ULTIMA.EXE");
        } else {
            gameLoaded = true;
            retro_system_av_info avInfo{};
            retro_get_system_av_info(&avInfo);
            updateGeometry(avInfo.geometry);
            if (avInfo.timing.fps > 1.0) {
                framesPerSecond.store(avInfo.timing.fps);
            }
            configureAudio(
                avInfo.timing.sample_rate > 1.0
                    ? static_cast<unsigned>(std::lround(avInfo.timing.sample_rate))
                    : 48000u
            );
            contentLoaded.store(true);
            if (videoPresented.load()) {
                notifyVideoRunning();
            } else {
                notifyStatus(std::string("video_wait:") + videoWaitStatus());
            }

            auto nextFrame = std::chrono::steady_clock::now();
            while (!stopRequested.load() && !coreRequestedShutdown.load()) {
                if (paused.load()) {
                    std::unique_lock<std::mutex> lock(pauseMutex);
                    pauseCondition.wait(
                        lock,
                        [] {
                            return !paused.load() ||
                                stopRequested.load() ||
                                coreRequestedShutdown.load();
                        }
                    );
                    nextFrame = std::chrono::steady_clock::now();
                    continue;
                }

                retro_run();
                const unsigned runNumber = emulationRunCount.fetch_add(1) + 1;
                if (runNumber >= 180 &&
                    !videoPresented.load() &&
                    !extendedVideoWaitReported.exchange(true)) {
                    notifyStatus(std::string("video_wait:") + videoWaitStatus());
                }

                const double fps = std::max(10.0, framesPerSecond.load());
                const auto frameDuration = std::chrono::duration<double>(1.0 / fps);
                nextFrame += std::chrono::duration_cast<
                    std::chrono::steady_clock::duration
                >(frameDuration);
                const auto now = std::chrono::steady_clock::now();
                if (nextFrame < now - std::chrono::milliseconds(250)) {
                    nextFrame = now;
                } else if (nextFrame > now) {
                    std::this_thread::sleep_until(nextFrame);
                }
            }
        }
    } catch (const std::exception& error) {
        notifyStatus(std::string("error:Native emulator failure: ") + error.what());
    } catch (...) {
        notifyStatus("error:Unknown native emulator failure");
    }

    keyboardEvent.store(nullptr);
    if (gameLoaded) {
        retro_unload_game();
    }
    if (coreInitialized) {
        retro_deinit();
    }
    contentLoaded.store(false);
    running.store(false);
    if (!coreRequestedShutdown.load() && stopRequested.load()) {
        notifyStatus("stopped");
    } else if (coreRequestedShutdown.load()) {
        notifyStatus("stopped");
    }

    if (environment != nullptr) {
        releaseCallbackTarget(environment);
    }
    if (attachedHere) {
        javaVm->DetachCurrentThread();
    }
}

std::string javaString(JNIEnv* environment, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* utf8 = environment->GetStringUTFChars(value, nullptr);
    if (utf8 == nullptr) {
        return {};
    }
    std::string result(utf8);
    environment->ReleaseStringUTFChars(value, utf8);
    return result;
}

void stopEmulator() {
    std::thread threadToJoin;
    {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        stopRequested.store(true);
        paused.store(false);
        pauseCondition.notify_all();
        if (emulationThread.joinable()) {
            threadToJoin = std::move(emulationThread);
        }
    }
    if (threadToJoin.joinable()) {
        threadToJoin.join();
    }
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    javaVm = vm;
    for (auto& state : joypadState) {
        state.store(0);
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jakebrierley_ultimacontroller_NativeEmulator_nativeStart(
    JNIEnv* environment,
    jobject instance,
    jstring content,
    jstring system,
    jstring saves
) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (running.load() || emulationThread.joinable()) {
        return JNI_FALSE;
    }

    contentPath = javaString(environment, content);
    systemDirectory = javaString(environment, system);
    saveDirectory = javaString(environment, saves);
    if (contentPath.empty() || systemDirectory.empty() || saveDirectory.empty()) {
        return JNI_FALSE;
    }

    jclass callbackClass = environment->GetObjectClass(instance);
    if (callbackClass == nullptr) {
        return JNI_FALSE;
    }
    statusMethod = environment->GetMethodID(
        callbackClass,
        "onNativeStatus",
        "(Ljava/lang/String;)V"
    );
    audioConfiguredMethod = environment->GetMethodID(
        callbackClass,
        "onNativeAudioConfigured",
        "(I)V"
    );
    audioSamplesMethod = environment->GetMethodID(
        callbackClass,
        "onNativeAudioSamples",
        "(Ljava/nio/ByteBuffer;I)V"
    );
    environment->DeleteLocalRef(callbackClass);
    if (statusMethod == nullptr ||
        audioConfiguredMethod == nullptr ||
        audioSamplesMethod == nullptr) {
        clearJavaException(environment);
        return JNI_FALSE;
    }

    callbackTarget = environment->NewGlobalRef(instance);
    if (callbackTarget == nullptr) {
        return JNI_FALSE;
    }

    stopRequested.store(false);
    paused.store(false);
    coreRequestedShutdown.store(false);
    contentLoaded.store(false);
    videoPresented.store(false);
    extendedVideoWaitReported.store(false);
    videoCallbackCount.store(0);
    videoLockFailureCount.store(0);
    emulationRunCount.store(0);
    lastSourceWidth.store(0);
    lastSourceHeight.store(0);
    lastDestinationWidth.store(0);
    lastDestinationHeight.store(0);
    displayAspectRatio.store(kFallbackAspectRatio);
    framesPerSecond.store(kFallbackFramesPerSecond);
    for (auto& state : joypadState) {
        state.store(0);
    }
    running.store(true);
    emulationThread = std::thread(runEmulator);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jakebrierley_ultimacontroller_NativeEmulator_nativeSetSurface(
    JNIEnv* environment,
    jobject,
    jobject surface
) {
    std::lock_guard<std::mutex> lock(surfaceMutex);
    ANativeWindow* replacement = surface != nullptr
        ? ANativeWindow_fromSurface(environment, surface)
        : nullptr;
    if (nativeWindow != nullptr) {
        ANativeWindow_release(nativeWindow);
    }
    nativeWindow = replacement;
    if (nativeWindow != nullptr) {
        ANativeWindow_setBuffersGeometry(
            nativeWindow,
            0,
            0,
            WINDOW_FORMAT_RGBA_8888
        );
        surfaceWidth.store(ANativeWindow_getWidth(nativeWindow));
        surfaceHeight.store(ANativeWindow_getHeight(nativeWindow));
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Video surface attached: %dx%d format=%d",
            surfaceWidth.load(),
            surfaceHeight.load(),
            ANativeWindow_getFormat(nativeWindow)
        );
    } else {
        surfaceWidth.store(0);
        surfaceHeight.store(0);
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Video surface detached"
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jakebrierley_ultimacontroller_NativeEmulator_nativeSetPaused(
    JNIEnv*,
    jobject,
    jboolean shouldPause
) {
    paused.store(shouldPause == JNI_TRUE);
    if (shouldPause != JNI_TRUE) {
        pauseCondition.notify_all();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jakebrierley_ultimacontroller_NativeEmulator_nativeStop(
    JNIEnv*,
    jobject
) {
    stopEmulator();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jakebrierley_ultimacontroller_NativeEmulator_nativeSendKey(
    JNIEnv*,
    jobject,
    jint keyCode,
    jboolean down
) {
    retro_keyboard_event_t callback = keyboardEvent.load();
    if (callback == nullptr || keyCode < 0 || keyCode >= RETROK_LAST) {
        return JNI_FALSE;
    }
    const uint32_t character =
        keyCode >= RETROK_SPACE && keyCode <= RETROK_z
            ? static_cast<uint32_t>(keyCode)
            : 0u;
    callback(down == JNI_TRUE, static_cast<unsigned>(keyCode), character, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jakebrierley_ultimacontroller_NativeEmulator_nativeSetJoypadButton(
    JNIEnv*,
    jobject,
    jint button,
    jboolean down
) {
    if (button >= 0 && button < 16) {
        joypadState[button].store(down == JNI_TRUE ? 1 : 0);
    }
}
