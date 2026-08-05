# End-to-end factory reset test: checks wipe vs protected files, interrupted download resume, and clean boot after reset.

param([string]$Tag = "v0.0.8-test")
. "$PSScriptRoot\_common.ps1"

$harness = Join-Path $Boxes "_harness"
New-Item -ItemType Directory $harness -Force | Out-Null
& javac -cp $Shim -d $harness "$PSScriptRoot\Bios.java" 2>&1 | Out-Null
if (-not (Test-Path "$harness\Bios.class")) { throw "Bios.java did not compile" }

# Keep the classpath quoted so paths with spaces do not split -cp arguments.
function Invoke-Reset {
    param([string]$Box)
    Push-Location $Box
    try { & $Java -cp "bamfos-updater-boot.jar;$harness" Bios factoryReset 2>&1 | Select-Object -Last 1 }
    finally { Pop-Location }
}

Write-Output "########## a clean client box at $Tag"
$box = New-Box -Name "factory" -Tag $Tag
Invoke-Boot -Box $box
$clean = Get-Fingerprint $box
$packFiles = @($clean.Keys | Where-Object { $_ -notmatch "bamfos-updater" })
Write-Output ("    pack files: " + $packFiles.Count)

Write-Output ""
Write-Output "########## make it lived-in"
New-Item -ItemType Directory "$box\mods\myfolder", "$box\resourcepacks", "$box\saves\MyWorld", "$box\screenshots" -Force | Out-Null
"mine"        | Out-File "$box\mods\i-added-this.jar" -Encoding ascii
"also mine"   | Out-File "$box\mods\myfolder\my-own-mod.jar" -Encoding ascii
"keep me"     | Out-File "$box\resourcepacks\my-pack.zip" -Encoding ascii
"IRREPLACEABLE" | Out-File "$box\saves\MyWorld\level.dat" -Encoding ascii
"pic"         | Out-File "$box\screenshots\shot.png" -Encoding ascii
"fov:90"      | Out-File "$box\options.txt" -Encoding ascii
$victim = Get-ChildItem "$box\config" -Recurse -File | Where-Object { $_.Length -gt 200 } | Select-Object -First 1
$victimRel = $victim.FullName.Substring($box.Length + 1)
"# I EDITED THIS" | Add-Content $victim.FullName
Write-Output ("    two mods of theirs, a pack of theirs, a world, a screenshot, options.txt, and edited " + $victimRel)

Write-Output ""
Write-Output "########## arm it"
$t0 = Get-Date
$armed = Invoke-Reset -Box $box
Write-Output ("    " + $armed)
Write-Output ("    -> $([int]((Get-Date) - $t0).TotalSeconds)s")
if (-not (Test-Path "$box\.bamfos-updater\journal.json")) { throw "nothing armed, so there is nothing to test" }
$j = Get-Content "$box\.bamfos-updater\journal.json" -Raw | ConvertFrom-Json
Write-Output "    the journal's deletes, which is everything the wipe found that a move does NOT replace:"
$j.ops | Where-Object { $_.kind -eq "delete" } | ForEach-Object { "        $($_.to)" }

Write-Output ""
Write-Output "########## the retry: drop the journal, delete 100 parts, truncate one, arm again"
$staging = "$box\.bamfos-updater\staging"
Remove-Item "$box\.bamfos-updater\journal.json" -Force
$parts = Get-ChildItem $staging -Filter "*-fr*.part" | Sort-Object Name
$parts | Select-Object -First 100 | Remove-Item -Force
# a part with the right NAME and short content, which is what a kill mid-write leaves. Only the hash sees it,
# and reusing it would put bad bytes on disk with the journal vouching for them
$short = $parts | Select-Object -Skip 100 -First 1
$fs = [System.IO.File]::Open($short.FullName, "Open", "Write")
$fs.SetLength([math]::Max(1, $fs.Length - 64)); $fs.Dispose()
# both halves of the sweep in one go: ours from an older release must go, an ordinary staged sync's must not
"junk"      | Out-File "$staging\deadbeef-fr9999.part" -Encoding ascii
"not mine"  | Out-File "$staging\cafebabe.part" -Encoding ascii
Write-Output ("    " + $parts.Count + " staged, deleted 100, truncated 1, planted 2 strays")

