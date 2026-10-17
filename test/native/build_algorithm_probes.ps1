[CmdletBinding()]
param(
    [string]$Ndk = $env:ANDROID_NDK_HOME,
    [string]$OutDir,
    [switch]$ProductionLibrary
)
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$taskProject = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $taskProject 'out/rc11-algorithm-native'
}
if ([string]::IsNullOrWhiteSpace($Ndk)) { throw '请通过 -Ndk 或 ANDROID_NDK_HOME 指定 Android NDK。' }
$taskCompiler = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
if (-not (Test-Path -LiteralPath $taskCompiler -PathType Leaf)) { throw "找不到编译器：$taskCompiler" }
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
[System.IO.Directory]::CreateDirectory($OutDir) | Out-Null
function Invoke-TaskCompiler {
    param([string[]]$CompilerArguments)
    & $taskCompiler @CompilerArguments
    if ($LASTEXITCODE -ne 0) { throw "原生编译失败：$LASTEXITCODE" }
}
$taskFlags = @('--target=aarch64-linux-android29', '-std=c++17', '-O2', '-static-libstdc++', '-Wall', '-Wextra', '-Werror', '-DNDEBUG')
foreach ($taskProbe in @('algorithm_policy_probe', 'algorithm_gpu_probe', 'queue_retry_probe')) {
    $taskArguments = $taskFlags + @((Join-Path $PSScriptRoot ($taskProbe + '.cpp')), '-o', (Join-Path $OutDir $taskProbe))
    if ($taskProbe -eq 'algorithm_gpu_probe') { $taskArguments += @('-lEGL', '-lGLESv3') }
    Invoke-TaskCompiler -CompilerArguments $taskArguments
}
if ($ProductionLibrary) {
    Invoke-TaskCompiler -CompilerArguments ($taskFlags + @('-fPIC', '-shared', (Join-Path $taskProject 'native/service_gpu.cpp'), '-lEGL', '-lGLESv3', '-lnativewindow', '-llog', '-ldl', '-Wl,-soname,libeinkgpu.so', '-o', (Join-Path $OutDir 'libeinkgpu-algorithm-check.so')))
}
$taskHashes = [ordered]@{}
foreach ($taskDirectory in @((Join-Path $taskProject 'native'), $PSScriptRoot)) {
    foreach ($taskFile in Get-ChildItem -LiteralPath $taskDirectory -File | Sort-Object Name) {
        if ($taskFile.Extension -notin @('.cpp', '.hpp', '.ps1', '.md')) { continue }
        $taskRelative = $taskFile.FullName.Substring($taskProject.Length + 1).Replace('\', '/')
        $taskHashes[$taskRelative] = (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$taskManifest = [ordered]@{
    target = 'aarch64-linux-android29'
    validation = 'cross-compile only; device probe pending'
    sources = $taskHashes
}
$taskJson = $taskManifest | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText((Join-Path $OutDir 'compile-sources.json'), $taskJson + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Android 独立 probe 构建通过：$OutDir"
