plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ziyao.huang.unzip.archive.rar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
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
}