# Run a normal boot between the failed reset and retry to make sure Reconciler.pruneStaging doesn't delete our -fr .part resume files.
Write-Output ""
Write-Output "########## a boot in between, which is what a real friend does, and what pruneStaging runs on"
$before = @(Get-ChildItem $staging -Filter "*-fr*.part").Count
Invoke-Boot -Box $box -Filter "current|updated|repaired|interrupted"
$after = @(Get-ChildItem $staging -Filter "*-fr*.part" -EA SilentlyContinue).Count
Write-Output ("    -fr parts before the boot: " + $before + "   after: " + $after)
Write-Output ("    the sweep left the resume state alone: " +
              $(if ($after -eq $before) { "yes" } else { "NO, " + ($before - $after) + " eaten, resume is dead" }))

$t0 = Get-Date
$again = Invoke-Reset -Box $box
Write-Output ("    " + $again)
Write-Output ("    -> $([int]((Get-Date) - $t0).TotalSeconds)s, and the first arm was the full download")
$want = $parts.Count - 101
$got = if ($again -match "reused=(\d+)") { [int]$Matches[1] } else { -1 }
Write-Output ("    reused " + $got + ", wanted " + $want + " (everything but the 100 gone and the 1 short) -> " +
              $(if ($got -eq $want) { "right" } else { "WRONG" }))
Write-Output ("    an older release's stray part swept : " + (-not (Test-Path "$staging\deadbeef-fr9999.part")))
Write-Output ("    an ordinary sync's part left alone  : " + (Test-Path "$staging\cafebabe.part"))

Write-Output ""
Write-Output "########## the next boot, where step 0 does the whole thing from local bytes"
# Expect bamfos-updater-boot.jar.new staged here since New-Box puts the shim at server root instead of mods/.
Invoke-Boot -Box $box -Filter "updater|interrupted|repaired|current"
$bus = Get-Content "$box\.bamfos-updater\bus.log" -ErrorAction SilentlyContinue
Write-Output "    the events the boot terminal drew:"
$bus | Where-Object { $_ -match "^(APPLY|ADONE)" } | ForEach-Object { "        $_" }
foreach ($ph in @("remove", "install", "verify")) {
    # rows, NOT ops: BootBus throttles to one row per percent per phase, so ~100 is a full phase and 0 is a
    # phase that never reported
    Write-Output ("        ASTEP " + $ph.PadRight(8) + " rows: " + @($bus | Where-Object { $_ -match "ASTEP" -and $_ -match $ph }).Count)
}

Write-Output ""
Write-Output "########## what survived and what did not"
$mustGo   = @("mods\i-added-this.jar", "mods\myfolder\my-own-mod.jar", "options.txt")
$mustStay = @("resourcepacks\my-pack.zip", "saves\MyWorld\level.dat", "screenshots\shot.png",
              "bamfos-updater-boot.jar", "servers.dat")
foreach ($p in $mustGo) {
    "    {0,-38} {1}" -f $p, $(if (Test-Path (Join-Path $box $p)) { "STILL THERE, the wipe missed it" } else { "gone, right" })
}
foreach ($p in $mustStay) {
    "    {0,-38} {1}" -f $p, $(if (Test-Path (Join-Path $box $p)) { "survived, right" } else { "DELETED, Law 2 broke" })
}
$stillEdited = (Get-Content (Join-Path $box $victimRel) -Raw).Contains("# I EDITED THIS")
Write-Output ("    {0,-38} {1}" -f $victimRel, $(if ($stillEdited) { "STILL EDITED, a managed file did not go back" } else { "back to release bytes, right" }))

Write-Output ""
Write-Output "########## did it converge"
$after = Get-Fingerprint $box
$wrong = @(); $missing = @()
foreach ($k in $packFiles) {
    if (-not $after.ContainsKey($k)) { $missing += $k } elseif ($after[$k] -ne $clean[$k]) { $wrong += $k }
}
Write-Output ("    pack files checked: " + $packFiles.Count + "   wrong bytes: " + $wrong.Count + "   missing: " + $missing.Count)
($wrong + $missing) | Select-Object -First 12 | ForEach-Object { "        $_" }
Write-Output ("    journal cleared: " + (-not (Test-Path "$box\.bamfos-updater\journal.json")))
Write-Output ""
Write-Output "########## and the boot after that, which has to be quiet"
Invoke-Boot -Box $box -Filter "current|updated|repaired|interrupted"
