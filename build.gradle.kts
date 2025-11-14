plugins {
    id ("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("com.android.application") version "8.6.0-beta02" apply false
    id("com.google.gms.google-services") version "4.4.1" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.7.7" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
}

buildscript {
    dependencies {
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.7.7")
        classpath("com.google.gms:google-services:4.4.1")
    }
}