pluginManagement {
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/")
    }
}

rootProject.name = "xiaoman"

include(
    ":core-api",
    ":core-llm",
    ":core-memory",
    ":core-skills",
    ":core-sentinel",
    ":core-agent",
    ":hands",
    ":app",
)
