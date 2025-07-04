plugins {
    kotlin("android") version "1.9.23" apply false
    id("com.android.application") version "8.6.0-beta02" apply false
    id("com.google.gms.google-services") version "4.4.1" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.7.7" apply false

}

buildscript {
    dependencies {
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.7.7")
        classpath("com.google.gms:google-services:4.4.1")
    }
}