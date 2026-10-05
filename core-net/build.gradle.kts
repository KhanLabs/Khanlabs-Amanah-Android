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

// The receiver in conformance mode, for the Windows repo's black-box tests (test code, never in the app):
//     gradlew :core-net:conformanceTarget       then  python -m pytest conformance --target 127.0.0.1:47890
tasks.register<JavaExec>("conformanceTarget") {
    description = "Runs the Kotlin receiver in conformance mode on 127.0.0.1:47890."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.khanlabs.lanshare.net.ConformanceTargetKt")
    args = (findProperty("lan") as String?)?.let { listOf("lan") } ?: emptyList()
}

// Sends with the Kotlin sender, for testing against another device (test code, never in the app):
//     gradlew :core-net:sendTo -Pto=192.168.1.20:47820 -Pid=<device ID or -> -Pfiles="a.txt;photos" -Ptext=hi
tasks.register<JavaExec>("sendTo") {
    description = "Sends files and text to a device with the Kotlin sender."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.khanlabs.lanshare.net.SendCliKt")
    fun prop(name: String) = findProperty(name) as String?
    args = listOf(prop("to") ?: "", prop("id") ?: "-", prop("text") ?: "") +
        (prop("files")?.split(';')?.filter { it.isNotBlank() } ?: emptyList())
}
