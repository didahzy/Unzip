plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ziyao.huang.unzip.archive.rar"
    compileSdk {
        version = release(37)
    }

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The connected tests keep their binary fixtures in src/androidTest/resources
    // (per the task brief); wire that directory in as androidTest assets so the
    // files ship inside the test APK and are reachable via Instrumentation.context.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("src/androidTest/resources")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":archive-core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
