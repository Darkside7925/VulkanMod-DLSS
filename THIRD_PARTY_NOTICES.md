# Third-Party Notices

This project (`mc-dlss`) combines several third-party components. Each is governed by its own license.

## VulkanMod (fork base)
- Source: https://github.com/xCollateral/VulkanMod (branch `dev`, mod_version 0.6.7-dev, targeting Minecraft 1.21.11)
- License: **GNU LGPL-3.0**
- This project forks and modifies VulkanMod's Vulkan renderer. The combined work is therefore distributed under
  LGPL-3.0: corresponding source is published, copyright/license notices are preserved, and users may relink against
  a modified VulkanMod. See `vulkanmod/LICENSE`.

## NVIDIA Streamline SDK + DLSS / DLSS-G / Reflex / NGX
- Source (open headers + interposer/plugins source): https://github.com/NVIDIA-RTX/Streamline (v2.12.0)
- Redistributable signed binaries: `streamline-sdk-v2.12.0.zip` (GitHub release), Authenticode-signed `CN=NVIDIA Corporation`.
  - Streamline: `sl.interposer.dll`, `sl.common.dll`, `sl.dlss.dll`, `sl.dlss_g.dll`, `sl.dlss_d.dll`, `sl.reflex.dll`,
    `sl.pcl.dll`, `sl.nis.dll`, `NvLowLatencyVk.dll`.
  - NGX models (v310.7): `nvngx_dlss.dll`, `nvngx_dlssg.dll`, `nvngx_dlssd.dll`, `nvngx_deepdvc.dll`.
- License: **proprietary — NVIDIA Software License Agreement** (see `native_streamline/license.txt` and
  `native_streamline_sdk/`). These DLLs are redistributed **unmodified** under NVIDIA's terms.
- **These binaries are NOT committed to version control.** To build/run, download `streamline-sdk-v2.12.0.zip` from the
  NVIDIA-RTX/Streamline releases and extract to `native_streamline_sdk/` (see README).
- `sl.dlss_g` and `nvngx_*` are **not** open-source and **cannot** be recompiled from source.

## Minecraft / Mojang
- Minecraft and its assets/mappings are property of Mojang/Microsoft and are **not** redistributed by this project.
- Official mappings are obtained via Fabric/Yarn at build time under their respective terms.

## Fabric (Loader / API / Loom)
- https://fabricmc.net — License: Apache-2.0.

---
If any combination above is legally ambiguous for a given distribution channel, resolve with a human/legal review
before publishing. See README "Licensing".
