// FlexPad — cấu hình kho mã nguồn cho toàn project.
// Chỉ dùng kho của Google + Maven Central + Gradle Plugin Portal.
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
    }
}
rootProject.name = "FlexPad"
include(":app")
