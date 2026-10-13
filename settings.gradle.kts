pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The JVM half of rustls-platform-verifier, published by upstream.
        maven {
            url = uri(
                "https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/" +
                    "android-release-support/maven/",
            )
        }
    }
}

rootProject.name = "decdn-android-demo"
include(":app")
