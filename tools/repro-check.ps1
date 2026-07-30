# Builds the five shipped jars TWICE and fails if any of them changed.
#
# This exists because bamfmenu#6 cost nothing visible: the jars were byte-different on every rebuild, the
# release tool read that as new content, and 77MB of early window went up the wire again on a build where
# nobody had touched a line. Nothing failed, nothing warned, the only symptom was upload time. So the guard
# has to be a thing somebody RUNS, not a thing somebody notices.
#
# usage:  powershell -File tools\repro-check.ps1
# exit:   0 = both builds hashed the same, 1 = something is nondeterministic again
#
# On a hit, the report names the entries that moved and whether it was their CONTENT (crc) or just their
# TIMESTAMP, because those are two completely different bugs: a crc change is a real code change and this script
# is telling you the truth, a timestamp change means the archive settings in the root build.gradle stopped
# reaching that task.

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$JARS = @(
    "build\libs\bamfmenu-1.0.0.jar",
    "earlywindow\build\libs\bamfmenu-earlywindow-1.0.0.jar",
    "bamfos-updater-boot\build\libs\bamfos-updater-boot-0.1.0.jar",
    "bamfos-updater-engine\build\libs\bamfos-updater-engine-0.1.0.jar",
    "bamfos-updater-watchdog\build\libs\bamfos-updater-watchdog-0.1.0.jar"
)

function Build-All {
    # Use --rerun-tasks instead of clean so we verify warm incremental rebuilds stay deterministic.
    & "$root\gradlew.bat" jar :earlywindow:jar :bamfos-updater-boot:jar :bamfos-updater-engine:jar `
        :bamfos-updater-watchdog:jar --rerun-tasks --console=plain -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED"; exit 1 }
}

function Hash-All {
    $h = @{}
    foreach ($j in $JARS) {
        if (-not (Test-Path $j)) { Write-Output "MISSING $j"; exit 1 }
        $h[$j] = (Get-FileHash $j -Algorithm SHA256).Hash
    }
    $h
}

Write-Output "build 1 of 2 ..."
Build-All
$a = Hash-All
$keep = Join-Path ([System.IO.Path]::GetTempPath()) ("repro-check-" + [System.Diagnostics.Process]::GetCurrentProcess().Id)
New-Item -ItemType Directory $keep -Force | Out-Null
foreach ($j in $JARS) { Copy-Item $j (Join-Path $keep (Split-Path $j -Leaf)) -Force }

Write-Output "build 2 of 2 ..."
Build-All
$b = Hash-All

Add-Type -AssemblyName System.IO.Compression.FileSystem
$bad = 0
foreach ($j in $JARS) {
    $name = Split-Path $j -Leaf
    if ($a[$j] -eq $b[$j]) {
        Write-Output ("  same      {0}" -f $name)
        continue
    }
    $bad++
    Write-Output ("  CHANGED   {0}" -f $name)
    # print what changed, what entry because "the hash is different" doesn't help and solo <whatever> number tells me nothing and Idk how hashes work anyways
    # Hash entry bytes directly instead of checking ZipArchiveEntry.Crc32 (PowerShell 5.1's .NET Framework doesn't have Crc32 so it always evaluates null).
    function EntryMap($path) {
        $z = [System.IO.Compression.ZipFile]::OpenRead($path)
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $m = @{}
        try {
            foreach ($e in $z.Entries) {
                $s = $e.Open()
                $ms = New-Object System.IO.MemoryStream
                try { $s.CopyTo($ms); $h = [System.BitConverter]::ToString($sha.ComputeHash($ms.ToArray())) }
                finally { $s.Dispose(); $ms.Dispose() }
                $m[$e.FullName] = @{ sha = $h; time = $e.LastWriteTime; len = $e.Length }
            }
        } finally { $z.Dispose(); $sha.Dispose() }
        $m
    }
    $ea = EntryMap (Join-Path $keep $name)
    $eb = EntryMap (Join-Path $root $j)
    $crc = 0; $time = 0; $only = 0
    $shown = 0
    foreach ($k in $eb.Keys) {
        $o = $ea[$k]
        if (-not $o) { $only++; continue }
        if ($o.sha -ne $eb[$k].sha) {
            $crc++
            if ($shown -lt 5) { Write-Output ("      content moved : {0}" -f $k); $shown++ }
        } elseif ($o.time -ne $eb[$k].time) {
            $time++
            if ($shown -lt 5) { Write-Output ("      TIMESTAMP moved: {0}" -f $k); $shown++ }
        }
    }
    Write-Output ("      {0} content, {1} timestamp, {2} entries only in the second build" -f $crc, $time, $only)
    if ($time -gt 0 -and $crc -eq 0) {
        Write-Output "      timestamps only, so the archive block in the root build.gradle is not reaching this task"
    }
}

Remove-Item $keep -Recurse -Force -ErrorAction SilentlyContinue
if ($bad -gt 0) { Write-Output ""; Write-Output "$bad of $($JARS.Count) jars are not reproducible"; exit 1 }
Write-Output ""
Write-Output "all $($JARS.Count) jars reproducible"
exit 0
