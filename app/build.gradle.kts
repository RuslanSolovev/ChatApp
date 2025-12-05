plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.gms.google-services")
    kotlin("plugin.parcelize")
    id("androidx.navigation.safeargs.kotlin")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.chatapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.chatapp"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("boolean", "STEP_COUNTER_ENABLED", "true")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjvm-default=all",
        )
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    // ИСПРАВЛЕНИЕ: packagingOptions для устранения конфликтов дубликатов
    packaging {
        resources {
            excludes += setOf(
                // Исключаем дублирующиеся .proto файлы из конфликтующих библиотек
                "google/protobuf/empty.proto",
                "google/protobuf/field_mask.proto",
                "google/protobuf/descriptor.proto",
                "google/protobuf/api.proto",
                "google/protobuf/type.proto",
                "google/protobuf/source_context.proto",
                "google/protobuf/duration.proto",
                "google/protobuf/timestamp.proto",
                "google/protobuf/wrappers.proto",
                "google/protobuf/any.proto",
                "google/protobuf/struct.proto",
                // Стандартные исключения
                "META-INF/DEPENDENCIES",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
        // Если ошибки с конкретными классами остаются, можно использовать pickFirst:
        // pickFirsts += setOf("com/google/api/Advice.class", "com/google/api/Advice\$Builder.class")
    }
}

dependencies {
    // Room
    implementation("androidx.room:room-runtime:2.5.2")
    implementation("androidx.room:room-ktx:2.5.2")
    // ИСПРАВЛЕНИЕ: Используем правильную версию из versions.toml или напрямую
    // implementation(libs.appcrawler.platform) // Закомментировано, так как может быть источником проблем
    kapt("androidx.room:room-compiler:2.5.2")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Firebase BOM
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    // Остальные зависимости
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // ИСПРАВЛЕНИЕ: Удалён дубликат material 1.9.0
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("de.hdodenhof:circleimageview:3.1.0")
    implementation ("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation ("androidx.cardview:cardview:1.0.0")

    implementation ("com.github.PhilJay:MPAndroidChart:v3.1.0")

    implementation ("de.hdodenhof:circleimageview:3.1.0")

    implementation ("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation ("androidx.cardview:cardview:1.0.0")

    implementation ("com.squareup.picasso:picasso:2.8")

    // Yandex MapKit
    implementation("com.yandex.android:maps.mobile:4.5.1-full")

    implementation("org.danilopianini:gson-extras:0.2.1")

    // OneSignal
    implementation("com.onesignal:OneSignal:[4.8.5, 4.99.99]")

    implementation ("com.google.code.gson:gson:2.10.1")

    // PhotoView
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // Timber
    implementation("com.jakewharton.timber:timber:5.0.1")
    // AWS SDK - ИСПРАВЛЕНИЕ: Исключаем конфликтующую транзитивную зависимость
    implementation("com.amazonaws:aws-android-sdk-s3:2.34.0") {
        exclude(group = "com.google.api.grpc", module = "proto-google-common-protos")
    }
    implementation("com.amazonaws:aws-android-sdk-core:2.34.0") {
        exclude(group = "com.google.api.grpc", module = "proto-google-common-protos")
    }
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.7.1")

    // Тестовые зависимости
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}