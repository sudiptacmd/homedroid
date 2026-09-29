plugins {
    id("com.android.application")
}

android {
    namespace = "dev.lindroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lindroid"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload-only for now; swap for a real keystore before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One APK per ABI keeps downloads small; the universal APK is for convenience.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // The daemons are executables disguised as lib*.so. They must be extracted to
            // nativeLibraryDir (the only exec-allowed location for targetSdk >= 29) and left unstripped.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
