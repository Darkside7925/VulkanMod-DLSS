# run_dev.ps1 — launch the VulkanMod+DLSS dev client with the native lib staged.
# Usage:  .\tools\run_dev.ps1 [-BuildNative] [-Streamline]
param(
    [switch]$BuildNative,   # rebuild mcdlss_native.dll before launching
    [switch]$Streamline     # build/stage with Streamline enabled (Phase 1+)
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1"

$root = Split-Path $PSScriptRoot -Parent

if ($BuildNative) {
    & "$PSScriptRoot\build_native.ps1" -Streamline:$Streamline
}

# Ensure the native DLL is staged even if -BuildNative was not passed.
$dll = Join-Path $root 'native\build\Release\mcdlss_native.dll'
$stage = Join-Path $root 'vulkanmod\run\mcdlss'
if ((Test-Path $dll) -and -not (Test-Path (Join-Path $stage 'mcdlss_native.dll'))) {
    New-Item -ItemType Directory -Force $stage | Out-Null
    Copy-Item $dll $stage -Force
}

Push-Location (Join-Path $root 'vulkanmod')
try {
    & .\gradlew.bat runClient --no-daemon
} finally {
    Pop-Location
}
