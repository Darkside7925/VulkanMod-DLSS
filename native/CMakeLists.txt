cmake_minimum_required(VERSION 3.20)
cmake_policy(SET CMP0091 NEW)  # MSVC_RUNTIME_LIBRARY abstraction
project(mcdlss_native LANGUAGES CXX)

# Static MSVC runtime so the DLL is self-contained (no vcruntime140 dependency at load time).
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# mcdlss_native — JNI glue that owns the NVIDIA Streamline lifecycle on top of VulkanMod's Vulkan.
#
# Phase 0 builds a dependency-free DLL (JNI hello-world only).
# Phase 1 turns on MCDLSS_WITH_STREAMLINE to compile sl_manager.cpp and link sl.interposer.lib.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# ---- JNI headers (from the portable JDK via JAVA_HOME) ----
if (DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME_DIR "$ENV{JAVA_HOME}")
else()
    message(FATAL_ERROR "JAVA_HOME is not set. Run `. tools/env.ps1` first.")
endif()
if (NOT EXISTS "${JAVA_HOME_DIR}/include/jni.h")
    message(FATAL_ERROR "jni.h not found under JAVA_HOME=${JAVA_HOME_DIR}")
endif()

# ---- Streamline SDK (prebuilt, with signed redistributables) ----
set(STREAMLINE_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../native_streamline_sdk"
    CACHE PATH "Path to extracted streamline-sdk-v2.12.0")
option(MCDLSS_WITH_STREAMLINE "Compile + link the Streamline integration (Phase 1+)" OFF)

set(MCDLSS_SOURCES
    src/jni_bridge.cpp
)

if (MCDLSS_WITH_STREAMLINE)
    list(APPEND MCDLSS_SOURCES
        src/sl_manager.cpp
        src/sl_dlss_sr.cpp
        # src/sl_dlss_g.cpp  src/sl_reflex.cpp  src/tagging.cpp  (added per phase)
    )
endif()

add_library(mcdlss_native SHARED ${MCDLSS_SOURCES})

target_include_directories(mcdlss_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    "${JAVA_HOME_DIR}/include"
    "${JAVA_HOME_DIR}/include/win32"
)

if (MCDLSS_WITH_STREAMLINE)
    if (NOT EXISTS "${STREAMLINE_SDK_DIR}/include/sl.h")
        message(FATAL_ERROR "Streamline SDK not found at ${STREAMLINE_SDK_DIR} (expected include/sl.h). "
                            "Download streamline-sdk-v2.12.0.zip and extract there.")
    endif()
    target_include_directories(mcdlss_native PRIVATE "${STREAMLINE_SDK_DIR}/include")
    target_link_libraries(mcdlss_native PRIVATE "${STREAMLINE_SDK_DIR}/lib/x64/sl.interposer.lib")
    target_compile_definitions(mcdlss_native PRIVATE MCDLSS_WITH_STREAMLINE=1)
endif()

# Keep the output name stable: mcdlss_native.dll (no "lib" prefix on any platform).
set_target_properties(mcdlss_native PROPERTIES PREFIX "")

# MSVC: be strict but not pedantic about JNI's C-linkage exports.
if (MSVC)
    target_compile_options(mcdlss_native PRIVATE /W3 /EHsc)
endif()
