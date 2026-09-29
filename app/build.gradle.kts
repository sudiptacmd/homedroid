import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Release signing key: from keystore.properties (see keystore.properties.example; never
 * committed) or, in CI, from HOMEDROID_KEYSTORE* environment variables. Without either,
 * release builds use the debug key, which is fine for local testing but can't update an
 * install signed with the real key.
 */
val releaseKey: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        val p = Properties().apply { file.inputStream().use(::load) }
        p.stringPropertyNames().associateWith { p.getProperty(it) }
    } else {
        System.getenv("HOMEDROID_KEYSTORE")?.let { path ->
            mapOf(
                "storeFile" to path,
                "storePassword" to System.getenv("HOMEDROID_KEYSTORE_PASSWORD").orEmpty(),
                "keyAlias" to System.getenv("HOMEDROID_KEY_ALIAS").orEmpty(),
                "keyPassword" to System.getenv("HOMEDROID_KEY_PASSWORD").orEmpty(),
            )
        }
    }
}

android {
    namespace = "dev.homedroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.homedroid"
        minSdk = 29
        targetSdk = 36
        versionCode = 803
        versionName = "0.8.3"
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = file(key.getValue("storeFile"))
                storePassword = key.getValue("storePassword")
                keyAlias = key.getValue("keyAlias")
                keyPassword = key.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
