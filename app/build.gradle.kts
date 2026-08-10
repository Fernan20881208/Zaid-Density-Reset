import java.security.MessageDigest
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val licenseApiUrl = providers.gradleProperty("LICENSE_API_URL")
    .orElse(providers.environmentVariable("LICENSE_API_URL"))
    .orElse("https://zzlvupunploglgxbgllm.supabase.co/functions/v1/license-api")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

android {
    namespace = "com.zaid.densityreset"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zaidnavarro.ds"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.5.0"

        buildConfigField("String", "LICENSE_API_URL", "\"$licenseApiUrl\"")
        buildConfigField("long", "LICENSE_OFFLINE_GRACE_HOURS", "12L")
        buildConfigField("String", "GITHUB_OWNER", "\"Fernan20881208\"")
        buildConfigField("String", "GITHUB_REPOSITORY", "\"Zaid-Density-Reset\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
        compose = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
val generatedBrandSourceDir = layout.buildDirectory.dir("generated/brandSource")

android.sourceSets.getByName("main").apply {
    res.srcDir(generatedBrandResDir)
    java.srcDir(generatedBrandSourceDir)
}

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
    val imageAssetsOutput = generatedBrandSourceDir.map {
        it.file("com/zaid/densityreset/util/ImageAssets.kt")
    }

    inputs.files(listOf(logoSource) + backgroundSources)
    outputs.files(logoOutput, imageAssetsOutput)

    doLast {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }

        val logoBytes = Base64.getDecoder().decode(
            logoSource.asFile.readText().trim()
        )
        check(
            sha256(logoBytes) ==
                "8cbc6a8fb470c03c29482b395af8d5ce95f8a5d8bb71e5172d5e8ebf173a1b89"
        ) {
            "El recurso generado no coincide con file (1).svg."
        }
        logoOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(logoBytes)
        }

        val backgroundBase64 = backgroundSources.joinToString(separator = "") {
            it.asFile.readText().trim()
        }
        val backgroundBytes = Base64.getDecoder().decode(backgroundBase64)
        check(
            sha256(backgroundBytes) ==
                "8c09653f8ef00cead504548f255d79a18851a4c807330acbf54be158087c3783"
        ) {
            "El recurso generado no coincide con file.svg."
        }

        val chunks = backgroundBase64.chunked(4_000)
        imageAssetsOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("package com.zaid.densityreset.util")
                    appendLine()
                    appendLine("object ImageAssets {")
                    appendLine("    const val BACKGROUND_BASE64: String =")
                    chunks.forEachIndexed { index, chunk ->
                        append("        \"")
                        append(chunk)
                        append("\"")
                        if (index != chunks.lastIndex) append(" +")
                        appendLine()
                    }
                    appendLine("}")
                }
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(decodeBrandAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.material:material:1.14.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
