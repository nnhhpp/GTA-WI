# GTA WI - one-time setup: downloads the portable build tools into .\tools (nothing is installed system-wide).
#   JDK 8 (Temurin)      compiles for old phones (Java 1.3 bytecode)          ~100 MB
#   ProGuard 7.6.1       shrinks + preverifies the jar for MIDP                 ~10 MB
#   uv                   runs the Python scripts (brings its own Python)        ~20 MB
#   FreeJ2ME-Plus 1.52   J2ME emulator for the PC (+ the MIDP classes javac compiles against)
#   Blender 5.2.3        renders the sprites from your GTA III models           ~400 MB
#   INU Tools 2.5.4      Blender extension that imports GTA III .dff/.txd files
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'          # makes Invoke-WebRequest much faster
Set-Location $PSScriptRoot
$env:UV_CACHE_DIR = "$PSScriptRoot\tools\uv-cache"; $env:UV_PYTHON_INSTALL_DIR = "$PSScriptRoot\tools\python"    # keep Python inside tools\ too
New-Item -ItemType Directory -Force tools | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-Zip($url, $zip, $dest) {
    Write-Host "  downloading $url"
    Invoke-WebRequest $url -OutFile $zip -UseBasicParsing
    Expand-Zip $zip $dest
}
function Expand-Zip($zip, $dest) {
    Write-Host "  unpacking..."
    New-Item -ItemType Directory -Force $dest | Out-Null
    $ok = $false
    try { & tar.exe -xf $zip -C $dest; $ok = ($LASTEXITCODE -eq 0) } catch { $ok = $false }   # Windows' own tar is much faster
    if (-not $ok) { Expand-Archive $zip -DestinationPath $dest -Force }
    Remove-Item $zip
}

# --- JDK 8
if (-not (Get-ChildItem tools -Directory -Filter 'jdk8u*' -ErrorAction SilentlyContinue)) {
    Write-Host 'JDK 8 (Temurin 8u432)...'
    Get-Zip 'https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u432-b06/OpenJDK8U-jdk_x64_windows_hotspot_8u432b06.zip' 'tools\jdk8.zip' 'tools'
} else { Write-Host 'JDK 8: already there' }

# --- ProGuard
if (-not (Test-Path 'tools\proguard-7.6.1')) {
    Write-Host 'ProGuard 7.6.1...'
    Get-Zip 'https://github.com/Guardsquare/proguard/releases/download/v7.6.1/proguard-7.6.1.zip' 'tools\proguard.zip' 'tools'
} else { Write-Host 'ProGuard: already there' }

# --- uv (portable: just uv.exe in tools\uv)
if (-not (Get-ChildItem tools\uv -Recurse -Filter 'uv.exe' -ErrorAction SilentlyContinue)) {
    Write-Host 'uv...'
    Get-Zip 'https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-pc-windows-msvc.zip' 'tools\uv.zip' 'tools\uv'
} else { Write-Host 'uv: already there' }
$uvexe = (Get-ChildItem tools\uv -Recurse -Filter 'uv.exe' | Select-Object -First 1).FullName

# --- FreeJ2ME-Plus
if (-not (Get-ChildItem tools\freej2me -Recurse -Filter 'freej2me.jar' -ErrorAction SilentlyContinue)) {
    Write-Host 'FreeJ2ME-Plus 1.52...'
    Get-Zip 'https://github.com/TASEmulators/freej2me-plus/releases/download/1.52/freej2me-v1.52.zip' 'tools\freej2me.zip' 'tools\freej2me'
    if (-not (Get-ChildItem tools\freej2me -Recurse -Filter 'freej2me.jar')) { throw 'freej2me.jar was not in the FreeJ2ME-Plus zip - see BUILDING.md (Troubleshooting)' }
} else { Write-Host 'FreeJ2ME: already there' }

# --- Blender (portable: a 'portable' folder next to blender.exe keeps its settings inside tools\)
$bl = 'tools\blender-5.2.3-windows-x64'
if (-not (Test-Path "$bl\blender.exe")) {
    Write-Host 'Blender 5.2.3 (~400 MB, takes a while)...'
    $zip = 'tools\blender.zip'
    Invoke-WebRequest 'https://download.blender.org/release/Blender5.2/blender-5.2.3-windows-x64.zip' -OutFile $zip -UseBasicParsing
    Invoke-WebRequest 'https://download.blender.org/release/Blender5.2/blender-5.2.3.sha256' -OutFile 'tools\blender.sha256' -UseBasicParsing
    $want = ((Get-Content 'tools\blender.sha256' | Where-Object { $_ -match 'windows-x64\.zip' } | Select-Object -First 1) -split '\s+')[0]
    Remove-Item 'tools\blender.sha256'
    $got = (Get-FileHash $zip -Algorithm SHA256).Hash
    if ($want -and ($got -ne $want.ToUpper())) { Remove-Item $zip; throw "Blender download is damaged (sha256 $got) - run setup.bat again" }
    Expand-Zip $zip 'tools'
} else { Write-Host 'Blender: already there' }
New-Item -ItemType Directory -Force "$bl\portable" | Out-Null

# --- INU Tools (GPL-3) into the portable Blender
$inu = 'tools\inu_tools_gta_sa-2.5.4.zip'
if (-not (Test-Path "$bl\portable\extensions\user_default\inu_tools_gta_sa")) {
    Write-Host 'INU Tools 2.5.4 (GTA model importer for Blender)...'
    Invoke-WebRequest 'https://github.com/INU-ez/INU_Tools-GTA-Blender/releases/download/v2.5.4/inu_tools_gta_sa-2.5.4.zip' -OutFile $inu -UseBasicParsing
    & "$bl\blender.exe" --command extension install-file -r user_default -e $inu
    if ($LASTEXITCODE -ne 0) { throw 'Could not install INU Tools into Blender' }
} else { Write-Host 'INU Tools: already there' }

# --- warm up uv (downloads Python + numpy + pillow once)
& $uvexe run --with numpy --with pillow python -c "import numpy, PIL; print('python + numpy + pillow ok')"
Write-Host ''
Write-Host 'Setup done. Now set your GTA III folder in config.bat and run build.bat.' -ForegroundColor Green
