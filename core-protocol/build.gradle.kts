// Pure Kotlin, no Android imports: the protocol rules and formats, tested on the JVM against docs/v2/vectors.json.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("klshare.docs", rootProject.file("docs").absolutePath)
}
