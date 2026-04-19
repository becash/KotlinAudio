plugins {
    id("com.android.library")
    id("kotlin-android")
    id("maven-publish")
    id("de.mannodermaus.android-junit5")
}

val versionNumber = "2.0.0"

group = "com.github.doublesymmetry"
version = versionNumber

android {
    namespace = "com.doublesymmetry.kotlinaudio"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["runnerBuilder"] = "de.mannodermaus.junit5.AndroidJUnit5Builder"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
}

dependencies {
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.media:media:1.7.0")

    // Media3 — înlocuiește ExoPlayer 2.x
    api("androidx.media3:media3-exoplayer:1.3.1")
    api("androidx.media3:media3-exoplayer-dash:1.3.1")
    api("androidx.media3:media3-exoplayer-hls:1.3.1")
    api("androidx.media3:media3-exoplayer-smoothstreaming:1.3.1")
    api("androidx.media3:media3-session:1.3.1")
    api("androidx.media3:media3-ui:1.3.1")
    api("androidx.media3:media3-datasource:1.3.1")
    api("com.jakewharton.timber:timber:5.0.1")

    implementation("androidx.test:rules:1.5.0")

    androidTestImplementation("org.awaitility:awaitility:4.2.0") { isTransitive = false }
    androidTestImplementation("org.awaitility:awaitility-kotlin:4.2.0") { isTransitive = false }
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
    androidTestImplementation("de.mannodermaus.junit5:android-test-core:1.3.0")
    androidTestRuntimeOnly("de.mannodermaus.junit5:android-test-runner:1.3.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(tasks["sourcesJar"])
                groupId = "com.github.doublesymmetry"
                artifactId = "kotlin-audio"
                version = versionNumber
            }
        }
    }
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}
