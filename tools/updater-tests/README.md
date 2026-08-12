# tools/updater-tests

End-to-end harnesses that run the built updater jars against real installs and published releases.

Throwaway installs go in `scratch/updater-boxes/` (gitignored, ~750MB each). Nothing here writes into `tools/`.

## Scripts and harnesses

| File | What it checks | Network | Time |
|---|---|---|---|
| `offline-boot.ps1` | Offline boot, baseline engine extract, local journal replay, clean exit 0 | no | seconds |
| `mangle-and-repair.ps1` | Hash-based repair of missing and subtly corrupted files without re-downloading the whole pack | yes | ~2 min |
| `lifecycle.ps1` | Fresh install, update, rollback, mod restore, ledger rebuild, server sync | yes | ~5 min |
| `factory-reset.ps1` | Wipe vs protected files, interrupted download resume, clean boot after reset | yes | ~6 min |
| `Bios.java` | Runs a BIOS repair op headlessly through `OpsGateway` | depends on op | - |
| `Broadcast.java` | Pings a running server with `Slp` and `BetterStatus` to check live BCC output | no | seconds |
| `LockLadder.java` | Probes which Windows file ops work against jars opened via `zipfs` vs `ZipFile`/`RandomAccessFile` | no | seconds |
| `HotSwap.java` | Offline smoke test of open-file hot swap against the built engine jar (4-file fake instance) | no | seconds |

## Notes

- **Repair is two boots:** mid-session repair stages and journals changes while jars are open, and step 0 applies them on the next boot.
- **Ordinary boots trust the ledger:** normal launches restore missing files and skip full-pack hashing; corrupted files with intact names are fixed via Update and Repair.
- **`HotSwap.java` tests the built jar**, not compiled test classes (`--engine <jar>` or `--stub <jar>`).
- **`factory-reset.ps1` expects `mods/bamfos-updater-boot.jar.new` staged:** `New-Box` puts the shim at server root rather than `mods/`.

## Running

```powershell
powershell -File tools\updater-tests\offline-boot.ps1
powershell -File tools\updater-tests\mangle-and-repair.ps1 -Tag <tag>
powershell -File tools\updater-tests\lifecycle.ps1 -From <tag> -To <tag>
powershell -File tools\updater-tests\factory-reset.ps1 -Tag <tag>
```

Clean up throwaway installs with `Remove-Item scratch\updater-boxes -Recurse -Force`.
