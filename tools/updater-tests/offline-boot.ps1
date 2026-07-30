# Offline boot test: verifies baseline engine extract, local journal replay with no network, and clean exit 0.
# Usage: powershell -File tools\updater-tests\offline-boot.ps1
#
# Needs NO network. Takes a couple of seconds. This is the cheapest end to end proof in the set, so it is the
# one to run after touching the shim, EngineLoader, the journal or step 0.

. "$PSScriptRoot\_common.ps1"
Require-Shim

$box = Join-Path $Boxes "offline"
if (Test-Path $box) { [System.IO.Directory]::Delete($box, $true) }
New-Item -ItemType Directory $box, "$box\.bamfos-updater\staging", "$box\mods", "$box\config" -Force | Out-Null
Copy-Item $Shim "$box\bamfos-updater-boot.jar"
# a repo that cannot resolve AND no token, so nothing here can accidentally reach github
"repo=SixxHands/does-not-exist-offline-probe`ngithub_token=" |
    Out-File "$box\.bamfos-updater\bamfos-updater.properties" -Encoding ascii

# a journal shaped like the one a factory reset arms: deletes first, then the placements
$bytes = [System.Text.Encoding]::UTF8.GetBytes("the new file")
[System.IO.File]::WriteAllBytes("$box\.bamfos-updater\staging\p0.part", $bytes)
[System.IO.File]::WriteAllText("$box\config\doomed.json", "the config being wiped")
$sha = ([System.BitConverter]::ToString(
    (New-Object System.Security.Cryptography.SHA256Managed).ComputeHash($bytes)) -replace '-','').ToLower()
[System.IO.File]::WriteAllText("$box\.bamfos-updater\journal.json", @"
{"target_version":"v0.2.6","ops":[
{"kind":"delete","from":"","to":"config/doomed.json","sha256":""},
{"kind":"move","from":".bamfos-updater/staging/p0.part","to":"config/fresh.json","sha256":"$sha"}]}
"@)

Write-Output "########## a boot with no network and a journal waiting"
Push-Location $box
try {
    & $Java -cp "bamfos-updater-boot.jar" com.retro.bamfos.updater.boot.ServerMain --server --dir . 2>&1 |
        ForEach-Object { "    $_" }
    $code = $LASTEXITCODE
} finally { Pop-Location }

Write-Output ""
Write-Output "########## what it did"
$checks = [ordered]@{
    "exited 0 so the game still boots"        = ($code -eq 0)
    "baseline engine extracted"               = (Test-Path "$box\.bamfos-updater\engine\bamfos-updater-engine.jar")
    "step 0 ran the delete"                   = (-not (Test-Path "$box\config\doomed.json"))
    "step 0 ran the move"                     = (Test-Path "$box\config\fresh.json")
    "placed bytes are right"                  = ((Test-Path "$box\config\fresh.json") -and
                                                 (Get-Content "$box\config\fresh.json" -Raw) -eq "the new file")
    "journal cleared, nothing left stuck"     = (-not (Test-Path "$box\.bamfos-updater\journal.json"))
}
$bad = 0
foreach ($k in $checks.Keys) {
    if (-not $checks[$k]) { $bad++ }
    "    {0,-40} {1}" -f $k, $(if ($checks[$k]) { "yes" } else { "NO" })
}
Write-Output ""
if ($bad -gt 0) { Write-Output "  $bad of $($checks.Count) checks failed"; exit 1 }
Write-Output "  all $($checks.Count) checks passed"
exit 0
