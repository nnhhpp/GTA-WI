# Building GTA WI

The build turns **your** GTA III install into a phone game (sorta). Nothing from the game is in this repository.
Everything is downloaded or generated inside this folder, so deleting the folder removes all of it.

## What you need

- **Windows 10 or 11, 64-bit.**
- **GTA III for PC:** Steam, the original retail CD, or the Rockstar Games Launcher version.
  Any folder with `gta3.exe`, `models\gta3.img` and `models\gta3.dir` in it works.

- **About 2 GB of free disk space**, most of it for Blender.
- **A graphics card that runs Blender 5.2** (OpenGL 4.3). The sprites are rendered with EEVEE.
- **An internet connection** for `setup.bat`. After that the build works offline.

## Step by step

### 1. `setup.bat` (once)

Downloads the build tools into `tools\` (about 550 MB):

| Tool | Version | Purpose |
|---|---|---|
| Eclipse Temurin JDK | 8u432 | `javac` that can still target Java 1.3 bytecode for old phones |
| ProGuard | 7.6.1 | shrinks the jar and preverifies it for MIDP |
| uv | latest | runs the Python scripts with its own Python, numpy and Pillow |
| FreeJ2ME-Plus | 1.52 | plays the game on the PC; its MIDP classes are what `javac` compiles against |
| Blender | 5.2.3 | renders the sprites; set up portable, so its settings stay in `tools\` |
| INU Tools | 2.5.4 | Blender extension that imports GTA III `.dff` and `.txd` files |

Running `setup.bat` again only fetches what's missing.

### 2. `config.bat`

Set `GTA3` to your game folder, for example:

```bat
set "GTA3=G:\SteamLibrary\steamapps\common\Grand Theft Auto 3"
```

### 3. Music (optional)

GTA WI ships without music. Put your own MIDI files in `music\`:

- `maintheme.mid`: the main menu
- `track01.mid`, `track02.mid`, …: the car radio, played in random order

### 4. `build.bat`

| Step | Description | Duration |
|---|---|---|
| 1 - Extract | copies the models, HUD, font and menu textures, ped animations, data files and game text it needs from your GTA III folder into `work\gta3\` | instant |
| 2 - Paths | builds the car and pedestrian route graphs of Portland from the game's IDE/IPL data | instant |
| 3 - Render | Blender renders every sprite at 32 px per metre into `work\sprites\`: 15 cars × 16 directions, 15 peds (stand, walk, dead), run cycles, Claude's punch and pistol, the police chopper, two portraits | 2–5 min, first build only |
| 4 - Export | the map + sprites + HUD + menu art + your music → `work\res\` (tiles, collision, radar, routes, sprite strips) | about 1 min |
| 5 - Compile | Java 1.3 → jar → ProGuard → CLDC check → `dist\GTAWI.jar` + `.jad` | instant |

| Command Line arguments | Result |
|---|---|
| `build.bat fps` | `dist\GTAWI_fps.jar`, with a two-line profiler above the radar |
| `build.bat god` | `dist\GTAWI_god.jar`, with the profiler; Claude takes no damage |
| `build.bat rerender` | renders the sprites again, e.g. after you change `pipeline\render_sprites.py` |
| `build.bat clean` | deletes `work\` and `dist\` first |

The profiler shows fps and the worst frame in ms. Then, in ms per frame: u = update, m = map, s = sprites,
h = HUD, f = flushGraphics, w = sleep. Last come page loads, misses and heap.

### 5. Play the game

- **On the PC:** `run.bat` (or `run.bat fps` / `run.bat god`).
  FreeJ2ME keys: arrows = 2/4/6/8, Enter = 5, number keys = 0–9, E = \*, R = #, Q or W = soft key.
- **On a phone:** copy `GTAWI.jar` and `GTAWI.jad` to the phone (cable, Bluetooth or memory card) and open the jad.
  - The game loads every map page into memory at the start. On phones with little memory it falls back to
    streaming. If it is still too slow, see `WI-Cache` below.

## Optional keys for the `.jad`

Add any of these lines to the jad (or to `META-INF\MANIFEST.MF` in the jar):

| Key | Example | Effect |
|---|---|---|
| `WI-Fps` | `1` | the profiler (what `build.bat fps` sets) |
| `WI-God` | `1` | no damage (what `build.bat god` sets) |
| `WI-Stars` | `5` | start straight at that wanted level (2–6), for testing the late waves |
| `WI-At` | `4800 1389 270` | start next to a police car at that map pixel, facing that way (degrees, 0 = up). An optional 4th number is the car's damage |
| `WI-Cache` | `999,48,56,999` | stream the map instead of preloading it. The numbers are the page caps for ground, overlay, buildings and props |

## Changing the map

Open `map\chunk01\chunk01_col.tmx` in [Tiled](https://www.mapeditor.org/) 1.10 or newer.

**Tile layers:**
- `ground`, `roads`: opaque base
- `bridge`, `bridgepass`, `decals`, `rail`: overlay
- `building_tile`: buildings; the exporter works out what covers peds and cars
- `collision`: 8 px solid cells

**Object layers:**
- `props`: lamp posts, lights, signs and so on. Those whose `model` is in the `POLE` list in `pipeline\export.py` can be knocked down
- `mission`: the start points `claude`, `eight`, `car`, the burning wrecks `fire*`, and optionally `misty`
- `spawns`: cop spawn points, and the roadblock rectangles at the map exits (type `roadblock`)
- `weapons`: the five weapon-drop points

Save, then run `build.bat`. The sprites aren't rendered again, so it takes about a minute.

## Folder layout

```
gta-wi\
  src\            game source (WhatIf.java is the MIDlet)
  map\            chunk01\chunk01_col.tmx + tilesets, tiles\*.png (the tile art)
  pipeline\       assets.py      what is taken from GTA III (models, anims, cars, colours)
                  extract.py     step 1        build_paths.py  step 2
                  render_sprites.py  step 3 (runs inside Blender)
                  export.py      step 4 (+ tmx.py, rwtxd.py)    check_cldc.py  step 5
  music\          your MIDI files
  tools\          downloaded by setup.bat
  work\           everything generated (safe to delete)
  dist\           the finished jar + jad
```

## Troubleshooting

- **"GTA III not found":** `GTA3` in `config.bat` must point at the folder that contains `gta3.exe`.
- **"not found in your GTA III folder: …":** a modded `gta3.img` may be missing a model. Use an unmodded copy.
- **RENDER FAILED:** read `work\render_blender.log` and `work\sprites\render_log.txt`.
  The usual cause is a graphics driver too old for Blender 5.2. Update the driver and run `build.bat rerender`.
- **"INU Tools is not installed in this Blender":** delete `tools\blender-5.2.3-windows-x64\portable` and run `setup.bat` again.
- **setup.bat fails on FreeJ2ME:** the zip may have changed layout. Download FreeJ2ME-Plus 1.52 by hand and put
  `freej2me.jar` anywhere under `tools\freej2me\`.
- **A phone says "Error" + a number at start-up:** the jar calls a Java method the phone doesn't have.
  `check_cldc.py` should catch that during the build, so please report it.
- **No sound on the phone:** check the phone's profile and the game's SOUND option. Make sure `music\` has
  `maintheme.mid` / `trackNN.mid`.
