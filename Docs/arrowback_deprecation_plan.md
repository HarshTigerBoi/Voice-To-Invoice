# Plan: replace deprecated `Icons.Filled.ArrowBack` with the AutoMirrored variant

## Why

Every `assembleDebug` emits:

```
w: .../ui/screens/logs/DiagnosticLogsScreen.kt:98:44 'val Icons.Filled.ArrowBack: ImageVector'
   is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.ArrowBack.
```

`AutoMirrored` also flips the glyph correctly under RTL layouts.

## Steps

1. Open `app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt`.
2. At line 98, replace `Icons.Filled.ArrowBack` with `Icons.AutoMirrored.Filled.ArrowBack`.
3. Fix the imports in that same file: remove the now-unused
   `androidx.compose.material.icons.filled.ArrowBack` import if nothing else in the file uses
   it, and add `androidx.compose.material.icons.automirrored.filled.ArrowBack`.
4. Change nothing else in the file, and no other file.

## Verify

`./gradlew.bat assembleDebug` completes and the `ArrowBack is deprecated` warning is gone.

## Mirrored logic

None — this is client-only UI. No change is needed in
`supabase/functions/process-voice-job/index.ts`.

## Deviations

None expected.
