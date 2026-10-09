# Files

A file explorer for Android, in Kotlin and Jetpack Compose with Material 3: dark tones, amber `#e1a34f` accent.

## What it does

- Browse internal storage and SD cards or USB drives, with tappable breadcrumbs.
- Sort by name, size, date or type, with folders kept on top. Toggle hidden files.
- Long-press to select items. You can then copy, cut, rename, share or delete them. To paste, open the destination folder and tap the amber button.
- Create folders.
- Search the current folder and everything below it.
- Open any file in a matching app (shared through a `FileProvider`).
- Tap an APK to install it. The first time, Android asks you to allow installs from Files; the system then shows its usual confirmation, and the result appears at the bottom of the screen.

It asks for **All files access** on Android 11 and later, and for the usual storage permissions on 8–10.

## Build

Requirements: JDK 17 and the Android SDK, platform 35.

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The release build is signed with the debug key so it installs without extra setup:

```sh
./gradlew assembleRelease
```

Stack: AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01; min SDK 26, target SDK 35.
