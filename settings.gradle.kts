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

// 后续 Task 逐步 include(目录尚不存在,Task 1 只构建 :app):
// include(":core:network", ":core:database", ":core:datastore",
//         ":core:player", ":core:subtitle", ":core:designsystem")
// include(":feature:server", ":feature:home", ":feature:library",
//         ":feature:player", ":feature:settings")
