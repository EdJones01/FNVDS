# FNVDS

**FNVDS (Fallout: New Vegas Dual Screen)** is an interactive companion for playing *Fallout: New Vegas* on the AYN Thor. The game runs on the upper display and the companion app runs on the lower display.

FNVDS works with both the **Steam and GOG versions of Fallout: New Vegas**. The supported device is the **AYN Thor**, running the game in [**GameNative**](https://gamenative.app/). 

*Other devices and configurations are not confirmed*.

**[Video Demo](https://youtu.be/81zquuI1nRc)**

**[Discord](https://discord.gg/Cfare9cKmf)**

## Features

### Stats

- View health, AP, limb condition, active effects, level and XP.
- Use healing items, treat crippled limbs and manage Hardcore needs.
- Browse S.P.E.C.I.A.L., skills, perk descriptions, karma and faction reputation.

### Items

- Browse categorised inventory with item stats, condition and equipped indicators.
- Equip gear, use aid, switch compatible ammo, assign hotkeys and install weapon mods.
- Drop individual items or stacks, and sort your inventory by name, weight or value.

### Maps

- Explore live world and local maps with player, quest, custom and location markers.
- Pan, zoom, search locations, save favourites and fast travel.
- Place or remove custom markers.

### Data

- Track quests and view their objectives and map markers.
- Read notes and play or stop voice recordings through the game.
- Change radio stations and browse general statistics and challenges.

### Other Features

- Dialogue: Select responses, view skill checks and skip spoken lines.
- V.A.T.S.: Choose targets and body parts, view hit chances and predicted damage, and queue or cancel attacks.
- Trade monitor: View your proposed trade, item values and caps.
- Looting summary: See items taken or deposited and their total values during each session.

## Requirements

- **Steam or GOG Fallout: New Vegas**, installed and running in GameNative. The plugin targets the 32-bit **1.4.0.525** game runtime.
- **xNVSE**, installed and working in the same game environment.
- An Android 8.0+ device; the supported device is the **AYN Thor**.
- Matching versions of **FNVDS.apk** and **FNVDS.dll**.

## Install and update

1. Install [xNVSE](https://github.com/xNVSE/NVSE) in the game's environment.
2. Extract the release ZIP and copy its `Data` folder into the New Vegas installation so the plugin is at:

   ```text
   Data/
   └── NVSE/
       └── Plugins/
           └── FNVDS.dll
   ```

3. Install `FNVDS.apk` on the Thor.
4. Launch New Vegas through xNVSE and open FNVDS on the lower display.

When updating, close the game and replace **both** the APK and DLL with the matching release.

## How it works

The Android companion and xNVSE plugin exchange data over a local TCP bridge on `127.0.0.1:47888`. The bridge listens on loopback and is not exposed as a service on the local Wi-Fi network. The Android app needs network permission for this connection.

Game data, map textures and note/perk text are read from the user's installed game. Voice notes and gameplay actions run in New Vegas. No game assets are distributed with FNVDS.

## Build from source

### Android app

Requirements:

- Android Studio
- JDK 21
- Android SDK Platform 37
- Gradle 9.6.0 is provided through the committed Gradle wrapper; no separate Gradle installation is required.

Open the project in Android Studio, allow Gradle sync, then build the `app` module. On Windows, the included wrapper builds a debug APK from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. On macOS/Linux, use `sh ./gradlew` instead.

For a signed release APK, configure your own private signing key and run `.\gradlew.bat :app:assembleRelease`. See [BUILDING.md](BUILDING.md) for setup and key-backup instructions. The maintainer's key and credentials are not distributed.

### Native plugin

Build on Windows with Visual Studio 2022's **x86 C++ toolchain**, CMake 3.24+, Ninja, and the xNVSE SDK headers. The plugin uses C++20 and targets the 32-bit Fallout: New Vegas 1.4.0.525 runtime.

Set `FNVDS_XNVSE_SDK` to the extracted xNVSE SDK directory containing `nvse/PluginAPI.h`. In an x86 Visual Studio Developer PowerShell, run:

```powershell
$env:FNVDS_XNVSE_SDK = "C:\path\to\NVSE-6.4.9\nvse"
cmake -S native -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Replace the example SDK path with the path on your machine. Output: `build/FNVDS.dll`.

Build the native plugin with the **xNVSE 6.4.9 SDK**, the SDK version used for the packaged builds. The extracted SDK is a separate build dependency and is not included in this repository. Further build instructions are in [BUILDING.md](BUILDING.md); native implementation notes are indexed in [native/README.md](native/README.md).

## Compatibility

FNVDS has been tested and works with [Gopher's Stable New Vegas](https://www.nexusmods.com/games/newvegas/collections/60wuix/mods), installed following Gopher's YouTube tutorial, **with OneTweak removed or disabled**.

*Other mod configurations are not confirmed.*

## Built with AI Assistance

This project was developed with significant assistance from OpenAI's Codex. AI was used throughout the development process, including writing code, debugging, researching solutions and producing documentation.

All design decisions, feature requirements and final implementation choices were made by the author. The AI served as a development tool, with its contributions reviewed, refined and tested to ensure the software behaves as intended.

This disclosure is provided in the interest of transparency. I believe users should know how the software they install was developed. 


## Credits and license

FNVDS uses the xNVSE plugin API. See the [xNVSE project](https://github.com/xNVSE/NVSE) for its authors and contributors.

Massive thanks to the people over at the [New Vegas decompilation project](https://github.com/momusdigital/fnv_decomp) for their work. This helped tremendously with research whilst building this project.

The committed Gradle Wrapper retains its own Apache 2.0 license; see [third-party notices](THIRD_PARTY_NOTICES.md).

FNVDS is licensed under the **GNU General Public License version 3.0**. See [LICENSE.txt](LICENSE.txt).

*Fallout: New Vegas* and Pip-Boy are properties of their respective owners. FNVDS is an independent fan project and is not affiliated with or endorsed by Bethesda or ZeniMax. No game assets are distributed with FNVDS.
