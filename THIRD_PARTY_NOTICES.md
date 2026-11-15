# Third-party notices

## Gradle Wrapper 9.6.0

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are generated
Gradle Wrapper components distributed under Apache License 2.0. Their existing
copyright/license notices are retained. The wrapper JAR's embedded license is
also available at [licenses/Gradle-Wrapper-Apache-2.0.txt](licenses/Gradle-Wrapper-Apache-2.0.txt).

The wrapper downloads Gradle from its official distribution service. Gradle
and its build toolchain are not bundled in the FNVDS runtime APK or DLL.
Downloaded SDKs and toolchains are also excluded from this source repository.

## Runtime API and research

FNVDS builds against the separately obtained [xNVSE SDK](https://github.com/xNVSE/NVSE).
The SDK source and compiled xNVSE runtime are not bundled in this repository.
Native interface research also used the [FNV decompilation reference](https://github.com/momusdigital/fnv_decomp).
See the README and native documentation for credits and implementation notes.

FNVDS project code is licensed under GPL-3.0; the third-party components above
retain their respective licenses.
