// sl_manager.cpp — NVIDIA Streamline lifecycle + feature queries (Phase 1).
//
// Compiled only when MCDLSS_WITH_STREAMLINE=ON. Links sl.interposer.lib; the SL core API
// (slInit / slIsFeatureSupported / slGetFeatureRequirements / slShutdown) is implemented in
// sl.interposer.dll, which must be loadable at process start (we preload it from Java).
//
// Phase 1 scope: initialize Streamline against VulkanMod's Vulkan and report which features
// (DLSS, DLSS-G, Reflex, PCL) the current GPU/driver/OS supports. No tagging/evaluate yet.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <jni.h>
#include <string>
#include <cstdio>

#include "mcdlss.h"

#include "sl.h"
#include "sl_consts.h"
#include "sl_core_api.h"

static bool g_slInitialized = false;

static const char* resultName(sl::Result r) {
    switch (r) {
        case sl::Result::eOk:                       return "eOk";
        case sl::Result::eErrorIO:                  return "eErrorIO";
        case sl::Result::eErrorDriverOutOfDate:     return "eErrorDriverOutOfDate";
        case sl::Result::eErrorOSOutOfDate:         return "eErrorOSOutOfDate";
        case sl::Result::eErrorOSDisabledHWS:       return "eErrorOSDisabledHWS";
        case sl::Result::eErrorDeviceNotCreated:    return "eErrorDeviceNotCreated";
        case sl::Result::eErrorNoSupportedAdapterFound: return "eErrorNoSupportedAdapterFound";
        case sl::Result::eErrorAdapterNotSupported: return "eErrorAdapterNotSupported";
        case sl::Result::eErrorNoPlugins:           return "eErrorNoPlugins";
        case sl::Result::eErrorVulkanAPI:           return "eErrorVulkanAPI";
        case sl::Result::eErrorNGXFailed:           return "eErrorNGXFailed";
        case sl::Result::eErrorNotInitialized:      return "eErrorNotInitialized";
        case sl::Result::eErrorInitNotCalled:       return "eErrorInitNotCalled";
        case sl::Result::eErrorInvalidParameter:    return "eErrorInvalidParameter";
        case sl::Result::eErrorFeatureMissing:      return "eErrorFeatureMissing";
        case sl::Result::eErrorFeatureNotSupported: return "eErrorFeatureNotSupported";
        case sl::Result::eErrorFeatureFailedToLoad: return "eErrorFeatureFailedToLoad";
        case sl::Result::eWarnOutOfVRAM:            return "eWarnOutOfVRAM";
        default:                                    return "eError(unmapped)";
    }
}

// Streamline's own diagnostics → process stderr (captured in the MC dev console).
static void slLogCallback(sl::LogType type, const char* msg) {
    const char* p = (type == sl::LogType::eError) ? "[SL/ERROR]"
                  : (type == sl::LogType::eWarn)  ? "[SL/WARN] "
                  : "[SL/INFO] ";
    std::fprintf(stderr, "%s %s", p, msg ? msg : "");
}

static std::wstring jstringToWide(JNIEnv* env, jstring s) {
    if (!s) return L"";
    const jchar* jc = env->GetStringChars(s, nullptr);   // UTF-16
    jsize len = env->GetStringLength(s);
    std::wstring w(reinterpret_cast<const wchar_t*>(jc), static_cast<size_t>(len));
    env->ReleaseStringChars(s, jc);
    return w;
}

