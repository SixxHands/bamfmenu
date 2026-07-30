# tools/updater-tests, the harnesses that drive a real install

*Reviewed against the scripts. Update it when one changes.*

The unit suite proves the code. These prove the RELEASE: real built jars, real GitHub, a real 800MB instance
on disk. That is a different question, and for a long time nobody had asked it. Everything here was written
after a night of finding out what happens when you do.

Boxes go in `scratch/updater-boxes/`, which is gitignored, because each one is the whole pack and weighs about
750MB. Nothing here writes into `tools/`, compiled `.class` files included: there is no `*.class` rule in
`.gitignore` and a stray one would ride into a commit without a word.

## What each one answers

| script | question | network | time |
|---|---|---|---|
| `offline-boot.ps1` | does a box with no wifi still boot, and does step 0 finish an interrupted apply from local bytes | no | seconds |
| `mangle-and-repair.ps1` | can it fix files that are present and WRONG, and does asking for a repair re-download everything | yes | ~2 min |
| `lifecycle.ps1` | install from nothing, update, roll back, restore a deleted mod, rebuild a lost ledger, stand up a server | yes | ~5 min |
| `Bios.java` | drives one BIOS op headlessly, which is the only way to reach client-side repair | depends on the op | - |

`offline-boot.ps1` is the cheap one and it exits non-zero on failure, so it is the one to run after touching
the shim, EngineLoader, the journal or step 0. The other two print a report and want reading.

## Things worth knowing before you read a result

**A repair is two boots.** It runs mid-session and cannot overwrite a jar FML has open, so it downloads,
stages, journals, and says `repaired N file(s), some apply on next boot`. Step 0 on the next boot does the
moves. Stopping after the repair looks exactly like a repair that failed.

**An ordinary boot does not fix a file that is present and mangled.** It restores files that are MISSING
(rule A'), and it trusts the ledger for everything else, otherwise every launch would hash the whole install.
A crash that leaves half-written jars therefore boots straight past them saying `pack current, 0 actions`, and
somebody has to open UPDATE AND REPAIR. Measured, not assumed: five kinds of damage, one of six fixed by a
boot, six of six after a repair.

**Repair is surgical.** On a 596 file pack with five files damaged it fetched five, in four seconds. If that
number ever comes back near 596, something upstream started moving bytes that no edit moved, and
`tools\repro-check.ps1` is the thing that answers why.

**The measurement is the hard part, not the damage.** Two of these harnesses shipped wrong the first time: one
compared bare filenames against path keys so every check passed on `null -eq null`, and one never ran the op
at all because it asked the wrong classloader for `OpsGateway` and the crash looked like a clean zero. If a
result looks too good, check the keys and check the op actually ran.

## Running them

```
powershell -File tools\updater-tests\offline-boot.ps1
powershell -File tools\updater-tests\mangle-and-repair.ps1 -Tag v0.0.7-test
powershell -File tools\updater-tests\lifecycle.ps1 -From v0.0.5-test -To v0.0.6-test
```

They read the shim out of `bamfos-pack/drop/boot/`, so build and restage first or they test last week's bytes.
The tag has to be a release that actually exists; `gh release list --repo SixxHands/bamfos-pack` says which.

Cleaning up is `Remove-Item scratch\updater-boxes -Recurse -Force`. It adds up fast.
