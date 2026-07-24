import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elderguard.care"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.elderguard.care"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }
    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("KEYSTORE_PATH", "zhuapaq-release.keystore"))
            storePassword = localProps.getProperty("KEYSTORE_PASS", System.getenv("KEYSTORE_PASS") ?: "")
            keyAlias = localProps.getProperty("KEY_ALIAS", "zhuapaq")
            keyPassword = localProps.getProperty("KEY_PASS", System.getenv("KEY_PASS") ?: "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
