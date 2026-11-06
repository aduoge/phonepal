plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Copies `skills/examples/<name>/...` into an asset folder `skills/<name>/...` of the APK. */
abstract class BundleSkillsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val examples: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun bundle() {
        fs.sync {
            from(examples)
            into(output.dir("skills"))
            exclude("**/.*")
        }
    }
}

val bundleSkills = tasks.register<BundleSkillsTask>("bundleSkillAssets") {
    examples.set(rootProject.layout.projectDirectory.dir("skills/examples"))
    output.set(layout.buildDirectory.dir("generated/skill-assets"))
}

android {
    namespace = "dev.xiaoman.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.xiaoman.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin") }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleSkills, BundleSkillsTask::output)
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-api"))
    implementation(project(":core-llm"))
    implementation(project(":core-memory"))
    implementation(project(":core-skills"))
    implementation(project(":core-sentinel"))
    implementation(project(":core-agent"))
    implementation(project(":core-mcp"))
    implementation(project(":hands"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
