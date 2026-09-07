plugins {
    id("com.android.application")
}

android {
    namespace = "com.caddyai2.siyimk15teleop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.caddyai2.siyimk15teleop"
        // The MK15 handset runs Android 9 / API 28 — verified via `adb shell getprop
        // ro.build.version.sdk`. jros2-android's own floor is API 26, so 28 clears it.
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by jros2-android to use java.time / streams APIs down to API 26.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }
}

configurations.all {
    // kotlin-stdlib-jdk7/-jdk8 were merged into kotlin-stdlib in Kotlin 1.8, but some
    // transitive dependencies still pull the old split artifacts alongside a newer
    // unified kotlin-stdlib, producing duplicate-class failures at merge time.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation("us.ihmc:jros2-android:1.5.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
