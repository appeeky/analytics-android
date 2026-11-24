plugins {
    id("com.android.library")
    `maven-publish`
}

group = "com.appeeky"
version = "0.2.0"

android {
    namespace = "com.appeeky.analytics"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.all { test ->
            test.testLogging { events("passed", "skipped", "failed") }
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Maven coordinates com.appeeky:analytics. Not published anywhere yet; see README.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.appeeky"
            artifactId = "analytics"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Appeeky Analytics")
                description.set("Android SDK for Appeeky Analytics: installs, sessions, events and install attribution.")
            }
        }
    }
}
