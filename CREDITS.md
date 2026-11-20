# Credits and Third-Party Licenses

Code in this repository is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). Separate third-party data files listed below keep their own licenses and are not relicensed under GPL-3.0.

## DOOM Engine and Shareware WAD

- **Mocha Doom (`tools/doom-spike/`, `earlywindow/libs/mochadoom.jar`)**: GNU GPLv3. Originally based on Doom by id Software (1993-1996), ported to Java by Velktron / Maes and maintained in `vanilla-mocha-doom` by Gabor Bata. Full license text is in `earlywindow/src/main/resources/doom/MOCHADOOM-LICENSE.txt` and `tools/doom-spike/LICENSE.TXT`.
- **Doom 1 Shareware WAD (`earlywindow/src/main/resources/doom/doom1.wad`)**: Copyright id Software, Inc. Freely distributable shareware data under the terms in `earlywindow/src/main/resources/doom/doom-wad-shareware-license.txt`; non-commercial only, separate from the GPLv3 code.

## Bundled Fonts (`earlywindow/src/main/resources/fonts/`)

| Font | File | License |
|---|---|---|
| GohuFont (active default) | `gohufont.ttf` | WTFPL (Hugo Chargois, converted by Guilherme Maeda) |
| Cousine | `cousine.ttf` | Apache-2.0 (Google / Ascender) |
| DejaVu Sans Mono | `dejavu-sans-mono.ttf` | Bitstream Vera / Public Domain |
| Fira Mono | `fira-mono.ttf` | SIL OFL 1.1 (Mozilla / Telefonica) |
| Hack | `hack.ttf` | MIT + Bitstream Vera (Christopher Simpkins) |
| Iosevka | `iosevka.ttf` | SIL OFL 1.1 (Belleve Invis) |
| Mensch | `mensch.ttf` | Bitstream Vera derived (Robey Pointer) |
| Source Code Pro | `source-code-pro.ttf` | SIL OFL 1.1 (Adobe) |
| Sudo | `sudo.ttf` | SIL OFL 1.1 (Jens Kutilek) |
| Terminus | `terminus.ttf` | GPL-2.0 (Dimitar Zhekov / Eric Cheng) |

## Bundled Music

- **Autumonapia** ([YouTube](https://www.youtube.com/@autumnonapia), [Spotify](https://open.spotify.com/artist/32UHXbkj2LMKdTs0aE1HUg)): `FAR` (`far.ogg`) and `B-0000B1-ES` (`B-0000B1-ES.ogg`), used with permission. These recordings remain under their original rights and are separate from the GPLv3 code.
- **Parkerman1700** (Newgrounds Audio Portal): `tetris1.ogg` ([Tetris'd](https://www.newgrounds.com/audio/listen/634053)), `tetris2.ogg` ([Tetris Remix Song B [Final]](https://www.newgrounds.com/audio/listen/299881)), `tetris3.ogg` ([Tetris Remix [Revamped]](https://www.newgrounds.com/audio/listen/263998)), and `tetris4.ogg` ([Tetris Remix [Final]](https://www.newgrounds.com/audio/listen/162764)). Non-commercial, no derivatives; separate from the GPLv3 code.

## Adding Extra Local Music

Commercial or personal music tracks are not tracked in this repository. You can add them locally in one of the following:

- **Runtime (rhythm songs and osu!mania):** Drop song folders (`song.json`, `.ogg`, `.chart`), `.osz` beatmaps, or extracted osu!mania folders directly into `<gameDir>/BAMFOS/songs/`. Dropped `.osz` files convert automatically when Select Song or the Chart Editor opens.
- **Runtime (Tetris BYOM):** Drop any `.ogg` files into `<gameDir>/BAMFOS/tetris/` to add them to the Tetris jukebox alongside the bundled tracks. The mod shuffles songs in that folder durring gameplay.
- **Build-time:** Place files under `earlywindow/local-resources/` using the same layout as `earlywindow/src/main/resources/` (for example `earlywindow/local-resources/songs/tetris0.ogg` or `earlywindow/local-resources/songs/<id>/`). That folder is gitignored, however local Gradle builds package anything inside it automatically.
