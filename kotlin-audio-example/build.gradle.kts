import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.becash.becashplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.becash.becashplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "DEFAULT_SERVER_URL",     "\"${localProps["DEFAULT_SERVER_URL"]}\"")
        buildConfigField("String", "DEFAULT_USERNAME",       "\"${localProps["DEFAULT_USERNAME"]}\"")
        buildConfigField("String", "DEFAULT_PASSWORD",       "\"${localProps["DEFAULT_PASSWORD"]}\"")
        buildConfigField("String", "DEFAULT_REMOTE_FOLDER",  "\"${localProps["DEFAULT_REMOTE_FOLDER"]}\"")
        buildConfigField("String", "DEFAULT_MYSQL_HOST",     "\"${localProps["DEFAULT_MYSQL_HOST"]}\"")
        buildConfigField("int",    "DEFAULT_MYSQL_PORT",     "${localProps["DEFAULT_MYSQL_PORT"]}")
        buildConfigField("String", "DEFAULT_MYSQL_USER",     "\"${localProps["DEFAULT_MYSQL_USER"]}\"")
        buildConfigField("String", "DEFAULT_MYSQL_PASSWORD", "\"${localProps["DEFAULT_MYSQL_PASSWORD"]}\"")
        buildConfigField("String", "DEFAULT_MYSQL_DB",       "\"${localProps["DEFAULT_MYSQL_DB"]}\"")
        buildConfigField("String", "BARIERA9",               "\"${localProps["BARIERA9"]}\"")
        buildConfigField("String", "BARIERA10",              "\"${localProps["BARIERA10"]}\"")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = false
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/MANIFEST.MF"
            excludes += "META-INF/native-image/**"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0") {
            because("kotlin-stdlib-jdk7 is now a part of kotlin-stdlib")
        }
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0") {
            because("kotlin-stdlib-jdk8 is now a part of kotlin-stdlib")
        }
    }

    implementation(project(":kotlin-audio"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("mysql:mysql-connector-java:5.1.49")
    implementation("io.sentry:sentry-android:7.19.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.6.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
