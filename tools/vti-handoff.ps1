# vti-handoff.ps1 - stage a Claude-written plan for Antigravity with two keystrokes.
#
#   .\tools\vti-handoff.ps1                          uses Docs\next_plan.md
#   .\tools\vti-handoff.ps1 Docs\audio_fix_plan.md   uses a specific plan
#
# WHY THIS IS NOT FULLY AUTOMATIC
# -------------------------------
# Antigravity ships VS Code's `chat` subcommand (`antigravity-ide chat --mode agent "<prompt>"`),
# and its --help advertises a prompt argument. Tested 2026-08-09 four ways - positional arg,
# stdin form, cold window, fully-loaded window - and the prompt is NEVER delivered to the Agent
# panel. It opens and focuses the panel, then silently drops the text, and exits 0 either way,
# so failure is undetectable from a script. The subcommand is inherited plumbing this fork
# never wired up. Do not "fix" this by passing the prompt differently; it does not work.
#
# So this script does the parts that DO work - open the workspace, open the Agent panel, and
# load the exact prompt onto the clipboard - and leaves you Ctrl+V then Enter.

# -Surface picks which Antigravity you land in. They are TWO DIFFERENT APPS sharing a name:
#   manager - %LOCALAPPDATA%\Programs\Antigravity     - Google's Agent Manager desktop app.
#             Plain Electron, no workspaceStorage, keeps its own cross-project chat history.
#             This is the one Harsh normally works in, so it is the default.
#   ide     - %LOCALAPPDATA%\Programs\Antigravity IDE - the VS Code fork. Has the real
#             workspaceStorage (Voice To Invoice, Internship) and the antigravity-ide CLI.
# Neither can be handed a prompt programmatically - see the note above - so both routes end
# with the prompt on the clipboard.
param(
    [string]$Plan = "Docs\next_plan.md",
    [ValidateSet("manager", "ide")]
    [string]$Surface = "manager"
)

# NOT "Stop" - see the note in vti-ship.ps1: native exe stderr becomes a terminating
# error under PowerShell 5.1, and the Antigravity CLI writes to stderr on startup.
$ErrorActionPreference = "Continue"

$Repo    = "C:\Users\harsh\Documents\Voice To Invoice"
$Ag      = "C:\Users\harsh\AppData\Local\Programs\Antigravity IDE\bin\antigravity-ide.cmd"
$Manager = "C:\Users\harsh\AppData\Local\Programs\Antigravity\Antigravity.exe"

function Say([string]$m, [string]$c = "White") { Write-Host $m -ForegroundColor $c }

Set-Location $Repo

if (-not (Test-Path $Plan)) { Say "Plan not found: $Plan" "Red"; exit 1 }

$planName = (Resolve-Path $Plan).Path.Replace("$Repo\", "")

# Mirrors the implementer contract in CLAUDE.md so the agent does not redesign or widen scope.
$prompt = @"
Implement the plan in $planName exactly as written, word by word - same steps, same order,
same files, same names, same constants.

Follow the 'If you are Antigravity - implement the plan verbatim' section of CLAUDE.md:
do not redesign, do not widen scope, do not narrow scope, and do NOT create an
implementation_plan.md artifact - edit the source directly.

If a step is contradictory or names something that does not exist, stop on that step,
finish everything that does not depend on it, and ask me a specific question quoting the
plan line and what the code actually shows.

End with a 'Deviations' section listing anything changed, skipped, or interpreted
differently from the literal text. If none, say 'None.'
"@

Say "`nStaging handoff to Antigravity ($Surface)" "Cyan"
Say "  plan: $planName" "DarkGray"

# Load the prompt first, so it is ready the moment the window appears.
Set-Clipboard -Value $prompt

if ($Surface -eq "ide") {
    if (-not (Test-Path $Ag)) { Say "Antigravity IDE CLI not found at $Ag" "Red"; exit 1 }

    # Open the workspace first - without it the Agent panel opens with no folder attached.
    & $Ag $Repo | Out-Null
    Start-Sleep -Seconds 12

    # Open + focus the Agent panel, scoped to the workspace.
    & $Ag chat --mode agent --reuse-window | Out-Null
    Start-Sleep -Seconds 3

    Say "  Workspace open, Agent panel focused, prompt on clipboard." "Green"
} else {
    if (-not (Test-Path $Manager)) { Say "Agent Manager not found at $Manager" "Red"; exit 1 }

    # The Manager has no CLI and no workspace concept - just bring it to the front.
    Start-Process $Manager
    Start-Sleep -Seconds 5

    Say "  Agent Manager open, prompt on clipboard." "Green"
}

Say "`n  -> Click the Agent box, press Ctrl+V, then Enter." "Yellow"
Say "`nWhen Antigravity finishes, run:  .\tools\vti-ship.ps1`n" "DarkGray"

exit 0
