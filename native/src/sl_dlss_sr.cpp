// sl_dlss_sr.cpp — DLSS Super Resolution: Vulkan device hand-off + optimal-settings query.
//
// To call the DLSS feature functions (slDLSS*), Streamline needs the Vulkan device set via
// slSetVulkanInfo (manual-hooking mode — we do NOT use SL's vkCreateDevice proxies because
// LWJGL loads the real Vulkan loader). We avoid pulling in vulkan.h by declaring a
// layout-compatible sl::VulkanInfo with void* handles (VkDevice/VkInstance/VkPhysicalDevice
// are opaque pointers) and the unmangled extern "C" slSetVulkanInfo symbol.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <jni.h>
#include <cstdio>

#include "mcdlss.h"
#include "sl.h"
#include "sl_dlss.h"

namespace sl {
// Layout-compatible replica of sl_helpers_vk.h's VulkanInfo (handles are pointers).
SL_STRUCT_BEGIN(VulkanInfo, StructType({ 0xeed6fd5, 0x82cd, 0x43a9, { 0xbd, 0xb5, 0x47, 0xa5, 0xba, 0x2f, 0x45, 0xd6 } }), kStructVersion3)
    void* device{};
    void* instance{};
    void* physicalDevice{};
    uint32_t computeQueueIndex{};
    uint32_t computeQueueFamily{};
    uint32_t graphicsQueueIndex{};
    uint32_t graphicsQueueFamily{};
    uint32_t opticalFlowQueueIndex{};
    uint32_t opticalFlowQueueFamily{};
    bool useNativeOpticalFlowMode = false;
    uint32_t computeQueueCreateFlags{};
    uint32_t graphicsQueueCreateFlags{};
    uint32_t opticalFlowQueueCreateFlags{};
SL_STRUCT_END()
}

// SL_API resolves to extern "C" for consumers, so this links to the real (unmangled) symbol.
extern "C" sl::Result slSetVulkanInfo(const sl::VulkanInfo& info);

extern "C" {

// int NativeBridge.slSetVulkanInfoNative(instance, physicalDevice, device, gfxFamily, gfxIndex, cmpFamily, cmpIndex)
JNIEXPORT jint JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slSetVulkanInfoNative(JNIEnv*, jclass,
        jlong instance, jlong physicalDevice, jlong device,
        jint gfxFamily, jint gfxIndex, jint cmpFamily, jint cmpIndex) {
    sl::VulkanInfo info{};
    info.instance = reinterpret_cast<void*>(instance);
    info.physicalDevice = reinterpret_cast<void*>(physicalDevice);
    info.device = reinterpret_cast<void*>(device);
    info.graphicsQueueFamily = (uint32_t)gfxFamily;
    info.graphicsQueueIndex = (uint32_t)gfxIndex;
    info.computeQueueFamily = (uint32_t)cmpFamily;
    info.computeQueueIndex = (uint32_t)cmpIndex;
    sl::Result r = slSetVulkanInfo(info);
    return (jint)r;
}

// String NativeBridge.slDlssOptimalSettingsNative(outputWidth, outputHeight, mode)
JNIEXPORT jstring JNICALL
Java_net_vulkanmod_dlss_NativeBridge_slDlssOptimalSettingsNative(JNIEnv* env, jclass,
        jint outputWidth, jint outputHeight, jint mode) {
    sl::DLSSOptions options{};
    options.mode = (sl::DLSSMode)mode;
    options.outputWidth = (uint32_t)outputWidth;
    options.outputHeight = (uint32_t)outputHeight;

    sl::DLSSOptimalSettings settings{};
    sl::Result r = slDLSSGetOptimalSettings(options, settings);

    char buf[256];
    if (r != sl::Result::eOk) {
        std::snprintf(buf, sizeof(buf), "query failed: result=%d", (int)r);
    } else {
        std::snprintf(buf, sizeof(buf),
            "render %ux%u (min %ux%u, max %ux%u) sharpness=%.3f",
            settings.optimalRenderWidth, settings.optimalRenderHeight,
            settings.renderWidthMin, settings.renderHeightMin,
            settings.renderWidthMax, settings.renderHeightMax,
            settings.optimalSharpness);
    }
    return env->NewStringUTF(buf);
}

} // extern "C"
