pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "JellyCast"

include(":app")
include(":core:model")
include(":core:network")

// 后续 Task 逐步 include(目录尚不存在):
// include(":core:database", ":core:datastore",
//         ":core:player", ":core:subtitle", ":core:designsystem")
// include(":feature:server", ":feature:home", ":feature:library",
//         ":feature:player", ":feature:settings")
