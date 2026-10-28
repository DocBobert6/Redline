plugins {
    alias(libs.plugins.android.application) apply false
    // Puts a matching Kotlin Gradle Plugin on the classpath for AGP's built-in Kotlin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}
