import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// --- Signing -----------------------------------------------------------------
// Release signing is configured only when a keystore is supplied. The keystore
// and its passwords are NEVER committed (see .gitignore). Without it, `assembleRelease`
// produces an unsigned bundle so CI can still verify the release build compiles.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// --- API base URL ------------------------------------------------------------
// Priority for the compiled-in URL:
//   1. -PapiBaseUrl=...            (explicit, wins everywhere)
//   2. apiBaseUrl=... in local.properties  (persistent, git-ignored)
//   3. the per-build-type default passed by the caller
//
// local.properties.example:
//   apiBaseUrl=https://my-host.example/api/v1/
//
// There is deliberately NO environment-detected default. An earlier revision
// auto-detected a GitHub Codespaces forwarded port from CODESPACE_NAME; that made
// a handed-out APK silently point at a host that only worked from one machine, and
// a stopped codespace answers 401 with a non-JSON body — which the client could
// only surface as "Invalid credentials or session expired". A build now points at
// a real host unless the caller overrides it explicitly.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun apiBaseUrl(default: String): String {
    val raw = (project.findProperty("apiBaseUrl") as String?)
        ?: localProps.getProperty("apiBaseUrl")
        ?: default
    return if (raw.endsWith("/")) raw else "$raw/"
}

// Room schemas are exported so migrations can be diffed and verified in review.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

android {
    namespace = "bw.agriconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "bw.agriconnect"
        minSdk = 24
        targetSdk = 35
        // Bump on every build you hand out. Settings → footer shows this, so "which
        // build is on the phone?" is answerable without inspecting the APK.
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "bw.agriconnect.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        resourceConfigurations += listOf("en")

        // Base URL is injected per build type. Both point at the deployed service so a
        // handed-out APK works on a physical device with no configuration; pass
        // -PapiBaseUrl=... (or local.properties) to target a local/emulator server.
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("https://agriconnect-api-f55x.onrender.com/api/v1/")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("https://agriconnect-api-f55x.onrender.com/api/v1/")}\"")
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")
            // No debug endpoints, no sample data, no verbose logs in release.
        }
    }

    if (keystorePropsFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.window.size)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler.androidx)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.osmdroid.android)
    implementation(libs.play.services.location)
    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)

    implementation(libs.vico.compose.m3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}
