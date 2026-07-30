# End-to-end install, update, rollback, and server sync against real published releases (~800MB download).
# Usage: powershell -File tools\updater-tests\lifecycle.ps1 -From <tag> -To <tag>

param([string]$From = "v0.0.5-test", [string]$To = "v0.0.6-test", [switch]$SkipServer)
. "$PSScriptRoot\_common.ps1"

function Pin([string]$Box, [string]$Tag) {
    "repo=SixxHands/bamfos-pack`ntag=$Tag" | Out-File "$Box\.bamfos-updater\bamfos-updater.properties" -Encoding ascii
}
function State([string]$Box) {
    $l = Get-Content "$Box\.bamfos-updater\ledger.json" -Raw | ConvertFrom-Json
    $bcc = ((Get-Content "$Box\config\bcc-common.toml" | Select-String 'modpackVersion') -split '"')[1]
    "    ledger={0}  bcc={1}  files={2}  journal={3}" -f $l.installed_version, $bcc,
        (Get-ChildItem $Box -Recurse -File | Measure-Object).Count,
        (Test-Path "$Box\.bamfos-updater\journal.json")
}

Write-Output "########## 1. a client from nothing -> $From"
$box = New-Box -Name "lifecycle" -Tag $From
Invoke-Boot -Box $box
State $box

Write-Output ""
Write-Output "########## 2. a converged boot does nothing (Law 1: this is nearly every launch)"
Invoke-Boot -Box $box

Write-Output ""
Write-Output "########## 3. update $From -> $To"
Pin $box $To
Invoke-Boot -Box $box
State $box

Write-Output ""
Write-Output "########## 4. roll it back $To -> $From"
Pin $box $From
Invoke-Boot -Box $box
State $box

Write-Output ""
Write-Output "########## 5. a managed mod vanishes (rule A' restores it with no version change)"
$victim = Get-ChildItem "$box\mods" -Filter *.jar | Where-Object { $_.Name -notlike "*updater*" } |
          Sort-Object Length | Select-Object -First 1
$size = $victim.Length; $name = $victim.Name
Remove-Item $victim.FullName
Invoke-Boot -Box $box
$back = Test-Path (Join-Path "$box\mods" $name)
Write-Output ("    $name restored: $back, same size: " +
    $(if ($back) { (Get-Item (Join-Path "$box\mods" $name)).Length -eq $size } else { "n/a" }))

Write-Output ""
Write-Output "########## 6. the ledger is deleted (re-derive from disk rather than give up)"
Remove-Item "$box\.bamfos-updater\ledger.json"
Invoke-Boot -Box $box
State $box

if (-not $SkipServer) {
    Write-Output ""
    Write-Output "########## 7. a SERVER from nothing -> $To"
    $srv = New-Box -Name "lifecycle-server" -Tag $To
    Invoke-Boot -Box $srv -Side server
    $l = Get-Content "$srv\.bamfos-updater\ledger.json" -Raw | ConvertFrom-Json
    Write-Output ("    ledger entries: " + ($l.files.PSObject.Properties | Measure-Object).Count + " at " + $l.installed_version)
    Write-Output ("    watchdog in mods/: " + (Test-Path "$srv\mods\bamfos-updater-watchdog-0.1.0.jar"))
    Write-Output ("    shim at root     : " + (Test-Path "$srv\bamfos-updater-boot.jar"))
}
