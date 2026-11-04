plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core-api"))
    implementation(libs.snakeyaml.engine)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    val examples = rootProject.file("skills/examples")
    inputs.dir(examples).withPropertyName("skillExamples")
    systemProperty("xiaoman.skills.examples", examples.path)
}
