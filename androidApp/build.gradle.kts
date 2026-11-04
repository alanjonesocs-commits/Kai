plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val sherpaAarFile = layout.buildDirectory.file("third-party/sherpa-onnx-2.0.1.aar")
val sherpaAarSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

val downloadSherpaAar by tasks.registering {
    outputs.file(sherpaAarFile)

    doLast {
        val output = sherpaAarFile.get().asFile
        fun sha256(file: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        if (output.isFile && sha256(output) == sherpaAarSha256) {
            logger.lifecycle("Verified cached sherpa-onnx 2.0.1 AAR")
            return@doLast
        }

        output.parentFile.mkdirs()
        val temporary = output.resolveSibling("${output.name}.part")
        temporary.delete()
        try {
            java.net.URL(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
            ).openStream().use { input ->
                temporary.outputStream().buffered().use { destination ->
                    input.copyTo(destination)
                }
            }
            val actualSha256 = sha256(temporary)
            check(actualSha256 == sherpaAarSha256) {
                "sherpa-onnx AAR checksum mismatch: expected $sherpaAarSha256, got $actualSha256"
            }
            check(temporary.renameTo(output)) {
                "Could not move verified sherpa-onnx AAR into the Gradle build directory"
            }
            logger.lifecycle("Downloaded and verified sherpa-onnx 2.0.1 AAR ($actualSha256)")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}

android {
    namespace = "com.inspiredandroid.kai"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.inspiredandroid.kai"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode =
            libs.versions.android.versionCode
                .get()
                .toInt()
        versionName = libs.versions.appVersion.get()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("playStore") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
            isDefault = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".sherpaspike"
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../composeApp/proguard-rules.pro")
            signingConfig =
                if (System.getenv("KEYSTORE_FILE") != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(files(sherpaAarFile))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.foundation.android)
    implementation(libs.compose.material3)
    implementation(libs.koin.android)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.filekit.core)
    implementation(libs.filekit.compose)
    implementation(libs.tts)
    implementation(libs.tts.compose)
    implementation(libs.compose.components.uiToolingPreview)
    debugImplementation(libs.compose.ui.tooling)
    "playStoreImplementation"(libs.play.review)
}

tasks.named("preBuild").configure {
    dependsOn(downloadSherpaAar)
}
