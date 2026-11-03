plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("dev.phonepal.ios.runner.MainKt")
    applicationName = "ios-runner"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-llm"))
    implementation(project(":core-memory"))
    implementation(project(":core-skills"))
    implementation(project(":core-sentinel"))
    implementation(project(":ios-hands"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
