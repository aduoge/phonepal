plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Optional out-of-tree build output, used by scripts/gradle.sh so a checkout stays clean.
System.getenv("PHONEPAL_BUILD_DIR")?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${if (path == ":") "root" else path.removePrefix(":").replace(':', '/')}"))
    }
}
