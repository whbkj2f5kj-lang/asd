plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "de.jarvis.w204"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.jarvis.w204"
        minSdk = 29          // Android 10 (Head-Unit)
        targetSdk = 34
        versionCode = 48
        versionName = "5.8"
        // Standard (Android Studio): alle Prozessoren. Die Radio-APK baut GitHub mit -Pabis=arm64-v8a,armeabi-v7a
        val abis = (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "tflite" }
}

// KI-Modell für die Fahrzeugerkennung (EfficientDet-Lite0, ~4 MB) wird beim ersten Build geladen
val modelUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite"
val modelFile = file("src/main/assets/efficientdet_lite0.tflite")
val downloadModel by tasks.registering {
    outputs.file(modelFile)
    onlyIf { !modelFile.exists() }
    doLast {
        modelFile.parentFile.mkdirs()
        uri(modelUrl).toURL().openStream().use { input: java.io.InputStream ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModel) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // Standort wie Google Maps & Co. (GPS + WLAN + Mobilfunk gemischt) – falls Google-Dienste auf dem Radio sind
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Texterkennung offline (Modell in der APK, braucht keine Google-Dienste)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Fahrzeug-/Personenerkennung offline
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Eigener USB-Kameratreiber (UVC), falls das Radio USB-Kameras nicht an Android weitergibt
    implementation("com.herohan:UVCAndroid:1.0.13")

    // USB-Seriell (Reifendruck-Empfänger mit CH340/FTDI)
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    testImplementation("junit:junit:4.13.2")
}
