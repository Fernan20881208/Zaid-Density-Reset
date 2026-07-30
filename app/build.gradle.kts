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
        versionCode = 7
        versionName = "1.2.0"

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
    val sourceFile = layout.projectDirectory.file("src/main/brand/zaid_logo.base64")
    val outputFile = generatedBrandResDir.map {
        it.file("drawable-nodpi/zaid_logo.webp")
    }

    inputs.file(sourceFile)
    outputs.file(outputFile)

    doLast {
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()

        // Normaliza una transcripción dañada del recurso y comprueba que el
        // binario generado sea exactamente el retrato proporcionado por Zaid.
        val encoded = sourceFile.asFile.readText().trim().replace(
            "SZgBGKeEN15y6229HByBFb",
            "SZgBGKeEN15i6229HByBFb"
        )
        val decoded = Base64.getDecoder().decode(encoded)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(decoded)
            .joinToString("") { byte -> "%02x".format(byte) }
        check(digest == "1d1c3dcc2dfe09bc595f9acbe35689b69a1bcbe93e02fb6e5ec63f77d4c0bd23") {
            "El recurso del logo no coincide con la imagen original."
        }
        destination.writeBytes(decoded)
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
