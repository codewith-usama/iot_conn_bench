plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// ---- Thesis build switches --------------------------------------------------------------
// enableMatter=true links the real connectedhomeip Android controller (see docs/MATTER.md).
val enableMatter: Boolean = (findProperty("enableMatter") as String?)?.toBoolean() ?: true

// Pinned, verified dependency versions (see docs/DEPENDENCIES.md for rationale).
val coroutinesVersion = "1.10.2"
val hivemqVersion = "1.3.7"
val matterDemoSdkVersion = "1.0"

val sourceRevision: String = try {
    val p = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0 && out.isNotBlank()) out else "unknown"
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "com.thesis.iotconnbench"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.thesis.iotconnbench"
        // 27: required by com.google.matter:matter-android-demo-sdk (its manifest minSdk).
        minSdk = 27
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
        buildConfigField(
            "String", "MATTER_SDK",
            if (enableMatter) "\"com.google.matter:matter-android-demo-sdk:$matterDemoSdkVersion\"" else "\"disabled\"",
        )
        buildConfigField("String", "MQTT_CLIENT", "\"com.hivemq:hivemq-mqtt-client:$hivemqVersion\"")
        buildConfigField("boolean", "MATTER_ENABLED", enableMatter.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (enableMatter) "src/matter/kotlin" else "src/noMatter/kotlin")
        }
    }

    packaging {
        resources {
            // Netty (HiveMQ client) ships duplicate metadata files.
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            // Research builds: debug signing for local installation only.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("com.hivemq:hivemq-mqtt-client:$hivemqVersion")
    if (enableMatter) {
        implementation("com.google.matter:matter-android-demo-sdk:$matterDemoSdkVersion")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
