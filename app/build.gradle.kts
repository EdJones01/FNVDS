import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningPropertiesFile = providers.environmentVariable("FNVDS_SIGNING_PROPERTIES")
    .orNull?.let { rootProject.file(it) }
    ?: File(System.getProperty("user.home"), ".fnvds/signing/release.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.fnvds.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fnvds.companion"
        minSdk = 26
        targetSdk = 33
        versionCode = 109
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                fun setting(name: String): String = requireNotNull(releaseSigningProperties.getProperty(name)) {
                    "Missing release signing setting: $name"
                }
                storeFile = rootProject.file(setting("storeFile"))
                storePassword = setting("storePassword")
                keyAlias = setting("keyAlias")
                keyPassword = setting("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}



// Debug builds need no private key; release builds must never fall back to unsigned output.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(releaseSigningPropertiesFile.isFile) {
            "Release signing is not configured. See BUILDING.md."
        }
    }
}
