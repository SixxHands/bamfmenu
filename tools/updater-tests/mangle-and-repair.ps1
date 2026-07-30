# Tests hash-based repair on missing and subtly corrupted files (same size, zeroed bytes in the middle) without re-downloading the whole pack.

param([string]$Tag = "v0.0.7-test")
. "$PSScriptRoot\_common.ps1"

Write-Output "########## a clean box at $Tag"
$box = New-Box -Name "mangle" -Tag $Tag
Invoke-Boot -Box $box
$clean = Get-Fingerprint $box

$mods = Join-Path $box "mods"
$jars = Get-ChildItem $mods -Filter *.jar | Where-Object { $_.Name -notlike "*updater*" } | Sort-Object Length
$damage = [ordered]@{}

# the five shapes a half-written file actually takes, smallest jars first so the re-fetch stays quick
$f = $jars[0].FullName; $damage["truncated to 0 bytes"] = "mods\" + $jars[0].Name
[System.IO.File]::WriteAllBytes($f, @())

$f = $jars[1].FullName; $damage["empty file, jar name"] = "mods\" + $jars[1].Name
[System.IO.File]::WriteAllText($f, "")

$f = $jars[2].FullName; $damage["text, jar name"] = "mods\" + $jars[2].Name
[System.IO.File]::WriteAllText($f, "this was never a jar")

# THE NASTY ONE. Same name, same byte count, eight bytes zeroed in the middle. Nothing but a hash sees it.
$f = $jars[3].FullName; $damage["same size, bytes flipped"] = "mods\" + $jars[3].Name
$b = [System.IO.File]::ReadAllBytes($f)
for ($i = 0; $i -lt 8; $i++) { $b[[int]($b.Length / 2) + $i] = 0x00 }
[System.IO.File]::WriteAllBytes($f, $b)

$cfg = Get-ChildItem (Join-Path $box "config") -Recurse -File | Where-Object { $_.Length -gt 400 } | Select-Object -First 1
$damage["config half written"] = $cfg.FullName.Substring($box.Length + 1)
$cb = [System.IO.File]::ReadAllBytes($cfg.FullName)
[System.IO.File]::WriteAllBytes($cfg.FullName, $cb[0..([int]($cb.Length / 3))])

# the control: a file that is simply missing, which an ordinary boot already handles, and
# having it in the set is what makes the others' "still wrong" mean something
$gone = $jars[-1]; $damage["deleted outright"] = "mods\" + $gone.Name
Remove-Item $gone.FullName

# theirs, not ours. Jar-named junk in mods/ is the case most likely to get eaten by an over-eager repair
[System.IO.File]::WriteAllText((Join-Path $mods "blank.jar"), "")
[System.IO.File]::WriteAllText((Join-Path $mods "notamod.jar"), "random junk")
[System.IO.File]::WriteAllText((Join-Path $mods "leftover.txt"), "notes")
New-Item -ItemType Directory (Join-Path $box "saves\MyWorld") -Force | Out-Null
"IRREPLACEABLE" | Out-File (Join-Path $box "saves\MyWorld\level.dat") -Encoding ascii

Write-Output ""
Write-Output "########## damage"
$damage.GetEnumerator() | ForEach-Object { "    {0,-30} {1}" -f $_.Key, $_.Value }
Write-Output "    plus mods\blank.jar, mods\notamod.jar, mods\leftover.txt and a world, all theirs"

Write-Output ""
Write-Output "########## an ORDINARY boot"
Invoke-Boot -Box $box
Show-Diff -Before $clean -After (Get-Fingerprint $box) -Expect $damage -Label "after an ordinary boot"

Write-Output ""
Write-Output "########## REPAIR, through the gateway the UPDATE AND REPAIR row calls"
Invoke-BiosOp -Box $box -Op "repair" | ForEach-Object { "    $_" }

# Repair stages and journals changes while jars are open, so run one boot afterward for step 0 to apply them.
Write-Output ""
Write-Output "########## the next boot, where step 0 applies it"
Invoke-Boot -Box $box
$after = Get-Fingerprint $box
Show-Diff -Before $clean -After $after -Expect $damage -Label "after repair + apply"

Write-Output ""
Write-Output "########## blast radius"
$added = @($after.Keys | Where-Object { -not $clean.ContainsKey($_) -and $_ -notmatch "bamfos-updater" })
Write-Output ("    pack files       : " + $clean.Count)
Write-Output ("    added by the test: " + $added.Count + " (their junk and the world)")
Write-Output ("    journal cleared  : " + (-not (Test-Path (Join-Path $box ".bamfos-updater\journal.json"))))
Write-Output ""
Write-Output ("    world intact     : " + ((Get-Content (Join-Path $box "saves\MyWorld\level.dat") -Raw).Trim()))
foreach ($j in @("mods\blank.jar", "mods\notamod.jar", "mods\leftover.txt")) {
    Write-Output ("    their " + $j.PadRight(20) + ": " + $(if (Test-Path (Join-Path $box $j)) { "survived" } else { "DELETED, Law 2 broke" }))
}
