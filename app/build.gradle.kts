import java.time.LocalDateTime

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.voicetoinvoice.app"
    compileSdk = 36
    signingConfigs {
        // A fixed, repo-committed debug key -- not the machine-local ~/.android/debug.keystore
        // AGP falls back to otherwise, which is randomly generated per machine and per CI
        // runner. Every previous APK (laptop-built and CI-built alike) was signed with a
        // different one of those ephemeral keys, so each install "conflicted with an existing
        // package" instead of updating it (ISSUE-125). All debug/perf builds now share this
        // identity, so upgrades install cleanly everywhere.
        getByName("debug") {
            storeFile = file("shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    defaultConfig {
        applicationId = "com.voicetoinvoice.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        // Without this, AGP falls back to the legacy `android.test.InstrumentationTestRunner`,
        // which cannot run AndroidJUnit4 tests -- the run died with "Instrumentation run failed
        // due to Process crashed" and reported zero tests.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "BUILD_STAMP",
            "\"" + LocalDateTime.now().toString().substring(0, 19) + "\""
        )
        buildConfigField(
            "String",
            "GIT_SHA",
            "\"" + (try {
                val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(rootDir).start()
                p.inputStream.bufferedReader().readText().trim().ifEmpty { "nogit" }
            } catch (e: Exception) { "nogit" }) + "\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // ISSUE-116: every APK shipped so far has been `assembleDebug` — debuggable, no R8.
        // This variant is release-shaped (not debuggable, R8 on) but signed with the debug
        // keystore, so it installs on the test phone without introducing a signing secret.
        // Obfuscation stays OFF: it buys nothing here and would make stack traces unreadable
        // while the parse pipeline is still being tuned.
        create("perf") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation("androidx.work:work-runtime-ktx:2.9.0")
  // Lets a baseline profile be installed once one is generated (see Phase E preamble).
  implementation("androidx.profileinstaller:profileinstaller:1.3.1")

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Async Image Loading (Item Icons)
  implementation("io.coil-kt:coil-compose:2.6.0")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Room Database — KSP annotation processing (Kotlin 2.0.x compatible)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Real org.json on the unit-test classpath. The mockable android.jar stubs every
  // org.json method to throw "not mocked", which made IntentRouterTest fail on the
  // first JSONObject.put(). The parser/router contract is JSONArray-based, so tests
  // need a working implementation rather than returnDefaultValues.
  testImplementation("org.json:json:20240303")
  // Room in-memory DB tests run as instrumented tests on a device/emulator rather than
  // under Robolectric -- Robolectric has no android-all jar for compileSdk 36 and would
  // make `./gradlew test` depend on a network fetch. Pure logic (WAC costing, intent
  // scoring, bill rendering) is covered by JVM tests with no Android context.
  androidTestImplementation("androidx.room:room-testing:2.6.1")
  androidTestImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
