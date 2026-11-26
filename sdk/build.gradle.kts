// The Qanary SDK: goes inside the app BEING TESTED. No third-party dependencies beyond the Kotlin standard library
// (declared in the published POM), so it can't clash with the host app. Dormant unless the app was launched from
// Qanary with a session token.
//
// Releases are served from https://qanaries.com/maven (see the README). To build the same artifact here:
//   ./gradlew :sdk:testDebugUnitTest                         (the unit tests)
//   ./gradlew :sdk:publishReleasePublicationToLocalRepository  (writes sdk/build/repo)
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

val sdkVersion = "0.2.1" // keep in step with Qanary.VERSION

android {
    namespace = "com.qanaries.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        // Host apps compiling against older Android SDKs can still use it (AGP would otherwise demand compileSdk 36).
        aarMetadata { minCompileSdk = 21 }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    // Plain JVM unit tests: the queue, storage and session logic use no Android types beyond stubs (StrictMode,
    // SystemClock), which return defaults here; org.json is the real library.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        // Readable by host apps on Kotlin 1.9 and newer (the default would demand Kotlin 2.3).
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
    coreLibrariesVersion = "1.9.0" // the kotlin-stdlib version the POM asks for: old enough for any modern app
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.qanaries"
            artifactId = "sdk"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Qanary SDK")
                description.set("Records how Qanary testers use your Android app during a paid test: screens, rage taps, field struggle (counts only), crashes and freezes. Dormant for everyone else.")
                url.set("https://qanaries.com/developers/sdk")
                licenses { license { name.set("Proprietary, free to use with Qanary"); url.set("https://qanaries.com/terms") } }
                organization { name.set("SHYGUY LLC"); url.set("https://qanaries.com") }
            }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
