plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.lallamada.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lallamada.app"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // Clave fija para que cada versión nueva se instale encima de la anterior
    // sin tener que desinstalar (y sin perder la configuración).
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/lallamada.jks")
            storePassword = "lallamada"
            keyAlias = "lallamada"
            keyPassword = "lallamada"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
