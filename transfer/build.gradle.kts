plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.khanlabs.lanshare.transfer"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-net"))
    api(project(":core-store"))
    api(project(":core-link"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
