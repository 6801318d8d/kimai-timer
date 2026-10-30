import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key: signing/keystore.properties (storeFile, storePassword, keyAlias, keyPassword), not in git. Without it,
// release builds are signed with the debug key.
val keystore = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.x6801318d8d.kimaitimer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.x6801318d8d.kimaitimer"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.unifiedpush.android:connector:3.3.5")
}
