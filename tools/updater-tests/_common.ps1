# Shared helpers for updater test harnesses. Throwaway installs go in gitignored scratch/ so nothing writes into tools/.

$ErrorActionPreference = "Stop"

$Builder = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Pack    = if ($env:BAMFOS_PACK_DIR) { $env:BAMFOS_PACK_DIR } else { Join-Path (Split-Path $Builder -Parent) "bamfos-pack" }
$Boxes   = Join-Path $Builder "scratch\updater-boxes"
$Shim    = Join-Path $Pack "drop\boot\bamfos-updater-boot-0.1.0.jar"
$Java    = (Get-Command java).Source

New-Item -ItemType Directory $Boxes -Force | Out-Null

# Make sure we're testing the split stub and not a stale pre-split shim (both exit 0, so a leftover .new file
# can silently swap the old shim back in). Set $AllowPreSplitShim = $true to override.
$AllowPreSplitShim = $false

Add-Type -AssemblyName System.IO.Compression.FileSystem

# Tells split stub vs legacy shim apart from its jar entries (bundled floor core vs old EngineLoader).
function Get-ShimKind {
    param([string]$Jar)
    if (-not (Test-Path $Jar)) { return "missing" }
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
        try { $names = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
    } catch { return "unreadable" }
    if ($names -contains "com/retro/bamfos/updater/boot/EngineLoader.class") { return "pre-split" }
    if ($names -contains "baseline/bamfos-updater-core.jar") { return "split" }
    "unknown"
}

function Require-SplitShim {
    param([string]$Jar = $Shim)
    if ($AllowPreSplitShim) { return }
    $kind = Get-ShimKind $Jar
    if ($kind -ne "split") {
        throw ("$Jar is a '$kind' shim, not the split build, so this run would exercise the pre-split path " +
               "and pass. Rebuild the jars and restage drop/, or set `$AllowPreSplitShim = `$true if you " +
               "meant the old shim. Split = carries baseline/bamfos-updater-core.jar and no EngineLoader.class.")
    }
}

function Require-Shim {
    if (-not (Test-Path $Shim)) {
        throw "no shim at $Shim. Build the jars and restage drop/ first, or these test the wrong bytes."
    }
    Require-SplitShim $Shim
}

# Fresh test box with just the shim and properties. No token on purpose so we actually test the embedded token.
function New-Box {
    param([string]$Name, [string]$Tag, [switch]$Keep)
    Require-Shim
    $box = Join-Path $Boxes $Name
    if ((Test-Path $box) -and -not $Keep) { [System.IO.Directory]::Delete($box, $true) }
    New-Item -ItemType Directory $box, "$box\.bamfos-updater", "$box\mods" -Force | Out-Null
    Copy-Item $Shim "$box\bamfos-updater-boot.jar" -Force
    "repo=SixxHands/bamfos-pack`ntag=$Tag" | Out-File "$box\.bamfos-updater\bamfos-updater.properties" -Encoding ascii
    $box
}

# One boot of the real shim, the way the wrapper or the FML locator would.
function Invoke-Boot {
    param([string]$Box, [string]$Side = "client", [string]$Filter = "updated|repaired|current|integrity|interrupted|no carrier|rate limited|no release|synced")
    # check the jar inside the box itself, a wrapper cycle may have swapped it since New-Box copied it
    Require-SplitShim (Join-Path $Box "bamfos-updater-boot.jar")
    Push-Location $Box
    try {
        $t0 = Get-Date
        $out = & $Java -cp "bamfos-updater-boot.jar" com.retro.bamfos.updater.boot.ServerMain "--$Side" --dir . 2>&1
        $code = $LASTEXITCODE
        $out | Where-Object { $_ -match $Filter } | ForEach-Object { "    $_" }
        "    -> exit $code in $([int]((Get-Date) - $t0).TotalSeconds)s"
    } finally { Pop-Location }
}

# Drives a BIOS op through the real reflective gateway. Compiled into scratch, never into tools.
function Invoke-BiosOp {
    param([string]$Box, [string]$Op)
    Require-Shim
    $out = Join-Path $Boxes "_harness"
    New-Item -ItemType Directory $out -Force | Out-Null
    & javac -cp $Shim -d $out (Join-Path $PSScriptRoot "Bios.java") 2>&1 | Out-Null
    Push-Location $Box
    try { & $Java -cp "bamfos-updater-boot.jar;$out" Bios $Op 2>&1 | Select-Object -Last 3 }
    finally { Pop-Location }
}

# path -> sha256 for every file under a root, so a scenario can say exactly what moved and what did not.
function Get-Fingerprint {
    param([string]$Root)
    $h = @{}
    Get-ChildItem $Root -Recurse -File | ForEach-Object {
        $h[$_.FullName.Substring($Root.Length + 1)] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    }
    $h
}

# The comparison every scenario ends on. `Expect` is a name -> relative-path map of what was damaged.
function Show-Diff {
    param([hashtable]$Before, [hashtable]$After, $Expect, [string]$Label)
    Write-Output ""
    Write-Output "  == $Label"
    $ok = 0
    foreach ($k in ($Expect.Keys | Sort-Object)) {
        $p = $Expect[$k]
        if (-not $Before.ContainsKey($p)) { Write-Output "    ?? $p was not in the clean box"; continue }
        $good = $Before[$p] -eq $After[$p]
        if ($good) { $ok++ }
        "    {0,-30} {1,-48} {2}" -f $k, $p, $(if ($good) { "restored" } else { "still wrong" })
    }
    Write-Output "    --> $ok of $($Expect.Count) back to the shipped bytes"
    $drift = @($Before.Keys | Where-Object { $Before[$_] -ne $After[$_] -and $_ -notmatch "bamfos-updater" })
    Write-Output ("    pack files differing from clean: " + $drift.Count + " of " + $Before.Count)
    $drift | Sort-Object | Select-Object -First 15 | ForEach-Object { "        $_" }
}
