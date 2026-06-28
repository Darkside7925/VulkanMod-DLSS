# env.ps1 — sets up the portable toolchain for this project (no admin required).
# Dot-source it:  . .\tools\env.ps1
# Then `java`, `gradle` (via wrapper), and `cmake` resolve to the portable installs.

$ErrorActionPreference = 'Stop'

# --- Portable JDK 21 (Temurin) ---
$jdk = Get-ChildItem 'C:\Users\ibrah\tools' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
       Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw "Portable JDK 21 not found under C:\Users\ibrah\tools (expected jdk-21.*)" }
$env:JAVA_HOME = $jdk.FullName

# --- Portable CMake ---
$cmake = Get-ChildItem 'C:\Users\ibrah\tools' -Directory -Filter 'cmake-*windows-x86_64' -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending | Select-Object -First 1

# --- MSVC (only present once Build Tools are installed elevated; optional until native phase) ---
$vcvars = @(
  'C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat',
  'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat',
  'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
) | Where-Object { Test-Path $_ } | Select-Object -First 1

$env:PATH = "$env:JAVA_HOME\bin;" + ($(if ($cmake) { "$($cmake.FullName)\bin;" } else { '' })) + $env:PATH

Write-Host "JAVA_HOME = $env:JAVA_HOME"
& "$env:JAVA_HOME\bin\java.exe" -version
if ($cmake) { Write-Host "CMake     = $($cmake.FullName)" } else { Write-Host "CMake     = (not found)" -ForegroundColor Yellow }
if ($vcvars) { Write-Host "MSVC      = $vcvars" } else { Write-Host "MSVC      = (NOT INSTALLED — needed for native build, requires admin to install)" -ForegroundColor Yellow }
