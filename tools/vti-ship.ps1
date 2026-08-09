# vti-ship.ps1 - build the debug APK, verify it is actually new, archive it, install it on the phone.
#
#   .\tools\vti-ship.ps1              build + verify + archive + install
#   .\tools\vti-ship.ps1 -SkipBuild   archive/install whatever is already in C:\VTI_build
#   .\tools\vti-ship.ps1 -NoInstall   build + archive only
#
# Wireless adb: the phone is the default gateway whenever the laptop is on its hotspot,
# so the device address is auto-detected rather than hardcoded. USB is used as a fallback.

param(
    [switch]$SkipBuild,
    [switch]$NoInstall,
    [string]$DeviceIp = "",
    [int]$Port = 5555
)

# NOT "Stop": under PowerShell 5.1 anything a native exe writes to stderr becomes a
# terminating NativeCommandError, and gradlew always emits JVM warnings there. That would
# abort the script before the retry logic below ever ran. Exit codes are checked explicitly.
$ErrorActionPreference = "Continue"

$Repo    = "C:\Users\harsh\Documents\Voice To Invoice"
$Adb     = "C:\Users\harsh\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$BuiltApk= "C:\VTI_build\app\outputs\apk\debug\app-debug.apk"
$ApkStore= "C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs"
$Pkg     = "com.voicetoinvoice.app"

function Say([string]$m, [string]$c = "White") { Write-Host $m -ForegroundColor $c }
function Fail([string]$m) { Say "FAILED: $m" "Red"; exit 1 }

Set-Location $Repo

# --- 1. Build -----------------------------------------------------------------
if (-not $SkipBuild) {
    Say "`n[1/5] Building debug APK..." "Cyan"

    & "$Repo\gradlew.bat" assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        # Two known failure modes, same remedy. (a) KSP throws IOException because
        # OneDrive/Defender holds a lock on its output dir. (b) The Kotlin daemon is killed
        # mid-compile ("Connection to the Kotlin daemon has been unexpectedly lost") when
        # free RAM is under ~4GB - Gradle reserves 2GB and the Kotlin daemon wants 2GB more.
        Say "Build failed - clearing daemons + KSP lock and retrying once..." "Yellow"
        & "$Repo\gradlew.bat" --stop | Out-Null
        Start-Sleep -Seconds 3
        Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 3
        Remove-Item "$Repo\app\build\generated\ksp" -Recurse -Force -ErrorAction SilentlyContinue

        $freeGb = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB, 1)
        Say "  Free RAM: $freeGb GB" "DarkGray"
        if ($freeGb -lt 4) { Say "  Under 4GB free - close Chrome/Apple Music if this retry also fails." "Yellow" }

        & "$Repo\gradlew.bat" assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { Fail "gradle assembleDebug failed twice." }
    }
} else {
    Say "`n[1/5] Skipping build (-SkipBuild)." "DarkGray"
}

if (-not (Test-Path $BuiltApk)) { Fail "No APK at $BuiltApk" }

# --- 2. Freshness check -------------------------------------------------------
# This repo has a history of shipping stale artifacts (v91-v109 were one identical
# binary). Never claim a fix shipped without proving the bytes changed.
Say "`n[2/5] Verifying the build is actually new..." "Cyan"

# Note: an old timestamp here is NOT automatically an error - if gradle reports
# UP-TO-DATE (no source changed) it legitimately leaves the existing APK alone.
# The md5 comparison below is the authoritative "did anything actually change" check.
$age = (Get-Date) - (Get-Item $BuiltApk).LastWriteTime
if (-not $SkipBuild -and $age.TotalMinutes -gt 10) {
    Say "  Note: APK is $([math]::Round($age.TotalMinutes)) min old - gradle was UP-TO-DATE, no source changed." "Yellow"
}

$newHash = (Get-FileHash $BuiltApk -Algorithm MD5).Hash

$prev = Get-ChildItem "$ApkStore\VoiceToInvoice_v*.apk" -ErrorAction SilentlyContinue |
        Sort-Object { [int]($_.BaseName -replace '.*_v', '') } |
        Select-Object -Last 1

if ($prev) {
    $prevHash = (Get-FileHash $prev.FullName -Algorithm MD5).Hash
    if ($prevHash -eq $newHash) {
        Say "  WARNING: identical to $($prev.Name) (md5 $newHash)." "Yellow"
        Say "  Nothing changed in this build - the source edits did not take effect." "Yellow"
    } else {
        Say "  OK - differs from $($prev.Name)" "Green"
        Say "    was: $prevHash" "DarkGray"
        Say "    now: $newHash" "DarkGray"
    }
}

