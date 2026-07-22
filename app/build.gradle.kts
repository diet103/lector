import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Local-only developer secrets (git-ignored); absent in CI, so fields default to empty
val devProps = Properties().apply {
    val file = rootProject.file("dev.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Release signing. Locally from keystore.properties (git-ignored), in CI from repo secrets exported
// as env vars. When neither is present `assembleRelease` still succeeds and simply leaves the APK
// unsigned, so anyone cloning the repo can build a minified APK without our keystore.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSecret(property: String, environment: String): String? =
    keystoreProps.getProperty(property) ?: System.getenv(environment)

// ML Kit's OCR model is ~10.6 MB of native code per ABI and ~41 MB across all four, so an unsplit
// APK is an indefensible sideload download. Splits are release-only: debug stays a single
// app-debug.apk so the install loop and CI remain one-liners. Force either way with -PabiSplits=.
val abiSplits: Boolean = (project.findProperty("abiSplits") as String?)?.toBooleanStrictOrNull()
    ?: gradle.startParameter.taskNames.any { it.contains("Release") }

android {
    namespace = "io.github.diet103.lector"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.diet103.lector"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("String", "DEV_ELEVEN_KEY", "\"\"")
    }

    // Every split shares one versionCode on purpose. Distinct per-ABI codes only matter to Play's
    // multi-APK resolution; for sideloading they would turn switching from the universal APK to a
    // per-ABI one into a blocked "downgrade". Revisit if Lector ever ships on Play.
    splits {
        abi {
            isEnable = abiSplits
            reset()
            // x86 (32-bit) is omitted — it is effectively dead on Android 8+.
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // The universal APK is the "if unsure, take this one" download.
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val store = signingSecret("storeFile", "LECTOR_KEYSTORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingSecret("storePassword", "LECTOR_KEYSTORE_PASSWORD")
                keyAlias = signingSecret("keyAlias", "LECTOR_KEY_ALIAS")
                keyPassword = signingSecret("keyPassword", "LECTOR_KEY_PASSWORD")
            }
            // AGP leaves v3 off by default. It is the scheme that supports key rotation, which is
            // the only escape hatch a sideloaded app has if this key is ever compromised.
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_ELEVEN_KEY", "\"${devProps.getProperty("ELEVENLABS_API_KEY", "")}\"")
            // The dev loop installs on one arm64 phone, and carrying all four ML Kit ABIs made the
            // debug APK 60 MB — slow to dex and slow to push over wireless adb. Pass -PdebugAbis=all
            // when you need an APK that also installs on an x86_64 emulator.
            if (project.findProperty("debugAbis") != "all") {
                ndk { abiFilters += "arm64-v8a" }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            // Diagnostic switch, never used for an actual release. An R8-only failure can't be
            // reproduced on a debug build by definition, and can't be driven from adb either --
            // run-as needs a debuggable package. `-PdebuggableRelease=true` gives the minified
            // build just enough to be pushed test files and driven, without changing R8's output.
            isDebuggable = (project.findProperty("debuggableRelease") as String?).toBoolean()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }

    lint {
        // Media3's UnstableApi is accepted project-wide. Its marker is the androidx flavor of
        // @RequiresOptIn, which kotlinc's optIn flag cannot register — this lint switch is the
        // only real enforcement point, so disabling it here IS the project-wide opt-in.
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.jsoup)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.turbine)
}
