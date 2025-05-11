plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.chatapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chatapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Решение для переименования APK
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val outputFileName = "filosoff-${variant.baseName}.apk"
                output.outputFileName = outputFileName
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
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Используйте актуальную версию
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17" // Соответствует версии Java
    }

    buildFeatures {
        compose = true // Если используете Jetpack Compose
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1" // Версия Kotlin Compiler Extension для Compose
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}" // Исключаем ненужные метаданные
        }
    }
}

dependencies {
    // AndroidX dependencies
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation ("com.squareup.okhttp3:okhttp:4.10.0")
    implementation ("org.json:json:20210307")

    // Firebase dependencies

    implementation ("com.github.bumptech.glide:glide:4.15.1")
    implementation ("com.google.firebase:firebase-storage-ktx")
    implementation ("com.google.firebase:firebase-auth-ktx")


    implementation("com.squareup.okhttp3:okhttp:4.9.3")

// Firebase BOM для управления версиями
    implementation (platform("com.google.firebase:firebase-bom:32.7.0"))

    // Firebase компоненты
    implementation ("com.google.firebase:firebase-messaging")
    implementation ("com.google.firebase:firebase-analytics")
    implementation ("com.google.firebase:firebase-auth")
    implementation ("com.google.firebase:firebase-database")

    // Для работы с JSON
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    implementation(platform("com.google.firebase:firebase-bom:32.3.1")) // Firebase BOM
    implementation("com.google.firebase:firebase-auth-ktx") // Firebase Authentication
    implementation("com.google.firebase:firebase-database-ktx") // Firebase Realtime Database
    implementation("com.google.firebase:firebase-storage-ktx") // Firebase Storage
    implementation("com.google.firebase:firebase-firestore-ktx") // Firebase Firestore

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.15.0")

    // Jetpack Compose (если используете)
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.7.2")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}