# --- 3. Archive ---------------------------------------------------------------
$next    = if ($prev) { [int]($prev.BaseName -replace '.*_v', '') + 1 } else { 1 }
$dest    = "$ApkStore\VoiceToInvoice_v$next.apk"

Say "`n[3/5] Archiving as VoiceToInvoice_v$next.apk" "Cyan"
Copy-Item $BuiltApk $dest -Force

$copyHash = (Get-FileHash $dest -Algorithm MD5).Hash
if ($copyHash -ne $newHash) { Fail "Copy corrupted: $copyHash != $newHash" }
Say "  Copy verified." "Green"

if ($NoInstall) { Say "`nDone (-NoInstall). APK at $dest" "Green"; exit 0 }

# --- 4. Reach the phone -------------------------------------------------------
Say "`n[4/5] Connecting to phone..." "Cyan"

if (-not $DeviceIp) {
    $DeviceIp = (Get-NetIPConfiguration |
                 Where-Object { $_.IPv4DefaultGateway -ne $null } |
                 Select-Object -First 1).IPv4DefaultGateway.NextHop
}
$target = "${DeviceIp}:$Port"

# The hotspot hands out a new subnet each time it restarts, so previous addresses linger
# in `adb devices` as dead 'offline' entries. Drop them before connecting.
(& $Adb devices) | Select-String "^(\S+:\d+)\s+offline" | ForEach-Object {
    $stale = $_.Matches.Groups[1].Value
    Say "  Dropping stale endpoint $stale" "DarkGray"
    & $Adb disconnect $stale | Out-Null
}

& $Adb connect $target | Out-Null
Start-Sleep -Seconds 1

$online = (& $Adb devices) -match "^$([regex]::Escape($target))\s+device$"

if (-not $online) {
    # Wireless is down (phone rebooted -> tcpip mode resets). Fall back to USB and re-arm it.
    Say "  Wireless unreachable at $target - trying USB..." "Yellow"
    $usb = (& $Adb devices) | Select-String -Pattern "^\w+\s+device$" | Select-Object -First 1
    if (-not $usb) {
        Fail "No device over wireless or USB. Plug the cable in, then re-run to re-arm wireless."
    }
    $serial = ($usb -split "\s+")[0]
    Say "  USB device $serial found - re-arming wireless on port $Port..." "Yellow"
    & $Adb -s $serial tcpip $Port | Out-Null
    Start-Sleep -Seconds 3
    & $Adb connect $target | Out-Null
    Start-Sleep -Seconds 1
    $online = (& $Adb devices) -match "^$([regex]::Escape($target))\s+device$"
    if (-not $online) { $target = $serial; Say "  Using USB." "Yellow" }
    else { Say "  Wireless re-armed. Cable can be unplugged." "Green" }
} else {
    Say "  Connected wirelessly to $target" "Green"
}

# --- 5. Install ---------------------------------------------------------------
Say "`n[5/5] Installing..." "Cyan"

function Get-PkgField([string]$pattern) {
    $hit = & $Adb -s $target shell dumpsys package $Pkg 2>$null | Select-String $pattern | Select-Object -First 1
    if ($hit) { $hit.Matches.Groups[1].Value } else { "unknown" }
}

$before = Get-PkgField "versionCode=(\d+)"

# Collapse to a single string before testing. adb prints "Performing Streamed Install" on a
# separate stream, so $result is an array - and `-notmatch` on an array returns the
# non-matching ELEMENTS, not a boolean, which reads as true even on a successful install.
$result = (& $Adb -s $target install -r $dest 2>&1 | Out-String)
if ($result -notmatch "Success") {
    Say $result "Red"
    Fail "adb install failed. If it says INSTALL_FAILED_USER_RESTRICTED, enable 'Install via USB' in Developer options."
}

$after = Get-PkgField "lastUpdateTime=(.+)"

Say "`n  Installed v$next on the phone." "Green"
Say "  versionCode before: $before" "DarkGray"
Say "  lastUpdateTime now: $after" "DarkGray"
Say "  md5: $newHash" "DarkGray"
Say "`nDone.`n" "Green"

# Explicit: gradlew writes to stderr, which otherwise leaves a non-zero code behind and
# makes this script look failed to anything checking $LASTEXITCODE (e.g. vti-handoff).
exit 0
