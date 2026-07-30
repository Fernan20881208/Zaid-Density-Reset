import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zaid.densityreset"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zaidnavarro.ds"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.2.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

val generatedBrandResDir = layout.buildDirectory.dir("generated/brandRes")

android.sourceSets.getByName("main").res.srcDir(generatedBrandResDir)

val decodeBrandAssets by tasks.registering {
    val logoSource = layout.projectDirectory.file("src/main/brand/zaid_logo.base64")
    val backgroundSources = listOf(
        "src/main/brand/background_parts/part0.base64",
        "src/main/brand/background_parts/part1_0.base64",
        "src/main/brand/background_parts/part1_1.base64",
        "src/main/brand/background_parts/part1_2.base64",
        "src/main/brand/background_parts/part1_3.base64",
        "src/main/brand/background_parts/part1_4.base64"
    ).map(layout.projectDirectory::file)

    val logoOutput = generatedBrandResDir.map {
        it.file("drawable-nodpi/zaid_logo.webp")
    }
    val backgroundOutput = generatedBrandResDir.map {
        it.file("drawable-nodpi/app_background.webp")
    }

    inputs.files(listOf(logoSource) + backgroundSources)
    outputs.files(logoOutput, backgroundOutput)

    doLast {
        fun decodeAsset(
            sources: List<File>,
            destination: File,
            expectedSha256: String,
            sourceLabel: String
        ) {
            destination.parentFile.mkdirs()
            val encoded = sources.joinToString(separator = "") {
                it.readText().trim()
            }
            val decoded = Base64.getDecoder().decode(encoded)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(decoded)
                .joinToString("") { byte -> "%02x".format(byte) }
            check(digest == expectedSha256) {
                "El recurso generado no coincide con $sourceLabel."
            }
            destination.writeBytes(decoded)
        }

        decodeAsset(
            sources = listOf(logoSource.asFile),
            destination = logoOutput.get().asFile,
            expectedSha256 = "8cbc6a8fb470c03c29482b395af8d5ce95f8a5d8bb71e5172d5e8ebf173a1b89",
            sourceLabel = "file (1).svg"
        )
        decodeAsset(
            sources = backgroundSources.map { it.asFile },
            destination = backgroundOutput.get().asFile,
            expectedSha256 = "8c09653f8ef00cead504548f255d79a18851a4c807330acbf54be158087c3783",
            sourceLabel = "file.svg"
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(decodeBrandAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.material:material:1.14.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