extern "C" {

// int NativeBridge.slInitNative(String pluginDir, int logLevel)
JNIEXPORT jint JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slInitNative(JNIEnv* env, jclass, jstring jPluginDir, jint logLevel) {
    static std::wstring s_pluginDir;            // must outlive slInit's internal copy step
    s_pluginDir = jstringToWide(env, jPluginDir);
    static const wchar_t* s_paths[1];
    s_paths[0] = s_pluginDir.c_str();

    static const sl::Feature s_features[] = {
        sl::kFeatureDLSS, sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL
    };

    sl::Preferences pref{};
    pref.showConsole = false;
    pref.logLevel = (logLevel >= 0 && logLevel < (int)sl::LogLevel::eCount)
                        ? (sl::LogLevel)logLevel : sl::LogLevel::eDefault;
    pref.logMessageCallback = &slLogCallback;
    if (!s_pluginDir.empty()) {
        pref.pathsToPlugins = s_paths;
        pref.numPathsToPlugins = 1;
    }
    pref.featuresToLoad = s_features;
    pref.numFeaturesToLoad = (uint32_t)(sizeof(s_features) / sizeof(s_features[0]));
    pref.engine = sl::EngineType::eCustom;
    pref.engineVersion = "1.0";
    pref.projectId = "mc-dlss-vulkanmod";
    pref.renderAPI = sl::RenderAPI::eVulkan;
    // Manual hooking: we provide the Vulkan device via slSetVulkanInfo rather than letting
    // SL proxy vkCreateDevice (LWJGL loads the real Vulkan loader).
    pref.flags = sl::PreferenceFlags::eDisableCLStateTracking | sl::PreferenceFlags::eAllowOTA
               | sl::PreferenceFlags::eLoadDownloadedPlugins | sl::PreferenceFlags::eUseManualHooking;

    sl::Result r = slInit(pref, sl::kSDKVersion);
    g_slInitialized = (r == sl::Result::eOk);
    return (jint)r;
}

// int NativeBridge.slIsFeatureSupportedNative(int feature, long vkPhysicalDevice)
JNIEXPORT jint JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slIsFeatureSupportedNative(JNIEnv*, jclass, jint feature, jlong vkPhysicalDevice) {
    sl::AdapterInfo ai{};
    if (vkPhysicalDevice != 0) {
        ai.vkPhysicalDevice = reinterpret_cast<void*>(vkPhysicalDevice);
    }
    sl::Result r = slIsFeatureSupported((sl::Feature)feature, ai);
    return (jint)r;
}

// String NativeBridge.slFeatureRequirementsNative(int feature)
JNIEXPORT jstring JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slFeatureRequirementsNative(JNIEnv* env, jclass, jint feature) {
    char buf[640];
    sl::FeatureRequirements req{};
    sl::Result r = slGetFeatureRequirements((sl::Feature)feature, req);
    if (r != sl::Result::eOk) {
        std::snprintf(buf, sizeof(buf), "requirements unavailable (%s)", resultName(r));
        return env->NewStringUTF(buf);
    }

    std::string flags;
    if (req.flags & sl::FeatureRequirementFlags::eVulkanSupported)         flags += "VK ";
    if (req.flags & sl::FeatureRequirementFlags::eD3D12Supported)          flags += "D3D12 ";
    if (req.flags & sl::FeatureRequirementFlags::eD3D11Supported)          flags += "D3D11 ";
    if (req.flags & sl::FeatureRequirementFlags::eVSyncOffRequired)        flags += "VSyncOff ";
    if (req.flags & sl::FeatureRequirementFlags::eHardwareSchedulingRequired) flags += "HAGS ";
    if (flags.empty()) flags = "-";

    std::snprintf(buf, sizeof(buf),
        "driver %u.%u.%u (need %u.%u.%u); os %u.%u.%u; reqFlags: %s; reqTags:%u; vkQ(gfx:%u cmp:%u of:%u)",
        req.driverVersionDetected.major, req.driverVersionDetected.minor, req.driverVersionDetected.build,
        req.driverVersionRequired.major, req.driverVersionRequired.minor, req.driverVersionRequired.build,
        req.osVersionDetected.major, req.osVersionDetected.minor, req.osVersionDetected.build,
        flags.c_str(), req.numRequiredTags,
        req.vkNumGraphicsQueuesRequired, req.vkNumComputeQueuesRequired, req.vkNumOpticalFlowQueuesRequired);
    return env->NewStringUTF(buf);
}

// String NativeBridge.slResultNameNative(int code)
JNIEXPORT jstring JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slResultNameNative(JNIEnv* env, jclass, jint code) {
    return env->NewStringUTF(resultName((sl::Result)code));
}

// int NativeBridge.slShutdownNative()
JNIEXPORT jint JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slShutdownNative(JNIEnv*, jclass) {
    if (!g_slInitialized) return (jint)sl::Result::eOk;
    sl::Result r = slShutdown();
    g_slInitialized = false;
    return (jint)r;
}

} // extern "C"
