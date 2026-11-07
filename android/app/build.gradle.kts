plugins {
    id("com.android.application")
}

android {
    namespace = "global.nscb.aegis"
    compileSdk = 34

    defaultConfig {
        applicationId = "global.nscb.aegis"
        minSdk = 26
        targetSdk = 34
        versionCode = 26
        versionName = "2.6.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Release signing only when CI/local provides keystore + passwords via env.
        // Never hardcode passwords. Never commit *.keystore / *.jks.
        create("release") {
            val ksPath = System.getenv("AEGIS_KEYSTORE_PATH") ?: "../aegis.keystore"
            val ks = file(ksPath)
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("AEGIS_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("AEGIS_KEY_ALIAS") ?: "aegis"
                keyPassword = System.getenv("AEGIS_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = System.getenv("AEGIS_KEYSTORE_PATH") ?: "../aegis.keystore"
            if (file(ksPath).exists()
                && !System.getenv("AEGIS_STORE_PASSWORD").isNullOrEmpty()
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Always signed with Android debug keystore — sideload without secrets
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
