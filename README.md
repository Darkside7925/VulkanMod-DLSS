# VulkanMod-DLSS

NVIDIA DLSS 4 for Minecraft Java Edition 1.21.11 - DLSS Super Resolution and Frame Generation / Multi-Frame Generation, built on top of a Vulkan renderer.

Vanilla Minecraft is OpenGL, and DLSS cannot touch OpenGL - it needs Vulkan or D3D12. So this isn't a normal Fabric mod: it's a fork of [VulkanMod](https://github.com/xCollateral/VulkanMod) (which already replaces Minecraft's GL renderer with a real Vulkan one) wired straight into the NVIDIA Streamline SDK through a JNI-bound native layer. Java → JNI → C++ → Streamline (interposer + sl.dlss / sl.dlss_g / sl.reflex) → NGX, with VulkanMod's Vulkan device/swapchain/present path underneath. The end goal is the full DLSS 4 stack - render the world at a low internal resolution and AI-upscale it, then AI-interpolate 1×→2×/3×/4× frames - exposed from the in-game video settings on an RTX card.

> **What actually works today: the foundation.** A VulkanMod 1.21.11 fork that boots Minecraft on the Vulkan renderer, plus a self-contained native glue DLL (`mcdlss_native.dll`, MSVC, static CRT) that loads inside the running game and round-trips across the JNI boundary before the title screen, with an ABI handshake and graceful fallback to plain VulkanMod if the native side is missing. The entire proprietary supply chain is in place and signature-verified: Streamline 2.12.0 (`sl.interposer`, `sl.dlss`, `sl.dlss_g`, `sl.reflex`, all Authenticode `CN=NVIDIA Corporation`) and the NGX models (`nvngx_dlss` 310.7 SR, `nvngx_dlssg` FG, `nvngx_dlssd` RR). The Java↔C++ spine the whole project hangs off is proven end to end.

> **Honest status: DLSS does not upscale or generate frames yet.** This is early. What's done and verified is Phase 0 - the renderer base and the native bridge. Streamline init, motion vectors, jitter, the DLSS-SR evaluate path, Reflex markers, and Frame Generation are the next phases and are **not** working in this build. None of the "FPS goes up" payoff exists yet; what exists is the hard plumbing that everything else needs to stand on, built and tested one verifiable step at a time. Treat this as an ambitious, in-progress systems project, not a finished mod.

---

## Quick Start

Windows + an RTX GPU. The toolchain is portable (no admin needed except the one-time MSVC install).

```powershell
# put the portable JDK 21 + CMake on PATH, set JAVA_HOME
. .\tools\env.ps1

# build the native glue (mcdlss_native.dll) and stage it into the dev run dir
.\tools\build_native.ps1

# build + launch the dev client (VulkanMod renderer + native bridge)
.\tools\run_dev.ps1
```

On launch you'll see the bridge come up in the log, before the title screen:

```
[Render thread/INFO] (VulkanMod-DLSS) Loaded native library: ...\run\mcdlss\mcdlss_native.dll
[Render thread/INFO] (VulkanMod-DLSS) mcdlss_native 0.1.0 (Phase 0) — JNI round-trip OK, hello from 'VulkanMod 0.6.7-dev'
[Render thread/INFO] (VulkanMod-DLSS) Native ABI version: 1 (expected 1)
```

If the native DLL or the Streamline binaries aren't present, the game just runs as plain VulkanMod - DLSS is a strict opt-in, never a hard dependency.

> **You have to supply the NVIDIA binaries yourself.** `sl.dlss_g` and the `nvngx_*` models are proprietary, signed, and not recompilable - they are **not** in this repo. Download `streamline-sdk-v2.12.0.zip` from the [NVIDIA-RTX/Streamline](https://github.com/NVIDIA-RTX/Streamline) releases and extract it to `native_streamline_sdk/`; the native build links `sl.interposer.lib` from there and the run scripts stage the signed DLLs next to `mcdlss_native.dll`.

---

## Current Snapshot

- **Target:** Minecraft Java 1.21.11 (the last `1.x` before the `26.x` renumber), Fabric, JDK 21
- **Renderer base:** fork of VulkanMod `dev` (0.6.7-dev), Vulkan 1.2+ - verified booting MC 1.21.11 to the title screen on the Vulkan path, no GL fallback
- **Native layer:** `native/` CMake project → `mcdlss_native.dll`, MSVC 19.44, **static CRT** so the DLL is self-contained at load time. VS 2022 generator auto-locates the toolchain - no vcvars dance
- **JNI bridge:** `net.vulkanmod.dlss.NativeBridge` ↔ `native/src/jni_bridge.cpp`, with a versioned ABI handshake and best-effort load (any failure → plain VulkanMod)
- **Integration model:** DLSS lives **inside** the fork as an isolated `net.vulkanmod.dlss` package + sibling `native/`, not a separate mixin mod - DLSS needs white-box access to the device/swapchain/present internals, and the LGPL fork is published anyway
- **SDK:** NVIDIA Streamline **2.12.0** headers + programming guides; signed redistributables + NGX SR/FG/RR models present and Authenticode-valid (kept out of version control)
- **Verified test box:** RTX 5070 Ti Laptop (Blackwell - full Multi-Frame-Gen class), driver with `nvngx_dlssg` 310.x, Vulkan loader 1.4, HAGS on

---

## How it fits together

```
Minecraft 1.21.11 (Java) + Fabric
  └─ this fork: render hooks, jitter, motion-vector pass, config UI        [phases 2-6]
VulkanMod fork (Java) — Vulkan renderer, swapchain, present
  └─ net.vulkanmod.dlss.NativeBridge — hands Vk handles to the native layer  [done: bridge]
mcdlss_native.dll (C++/JNI)
  └─ slInit, feature checks, resource tagging, constants, evaluate          [phase 1+]
NVIDIA Streamline (sl.interposer proxies vkCreateDevice / vkQueuePresentKHR)
  → sl.dlss (SR), sl.dlss_g (Frame Gen), sl.reflex  →  NGX (nvngx_dlss*) inference
```

The whole trick is that Streamline's interposer has to be the Vulkan library the loader uses *before* the device is created, so the device VulkanMod makes is already proxied. The plan (Phase 1) is to point `org.lwjgl.system.Configuration.VULKAN_LIBRARY_NAME` at the staged `sl.interposer.dll` early in `Initializer`, ahead of `Vulkan.initVulkan` → `createInstance` → `DeviceManager.init`.

---

## Roadmap

One phase at a time; the game must build and launch after each one. Honest status:

- [x] **Phase 0 — Renderer base + native bridge.** VulkanMod 1.21.11 fork builds and runs; `mcdlss_native.dll` loads in-game; JNI round-trip + ABI check verified live. *Done.*
- [ ] **Phase 1 — Streamline init.** `slInit` against VulkanMod's Vulkan via the interposer; feature-support report for DLSS / DLSS-G / Reflex.
- [ ] **Phase 2 — Depth + motion vectors + jitter.** The temporal inputs DLSS lives on. Camera-only MVs first, then per-entity; debug visualizations early. This is the make-or-break part.
- [ ] **Phase 3 — DLSS Super Resolution.** Low-res world pass, tag + evaluate, composite, HUD on top, quality presets incl. DLAA.
- [ ] **Phase 4 — Reflex.** Mandatory prerequisite for Frame Gen; markers mapped onto the tick/render/present loop.
- [ ] **Phase 5 — Frame Generation / MFG.** HUD-less compositing, DLSS-G present interception, 2×/3×/4× on Ada/Blackwell, swapchain ownership on toggle.
- [ ] **Phase 6 — In-game config UI.** DLSS/SR preset/FG mode/Reflex/sharpness, gated by detected hardware.
- [ ] **Phase 7 — Robustness + packaging.** Graceful fallback, resize/alt-tab/dimension changes, clean shutdown, diagnostics.

---

## Requirements

- **OS:** Windows 10/11, Hardware-Accelerated GPU Scheduling **on** (Frame Gen won't initialize without it)
- **GPU:** DLSS Super Resolution needs Turing (RTX 20) or newer; Frame Generation needs Ada (RTX 40); Multi-Frame Gen (3×/4×) needs Blackwell (RTX 50). Vulkan 1.2+
- **JDK:** 21 (1.21.11 requires Java 21+)
- **Native toolchain:** CMake + MSVC (Build Tools 2022, VC++ workload). `tools/env.ps1` finds a portable JDK/CMake automatically
- Non-NVIDIA / older GPUs and missing-DLL setups load fine and just run plain VulkanMod with DLSS unavailable

---

## Licensing

VulkanMod is **LGPL-3.0**, so this fork is too - the source is published, notices preserved, and you can relink against a modified VulkanMod. NVIDIA Streamline / NGX DLSS DLLs are **proprietary**, redistributed only as unmodified signed binaries under NVIDIA's license and never committed here; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Minecraft itself and Mojang mappings are not redistributed - the loader pulls those at build time under their own terms.
