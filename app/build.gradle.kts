import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.droidlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.droidlink"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed key so every build can install over the previous one.
    // Fine for sideloading; create your own private key before publishing anywhere.
    signingConfigs {
        create("droidlink") {
            storeFile = file("droidlink.keystore")
            storePassword = "droidlink"
            keyAlias = "droidlink"
            keyPassword = "droidlink"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("droidlink")
        }
        debug {
            signingConfig = signingConfigs.getByName("droidlink")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
