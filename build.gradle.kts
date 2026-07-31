// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
}

/*
 * Build outputs live OUTSIDE the repo, under the local (non-synced) app-data directory.
 *
 * This repo sits in a OneDrive-synced `Documents` folder. OneDrive continuously re-scans and
 * locks files under `build/`, which made Gradle fail intermittently with
 * "Unable to delete directory ... Failed to delete some children" on `kspDebugKotlin`,
 * `compileDebugKotlin`, `test-results`, and others -- CLAUDE.md records it as expected "every few
 * builds". It is not a code error and clearing caches only defers it.
 *
 * Relocating the build directory removes the cause rather than the symptom: OneDrive never sees
 * these files, so nothing external holds a handle on them. Set VTI_BUILD_DIR to override.
 */
val relocatedBuildRoot: String = System.getenv("VTI_BUILD_DIR")
    ?: System.getenv("LOCALAPPDATA")?.let { "$it/VoiceToInvoiceBuild" }
    ?: layout.buildDirectory.get().asFile.absolutePath

allprojects {
    layout.buildDirectory.set(file("$relocatedBuildRoot/${project.path.replace(':', '_').trim('_').ifEmpty { "root" }}"))
}