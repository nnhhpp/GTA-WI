# GTA WI — "What if 8-Ball drove?"

A top-down survival demake of the first mission of **Grand Theft Auto III**, for J2ME phones
(MIDP 2.0 / CLDC 1.1, 240×320). Made for and tested on a **Nokia N95 8GB**.

What if 8-Ball insisted on driving, despite messed up hands? 
Kuruma ends up wrecked and the cops think *you* blew up the bridge.

**No game files are included.** You build the game yourself from your own PC copy of GTA III.
The build script downloads the free tools it needs; see [BUILDING.md](BUILDING.md).

## The game

- **Waves = wanted stars.** Wave 1 starts at 2 stars; every wave adds one, up to 6. The 6-star wave never ends.
- **To clear a wave,** survive its timer *and* take down its quota of cops. Miss the quota and it's overtime:
  they keep coming, faster and faster.
- Run over cops and gang members in any vehicle to pick up ammo for the gun in your hand.
- **Weapon drops:** from wave 2, two random guns are stashed on the map each wave. You can grab only one.
- **The heat:**
  - Cops carry the wave's gun: pistols, shotguns, SWAT uzis, FBI AKs, army M16s.
  - From wave 3 a police chopper hunts you with its searchlight.
  - From wave 4 they set up roadblocks on the road ahead.
- Every road out of the map is shut by a police roadblock.
- Cars burn and explode in chain reactions.

## Controls (phone keypad)

| | On foot | In a car |
|---|---|---|
| 2 4 6 8 / 1 3 7 9 | move | 2 / 8 gas and brake, 4 / 6 steer |
| 5 | shoot (hold for automatic guns) | horn |
| # | sprint (hold) | handbrake |
| * | next weapon | — |
| 0 | auto theft, grand (hold the button while the bar fills) | get out of the vehicle |
| soft key | pause | pause |

## What's in this repo

| Folder | Description |
|---|---|
| `src\` | The game: Java 1.3 source for MIDP 2.0 phones |
| `map\` | The map: chunk 01 of Portland (the hospital to the Callahan Bridge), hand-built in [Tiled](https://www.mapeditor.org/) |
| `pipeline\` | The build scripts: GTA III files → sprites → phone resources |
| `music\` | Empty: put your own MIDI files here (see `music\README.txt`) |
| `setup.bat`, `build.bat`, `run.bat`, `config.bat` | One-time tool download, build, PC test run, settings |

**Not in this repository:** any GTA III model, texture, font, text or sound. The build reads them from *your*
installed copy of the game, renders the sprites in Blender, and writes everything into `work\` on your PC.
The map's tile art was painted by hand over top-down views of GTA III's Portland, so it is fan art derived
from the original game too.

**Please don't share the jar you build.** It contains material made from your copy of GTA III.
Share this repository instead, and let people build their own.

## Building

Requirements: Windows 10/11 (64-bit), the PC version of GTA III, an internet connection for the one-time setup,
and about 2 GB of free disk space.

1. Run `setup.bat` once. It downloads the build tools into `tools\`.
2. Open `config.bat` and set `GTA3` to your GTA III folder.
3. Optionally, copy your MIDI files into `music\`.
4. Run `build.bat`. You get `dist\GTAWI.jar` and `dist\GTAWI.jad`.
5. Copy both files to your phone, or play on the PC with `run.bat`.

The details are in [BUILDING.md](BUILDING.md).

## License

The code, build scripts and map in this repository are MIT-licensed (see [LICENSE](LICENSE)).
GTA III and everything the build makes from it are not covered: they belong to Rockstar Games.

## Credits

- Claude (Anthropic)
- Built with:
  - [INU Tools](https://github.com/INU-ez/INU_Tools-GTA-Blender) by INU (GPL-3.0): GTA model import for Blender
  - [Blender](https://www.blender.org/) (GPL)
  - [FreeJ2ME-Plus](https://github.com/TASEmulators/freej2me-plus) (GPL-3.0)
  - [ProGuard](https://github.com/Guardsquare/proguard) (GPL-2.0)
  - [Eclipse Temurin](https://adoptium.net/) JDK 8
  - [uv](https://github.com/astral-sh/uv)
- Grand Theft Auto III © Rockstar Games / Take-Two Interactive. This is an unofficial, non-commercial fan project.
  It is not affiliated with or endorsed by Rockstar Games or Take-Two Interactive.
