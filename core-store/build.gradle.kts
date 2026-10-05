plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.khanlabs.lanshare.store"
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
    implementation(libs.androidx.core.ktx)
}
