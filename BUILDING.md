# Building FNVDS

## Android

Use JDK 21 and Android SDK Platform 37. The committed wrapper pins Gradle 9.6.0;
the first invocation downloads that distribution if it is not already cached.
Set `ANDROID_HOME` to your SDK directory, or use an ignored `local.properties`
containing `sdk.dir`. Android Studio can configure the SDK path for you.

From the project root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Use `sh ./gradlew` on macOS/Linux, or make the script executable and use `./gradlew`. Debug APK output is
`app/build/outputs/apk/debug/app-debug.apk`; it does not require a release key.

## Release signing

The maintainer's signing key is private and is not part of this repository.
For your own release, create an Android signing keystore with Android Studio
or JDK `keytool`, and keep it outside the repository. Create a private Java
properties file at `~/.fnvds/signing/release.properties`, or point the
`FNVDS_SIGNING_PROPERTIES` environment variable to a different properties file:

```properties
storeFile=C:/absolute/path/to/your-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Use forward slashes in Windows `storeFile` paths. These example values are
placeholders; never commit your real properties file or keystore.

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Release builds disable
debugging and require signing configuration; they do not fall back to a debug
key or an unsigned APK. Code shrinking is disabled to retain the current app's
behaviour. Anyone building from source uses their own key, which cannot update
an APK signed with another author's key without uninstalling that APK first.

Back up the keystore and credentials securely to a separate location and reuse
the same key for updates. A new key is not needed when changing app versions.
Losing the key prevents normal updates to installations signed with it.

Verify the finished APK with the Android SDK Build Tools:

```powershell
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

## Native plugin

Use Windows, Visual Studio 2022's **x86** C++ toolchain, CMake 3.24+, Ninja,
and the [xNVSE 6.4.9 source/SDK](https://github.com/xNVSE/NVSE/tree/6.4.9).
`FNVDS_XNVSE_SDK` must point to the directory containing `nvse/PluginAPI.h`.
The SDK is a separate dependency; no additional runtime NVSE plugins are needed.

In an x86 Visual Studio Developer PowerShell:

```powershell
$env:FNVDS_XNVSE_SDK = "C:\path\to\NVSE-6.4.9\nvse"
cmake -S native -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Output: `build/FNVDS.dll`. The plugin uses C++20 and targets the 32-bit
Fallout: New Vegas 1.4.0.525 runtime. Native memory layouts and addresses are
runtime-specific; see [native/README.md](native/README.md).

## Release contents

Include only `FNVDS.apk` and the `Data/` folder (containing
`NVSE/Plugins/FNVDS.dll`) in `FNVDS-<version>.zip`. Keep `README.md`, `LICENSE.txt`
and `CHANGES.txt` alongside the ZIP, outside the archive. Generated APKs, DLLs
and ZIPs belong in GitHub Release assets rather than source commits. Build/lint
checks do not replace checking gameplay on the Thor.
