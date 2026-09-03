pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "iov-vehicle-ivi-ivai"

include(":app-demo")
include(":service-ai-agent")
include(":agent-core")
include(":model-client")
include(":tool-registry")
include(":tool-runtime")
include(":adapter-vehicle-mock")
include(":retrieval")
include(":observability")
