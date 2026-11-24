import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}

fun props(path: String) = Properties().apply {
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

// Same backend as the phone app: the URL and publishable key come from android/local.properties
// (git-ignored), so the repo doesn't name the backend.
val androidLocal = props("../android/local.properties")
val versionProps = props("version.properties")
val buddyVersionCode = versionProps.getProperty("versionCode", "1").toInt()
val buddyVersionName = versionProps.getProperty("versionName", "1.1.0")

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

// The phone's offline quick-add parser and repeat rules are plain Kotlin: compile the very same files here.
val sharedSrc = layout.buildDirectory.dir("generated/shared")
val copyShared by tasks.registering(Sync::class) {
    from("../android/app/src/main/java/com/shrey/buddy/data/tasks") {
        include("QuickParse.kt", "Repeat.kt")
    }
    into(sharedSrc.map { it.dir("com/shrey/buddy/data/tasks") })
}

val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val writeBuildInfo by tasks.registering {
    val url = androidLocal.getProperty("supabase.url", "")
    val key = androidLocal.getProperty("supabase.publishableKey", "")
    inputs.property("url", url)
    inputs.property("key", key)
    inputs.property("code", buddyVersionCode)
    inputs.property("name", buddyVersionName)
    outputs.dir(buildInfoDir)
    doLast {
        val f = buildInfoDir.get().file("com/shrey/buddy/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            package com.shrey.buddy.desktop

            object BuildInfo {
                const val SUPABASE_URL = "$url"
                const val SUPABASE_KEY = "$key"
                const val VERSION_CODE = $buddyVersionCode
                const val VERSION_NAME = "$buddyVersionName"
            }
            """.trimIndent(),
        )
    }
}

sourceSets.main {
    kotlin.srcDir(copyShared)
    kotlin.srcDir(writeBuildInfo)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(platform("io.github.jan-tennert.supabase:bom:3.8.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Global hotkey (works while other apps have focus).
    implementation("com.github.kwhat:jnativehook:2.2.2")
    // Bringing the bar to the front (Windows blocks plain focus requests from background apps).
    implementation("net.java.dev.jna:jna-platform:5.18.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.shrey.buddy.desktop.MainKt"
        // jpackage (the installer builder) comes with the JDK 21 toolchain, not with Android Studio's JBR.
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(21)
            vendor = JvmVendorSpec.ADOPTIUM
        }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Djava.net.preferIPv4Stack=true", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Buddy"
            packageVersion = buddyVersionName
            description = "Buddy for Windows"
            vendor = "Buddy"
            modules("java.naming", "java.net.http", "jdk.unsupported", "java.management", "java.desktop", "java.prefs")
            windows {
                // Same id forever, so a newer installer upgrades the installed one in place.
                upgradeUuid = "6b0f3c9e-3d2a-4c55-9a51-2f7b8d0c1e42"
                perUserInstall = true
                menuGroup = "Buddy"
                shortcut = true
                dirChooser = false
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
