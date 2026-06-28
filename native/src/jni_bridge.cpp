// jni_bridge.cpp — JNI entrypoints for net.vulkanmod.dlss.NativeBridge.
//
// Phase 0: prove the native library loads inside Minecraft and a JNI round-trip works,
// with NO Streamline dependency yet. Streamline lifecycle (slInit, feature checks, tagging,
// evaluate) is added in sl_manager.cpp in Phase 1 and surfaced through additional natives.

#include <jni.h>
#include <string>
#include "mcdlss.h"

extern "C" {

// jstring net.vulkanmod.dlss.NativeBridge.hello(String from)
JNIEXPORT jstring JNICALL
Java_net_vulkanmod_dlss_NativeBridge_hello(JNIEnv* env, jclass /*clazz*/, jstring from) {
    const char* fromC = from ? env->GetStringUTFChars(from, nullptr) : "";
    std::string msg = std::string(MCDLSS_VERSION_STRING) + " — JNI round-trip OK, hello from '" +
                      (fromC ? fromC : "") + "'";
    if (from) env->ReleaseStringUTFChars(from, fromC);
    return env->NewStringUTF(msg.c_str());
}

// int net.vulkanmod.dlss.NativeBridge.abiVersion()
JNIEXPORT jint JNICALL
Java_net_vulkanmod_dlss_NativeBridge_abiVersion(JNIEnv* /*env*/, jclass /*clazz*/) {
    return (jint)MCDLSS_ABI_VERSION;
}

} // extern "C"
