import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"

    // Coverage (replaces jacoco, which only supports the java plugin)
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    `maven-publish`
}

group = "com.desweemerl"
version = "0.1.1"

repositories {
    mavenCentral()
}

val coroutinesVersion = "1.11.0"
val ktorVersion = "3.6.0"

kotlin {
    // Built and tested with the latest LTS JDK.
    jvmToolchain(25)

    jvm {
        // The published library still targets Java 11 (bytecode and JDK API), for Android and older JVMs.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjdk-release=11")
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Multiplatform logger: Logcat on Android, OSLog on iOS, console elsewhere.
            // Applications configure it through Kermit's global Logger (writers, minimum severity).
            implementation("co.touchlab:kermit:2.2.0")

            // Json serialization/deserialization. Exposed as api: payloads are JsonElement and decoded with serializers.
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

            // Multiplatform atomics
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")

            // Companion library for Kotlin. Exposed as api: the public API returns Flow.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

            // WebSocket engine. Exposed as api: KtorEngine accepts a custom HttpClient.
            api("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.ktor:ktor-client-websockets:$ktorVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("co.touchlab:kermit:2.2.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktorVersion")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/desweemerl/phoenix-client")
            credentials {
                username = System.getenv("GITHUB_USERNAME")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
