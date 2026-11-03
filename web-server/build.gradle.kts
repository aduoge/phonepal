plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
kotlin { jvmToolchain(17) }
application {
    mainClass.set("dev.phonepal.web.MainKt")
    applicationName = "web-server"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-llm"))
    implementation(project(":core-mcp"))
    implementation(project(":core-sentinel"))
    implementation(libs.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
