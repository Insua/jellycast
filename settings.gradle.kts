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
include(":core:datastore")
include(":core:database")
include(":core:player")
include(":core:subtitle")
include(":core:designsystem")
include(":feature:server")
include(":feature:home")
include(":feature:library")
include(":feature:player")

// 后续 Task 逐步 include(目录尚不存在):
// include(":feature:settings")
