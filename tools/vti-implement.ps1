# vti-implement.ps1 - hand a plan to the Antigravity CLI agent and wait for it to finish.
#
#   .\tools\vti-implement.ps1 Docs\my_plan.md
#   .\tools\vti-implement.ps1 Docs\my_plan.md -Effort high
#
# This is the automated replacement for the manual Claude -> Antigravity handoff. It runs on
# the Google AI Pro quota, not the Claude quota, and it BLOCKS until the agent is done.
#
# CRITICAL - two traps found while building this (2026-08-09):
#
# 1. WITHOUT --add-dir, agy sandboxes the workspace to its own "brain" directory
#    (%USERPROFILE%\.gemini\antigravity-cli\brain\<uuid>\) and writes there instead of the
#    repo - while reporting success and even printing a repo-looking path. A test file came
#    back "created at scratch/agy_edit_test.txt" that was actually 26 bytes inside the brain
#    dir and absent from the repo. Always pass --add-dir AND absolute paths in the prompt.
#
# 2. agy exits 0 and prints a confident summary whether or not it changed anything. Its exit
#    code proves nothing. This script verifies by effect - it diffs the working tree before
#    and after and fails loudly if nothing moved.
#
# Do not confuse this CLI with the two GUI apps. `agy.exe` is the Antigravity CLI (v1.1.11,
# %LOCALAPPDATA%\agy\bin, not on PATH). `antigravity-ide chat` is a different thing and is
# broken - see tools\vti-handoff.ps1.

param(
    [Parameter(Mandatory = $true)]
    [string]$Plan,
    [ValidateSet("low", "medium", "high")]
    [string]$Effort = "high",
    [string]$Timeout = "30m"
)

# NOT "Stop" - native exe stderr becomes a terminating error under PowerShell 5.1.
$ErrorActionPreference = "Continue"

$Repo = "C:\Users\harsh\Documents\Voice To Invoice"
$Agy  = "C:\Users\harsh\AppData\Local\agy\bin\agy.exe"

function Say([string]$m, [string]$c = "White") { Write-Host $m -ForegroundColor $c }
function Fail([string]$m) { Say "FAILED: $m" "Red"; exit 1 }

Set-Location $Repo

if (-not (Test-Path $Agy))  { Fail "Antigravity CLI not found at $Agy" }
if (-not (Test-Path $Plan)) { Fail "Plan not found: $Plan" }

$planAbs  = (Resolve-Path $Plan).Path
$planName = $planAbs.Replace("$Repo\", "")

# Mirrors the implementer contract in CLAUDE.md so the agent does not redesign or widen scope.
# Absolute paths are stated explicitly because of trap #1 above.
$prompt = @"
You are working in the repository at $Repo. Every file you read or write is under that
absolute path. Do NOT create files anywhere else.

Implement the plan in $planAbs exactly as written, word by word - same steps, same order,
same files, same names, same constants.

Follow the 'If you are Antigravity - implement the plan verbatim' section of
$Repo\CLAUDE.md: do not redesign, do not widen scope, do not narrow scope, and do NOT
create an implementation_plan.md artifact - edit the source directly.

If a step is contradictory or names a file/symbol that does not exist, stop on that step,
finish everything that does not depend on it, and say clearly which step you stopped on,
quoting the plan line and what the code actually shows.

Do not run gradle and do not build - the build is handled separately after you finish.

End with a 'Deviations' section listing anything changed, skipped, or interpreted
differently from the literal text. If none, say 'None.'
"@

Say "`nImplementing $planName via Antigravity CLI" "Cyan"
Say "  effort=$Effort timeout=$Timeout" "DarkGray"
Say "  (blocks until the agent finishes)" "DarkGray"

# Snapshot the working tree so we can prove the agent actually changed something.
# Must hash the DIFF CONTENT, not `git status --porcelain`: this repo carries ~38 already-
# modified files, and editing an already-" M" file leaves the porcelain output byte-identical.
# That produced a false "changed nothing" on the first real run.
function Get-TreeState {
    ((git diff HEAD 2>$null) -join "`n") + "`n---`n" + ((git status --porcelain 2>$null) -join "`n")
}
$before = Get-TreeState

$runStart = Get-Date
$sw = [Diagnostics.Stopwatch]::StartNew()
& $Agy --add-dir $Repo `
       --mode accept-edits `
       --dangerously-skip-permissions `
       --effort $Effort `
       --print-timeout $Timeout `
       -p $prompt
$agyExit = $LASTEXITCODE
$sw.Stop()

Say "`n  Agent finished in $([math]::Round($sw.Elapsed.TotalMinutes,1)) min (exit $agyExit)" "DarkGray"

if ($agyExit -ne 0) { Fail "Antigravity CLI exited $agyExit." }

# Verify by effect, not by exit code (trap #2).
$after = Get-TreeState

if ($before -eq $after) {
    Say "`n  WARNING: the working tree is byte-identical to before the run." "Yellow"
    Say "  The agent reported success but changed nothing. Do NOT treat this as implemented." "Yellow"
    Say "  Check whether it wrote into its sandbox instead:" "Yellow"
    Say "    $env:USERPROFILE\.gemini\antigravity-cli\brain\" "DarkGray"
    exit 2
}

# Report only what THIS run touched. `git status --porcelain` is useless for that here - the
# repo carries ~38 long-standing modified files that would drown the actual change.
Say "`n  Working tree changed. Files this run touched:" "Green"
$touched = Get-ChildItem "$Repo\app", "$Repo\supabase", "$Repo\Docs" -Recurse -File -ErrorAction SilentlyContinue |
           Where-Object { $_.LastWriteTime -ge $runStart } |
           Select-Object -First 30
if ($touched) {
    $touched | ForEach-Object { Say ("    " + $_.FullName.Replace("$Repo\", "")) "DarkGray" }
} else {
    Say "    (content changed but no file mtime in app/supabase/Docs - check the diff)" "Yellow"
}

Say "`nNext:  .\tools\vti-ship.ps1`n" "DarkGray"
exit 0
