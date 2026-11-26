// AGP 9 has Kotlin built in. The Kotlin plugin is only on the classpath (never applied) so the SDK compiles with the
// same Kotlin as the published AAR at https://qanaries.com/maven: the classes come out byte for byte the same.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
