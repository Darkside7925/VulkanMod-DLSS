# build_native.ps1 — configure + build mcdlss_native.dll and stage it into the dev run dir.
# Usage:  .\tools\build_native.ps1 [-Streamline] [-Config Release|Debug]
param(
    [switch]$Streamline,                  # turn on MCDLSS_WITH_STREAMLINE (Phase 1+)
    [ValidateSet('Release','Debug')] [string]$Config = 'Release'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1"

$cmake = (Get-ChildItem 'C:\Users\ibrah\tools' -Directory -Filter 'cmake-*windows-x86_64' |
          Sort-Object Name -Descending | Select-Object -First 1).FullName + '\bin\cmake.exe'
$root = Split-Path $PSScriptRoot -Parent
$src  = Join-Path $root 'native'
$bld  = Join-Path $src  'build'

$cfgArgs = @('-S', $src, '-B', $bld, '-G', 'Visual Studio 17 2022', '-A', 'x64')
if ($Streamline) { $cfgArgs += '-DMCDLSS_WITH_STREAMLINE=ON' }
& $cmake @cfgArgs
& $cmake --build $bld --config $Config

$dll = Join-Path $bld "$Config\mcdlss_native.dll"
if (-not (Test-Path $dll)) { throw "Build produced no DLL at $dll" }

# Stage native DLL + (if Streamline) the signed redistributables next to it, so the OS loader
# resolves sl.interposer.dll etc. when mcdlss_native is loaded.
$run   = Join-Path $root 'vulkanmod\run'
$stage = Join-Path $run  'mcdlss'
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item $dll $stage -Force
Write-Host "Staged $dll -> $stage"

if ($Streamline) {
    $sdkBin = Join-Path $root 'native_streamline_sdk\bin\x64'
    Get-ChildItem $sdkBin -Filter 'sl.*.dll' | Copy-Item -Destination $stage -Force
    Get-ChildItem $sdkBin -Filter 'nvngx_*.dll' | Copy-Item -Destination $stage -Force
    $low = Join-Path $sdkBin 'NvLowLatencyVk.dll'; if (Test-Path $low) { Copy-Item $low $stage -Force }
    Write-Host "Staged Streamline redistributables (sl.*, nvngx_*, NvLowLatencyVk) -> $stage"
}
