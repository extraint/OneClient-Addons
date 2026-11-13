pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "polymedia"
