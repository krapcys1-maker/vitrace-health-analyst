# Android Setup

VitaTrace starts as an Android app. This machine currently has Java 21, a committed Gradle Wrapper, and Android SDK command-line tools installed under the user's local Android SDK directory.

## Install Tooling

1. Android command-line tools are already installed locally.
2. Android SDK Platform 36, Build-Tools 36, Build-Tools 34, and Platform-Tools are installed.
3. Optional: install Android Studio for easier phone deployment and debugging.

If setting up a fresh machine:

1. Install Android Studio.
2. In Android Studio, install:
   - Android SDK Platform 36
   - Android SDK Build-Tools
   - Android SDK Platform-Tools
3. Open this repository in Android Studio.
4. Let Android Studio create or use `local.properties` with the local SDK path.
5. Sync Gradle.

`local.properties` must stay untracked because it contains a machine-specific SDK path.

## First Phone Install

On the phone:

1. Enable Developer options.
2. Enable USB debugging.
3. Connect the phone by USB.
4. Accept the computer trust prompt.
5. Run the `app` configuration from Android Studio.

The first app milestone is a Health Connect diagnostics screen. It should show SDK status, permission status, and basic 7-day counts after permission grant.

## Current Limitation

`gradlew.bat tasks` passes.

`gradlew.bat :app:assembleDebug` passes and creates:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Expected local file after SDK install:

```properties
sdk.dir=C\:\\Users\\user\\AppData\\Local\\Android\\Sdk
```

The exact path may differ.

## Install With ADB

After enabling USB debugging on the phone and accepting the computer trust prompt:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```
