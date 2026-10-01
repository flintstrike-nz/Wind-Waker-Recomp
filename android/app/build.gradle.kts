plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Where scripts/android/build.sh stages the native libraries (arm64-v8a/*.so):
//   libmain.so               the host (runtime/host + GXRuntime + Aurora + SDL3)
//   libbwdisc.so             the disc importer
//   libgGZLE01_recomp.so     the game module translated from the player's disc
// Pass -PbluewakeJniLibs=DIR. Without it the app builds with no native libraries
// (the launcher then reports that this build has no game module).
val jniLibsDir = providers.gradleProperty("bluewakeJniLibs")
    .orElse(layout.projectDirectory.dir("libs").asFile.absolutePath)

// Signing. A release build is signed with the keystore given by
// -Pbluewake.keystore=FILE -Pbluewake.keystorePassword=... (alias "bluewake");
// scripts/android/build.sh creates a private one on first use. Without it the
// release APK is left unsigned.
val keystorePath = providers.gradleProperty("bluewake.keystore").orNull
val keystorePassword = providers.gradleProperty("bluewake.keystorePassword").orNull

android {
    namespace = "dev.bluewake.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.bluewake.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    if (keystorePath != null && keystorePassword != null) {
        signingConfigs {
            create("bluewake") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "bluewake"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null && keystorePassword != null) {
                signingConfig = signingConfigs.getByName("bluewake")
            }
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(jniLibsDir)
    // Extra assets (the builder's BuilderProvenance.json: what this build was made from).
    providers.gradleProperty("bluewakeAssets").orNull?.let { sourceSets.getByName("main").assets.srcDir(it) }

    packaging {
        // SDL loads the host by its path under nativeLibraryDir, and the host
        // dlopens the game module from there too, so the libraries must be
        // extracted at install (android:extractNativeLibs="true" as well).
        jniLibs.useLegacyPackaging = true
    }

    // Aurora reads its seed pipeline cache through SDL's asset I/O, which seeks
    // in it: keep it stored, not compressed.
    androidResources { noCompress += "db" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // A personal build should not need lint's extra downloads; run ./gradlew lint to check.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Fold posture (Flex mode) for the in-game layout.
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-java:1.3.0")
}
