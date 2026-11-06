plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
kotlin { jvmToolchain(17) }
application {
    mainClass.set("dev.xiaoman.web.MainKt")
    applicationName = "web-server"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-llm"))
    implementation(project(":core-mcp"))
    implementation(project(":core-memory"))
    implementation(project(":core-sentinel"))
    implementation(libs.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Browser-side helpers (memory store, SSE parsing): `node --test`, skipped when Node.js is not installed.
val jsTest by tasks.registering(Exec::class) {
    description = "Runs the web page's JavaScript unit tests with Node.js when available."
    val script = layout.projectDirectory.file("src/test/js/app.test.js")
    inputs.file(script)
    inputs.dir(layout.projectDirectory.dir("src/main/resources/web"))
    val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.canExecute() }
    onlyIf("Node.js on PATH") { node != null }
    commandLine(node?.path ?: "node", "--test", script.asFile.path)
}
tasks.named("check") { dependsOn(jsTest) }
