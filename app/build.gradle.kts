plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.axioloom.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.axioloom.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "2.1.5"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "axioloom123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "axioloom"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "axioloom123"
            } else {
                // Fallback to debug signing config so release APK is always signed and installable
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/module-info.class",
                "META-INF/ASL2.0",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
            pickFirsts += setOf(
                "META-INF/log4j-provider.properties",
                "graphml.xsd",
                "**/*.xsd"
            )
        }
    }

    androidResources {
        noCompress += listOf("onnx", "ort", "txt", "json")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val inkVersion = "1.0.0-alpha03"
    implementation("androidx.ink:ink-authoring:$inkVersion")
    implementation("androidx.ink:ink-brush:$inkVersion")
    implementation("androidx.ink:ink-geometry:$inkVersion")
    implementation("androidx.ink:ink-nativeloader:$inkVersion")
    implementation("androidx.ink:ink-rendering:$inkVersion")
    implementation("androidx.ink:ink-strokes:$inkVersion")
    implementation("androidx.input:input-motionprediction:1.0.0-beta05")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    implementation("org.matheclipse:matheclipse-core:3.0.0") {
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("org.apache.logging.log4j:log4j-api:2.24.1")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("ru.noties:jlatexmath-android:0.2.0")

    testImplementation("junit:junit:4.13.2")
}
