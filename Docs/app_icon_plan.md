# Plan: replace the stock Android launcher icon with a mic + rupee mark

## Why

`app/src/main/res/drawable/ic_launcher_background.xml` and `ic_launcher_foreground.xml` are
still the unmodified Android Studio template — the green robot on a grid. The app ships to
shopkeepers with a default icon.

`minSdk` is 26, so `mipmap-anydpi-v26/ic_launcher.xml` is **always** the icon used on every
supported device. It already points at the two drawables below, so replacing only those two
files changes the icon everywhere. The `mipmap-*dpi/*.webp` rasters are dead weight at
minSdk 26 — leave them alone.

## Step 1 — replace `app/src/main/res/drawable/ic_launcher_background.xml`

Replace the entire file contents with exactly:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#1B7F5A"
        android:pathData="M0,0h108v108h-108z" />
</vector>
```

## Step 2 — replace `app/src/main/res/drawable/ic_launcher_foreground.xml`

Replace the entire file contents with exactly:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M47,29a7,7 0 0 1 14,0v12a7,7 0 0 1 -14,0z" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M41,44a13,13 0 0 0 26,0" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M54,57v6" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M46,63h16" />
    <path
        android:strokeColor="#FFC94A"
        android:strokeWidth="4.5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M44,66h20" />
    <path
        android:strokeColor="#FFC94A"
        android:strokeWidth="4.5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M44,74h20" />
    <path
        android:strokeColor="#FFC94A"
        android:strokeWidth="4.5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M48,66c10,0 10,8 0,8" />
    <path
        android:strokeColor="#FFC94A"
        android:strokeWidth="4.5"
        android:strokeLineCap="round"
        android:fillColor="#00000000"
        android:pathData="M52,74l10,11" />
</vector>
```

All drawn content sits between x=41..67 and y=22..85, inside the 66dp adaptive-icon safe zone
(21..87), so no launcher mask will clip it.

## Step 3 — do not touch anything else

Do NOT modify `mipmap-anydpi-v26/ic_launcher.xml`, `mipmap-anydpi-v26/ic_launcher_round.xml`,
`AndroidManifest.xml`, or any `.webp`. The manifest already points at `@mipmap/ic_launcher` and
`@mipmap/ic_launcher_round`, and both already reference the two drawables replaced above.

## Verify

`./gradlew.bat assembleDebug` succeeds. On the phone the launcher shows a white microphone
above a gold rupee on a dark green tile.

## Mirrored logic

None — client-only resource change. No change in
`supabase/functions/process-voice-job/index.ts`.

## Deviations

None expected.
