plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val numero = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ec.jat.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "ec.jat.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = numero
        versionName = "1.0.$numero"
    }

    signingConfigs {
        create("jat") {
            storeFile = file("jat.keystore")
            storePassword = "jatdatos"
            keyAlias = "jat"
            keyPassword = "jatdatos"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jat")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jat")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // Transmitir a Android TV / Chromecast (Google Cast)
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    // Android Auto con imagen (pantalla libre de las apps de navegación)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
}
