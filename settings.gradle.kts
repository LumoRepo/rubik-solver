pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RubikSolver"

include(":composeApp")
project(":composeApp").projectDir = file("composeApp")
include(":rubik-model")
include(":rubik-solver")
// Future modules:
include(":rubik-vision")
// include(":rubik-ar")
