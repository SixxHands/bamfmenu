# Tests rolling back to an older release with an older engine and verifying EngineGuard rolls back automatically if it fails.

param([string]$From = "v0.0.8-test", [string]$To = "v0.0.3-test")
. "$PSScriptRoot\_common.ps1"

function EngineSha([string]$Box) {
    $p = "$Box\.bamfos-updater\engine\bamfos-updater-engine.jar"
    if (Test-Path $p) { (Get-FileHash $p -Algorithm SHA256).Hash.Substring(0, 12) } else { "ABSENT" }
}
function Slot([string]$Box) {
    $e = "$Box\.bamfos-updater\engine"
    $names = @(Get-ChildItem $e -File -EA SilentlyContinue | ForEach-Object { $_.Name })
    "engine=" + (EngineSha $Box) + "  slot=[" + ($names -join ", ") + "]"
}
function Pin([string]$Box, [string]$Tag) {
    "repo=SixxHands/bamfos-pack`ntag=$Tag" | Out-File "$Box\.bamfos-updater\bamfos-updater.properties" -Encoding ascii
}

Write-Output "########## 1. a client at $From"
$box = New-Box -Name "downgrade" -Tag $From
Invoke-Boot -Box $box
$newEngine = EngineSha $box
Write-Output ("    " + (Slot $box))

Write-Output ""
Write-Output "########## 2. pin back to $To and boot, which promotes THAT release's engine"
Pin $box $To
Invoke-Boot -Box $box -Filter "updater|updated|current|carrier|skipped|engine"
Write-Output ("    " + (Slot $box))

Write-Output ""
Write-Output "########## 3. the boot that RUNS the old engine"
# looks like a healthy quiet boot and is not; still exits 0 so the game boots anyway.
Invoke-Boot -Box $box -Filter "updater|updated|current|carrier|skipped|engine|rejected|probation|rollback"
$afterOld = EngineSha $box
Write-Output ("    " + (Slot $box))

Write-Output ""
Write-Output "########## 4. the NEXT boot, which is where recovery lives"
Invoke-Boot -Box $box -Filter "updater|updated|current|carrier|skipped|engine|rejected|probation|rollback|strike"
$recovered = EngineSha $box
Write-Output ("    " + (Slot $box))

Write-Output ""
Write-Output "########## what happened"
Write-Output ("    engine at $From              : " + $newEngine)
Write-Output ("    engine after rolling back    : " + $afterOld)
Write-Output ("    engine after the next boot   : " + $recovered)
Write-Output ("    the engine MOVED on rollback : " + ($newEngine -ne $afterOld))
Write-Output ("    a .good backup exists        : " + (Test-Path "$box\.bamfos-updater\engine\bamfos-updater-engine.jar.good"))
# Check whatever EngineHealth state files exist in the engine slot.
$slot = "$box\.bamfos-updater\engine"
$marks = @(Get-ChildItem $slot -File -EA SilentlyContinue |
           Where-Object { $_.Name -notlike "*.jar*" })
if ($marks.Count -eq 0) {
    Write-Output "    probation markers            : none in $slot (listing above is the proof, not this line)"
} else {
    foreach ($m in $marks) {
        Write-Output ("    " + $m.Name + ":")
        Get-Content $m.FullName | Where-Object { $_ } | ForEach-Object { "        $_" }
    }
}
Write-Output ""
Write-Output "    every boot above exited 0, which is Law 5 and is the point: an updater that cannot update"
Write-Output "    must still let the game start. Read the ENGINE SHAS, not the exit codes, to see what moved."
