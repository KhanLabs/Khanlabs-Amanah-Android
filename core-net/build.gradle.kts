// Pure Kotlin, no Android imports: device identity, the HTTPS server and client, protocol v1 send and receive.
// Tested on the JVM, including against the Windows app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-protocol"))
    api(libs.okhttp)
    implementation(libs.bouncycastle.pkix)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
