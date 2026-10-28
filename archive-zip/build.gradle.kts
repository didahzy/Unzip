plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":archive-core"))
    implementation(libs.archive.zip4j)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
