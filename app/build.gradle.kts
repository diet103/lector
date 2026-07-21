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

android {
    namespace = "io.github.diet103.lector"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.diet103.lector"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-dev"

        buildConfigField("String", "DEV_ELEVEN_KEY", "\"\"")
        buildConfigField("String", "DEV_VOICE_ID", "\"\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_ELEVEN_KEY", "\"${devProps.getProperty("ELEVENLABS_API_KEY", "")}\"")
            buildConfigField("String", "DEV_VOICE_ID", "\"${devProps.getProperty("DEV_VOICE_ID", "")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.turbine)
}
