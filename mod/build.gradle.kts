plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom")
}

val mc: String by project
val modVersion: String by project

// Per-Minecraft-version dependency set.
data class Target(val oneConfig: String, val fabricApi: String, val adapterDir: String)

val target = when {
    mc.startsWith("26.1") -> Target("26.1-fabric", "0.155.3+26.1.2", "src/mc-26.1")
    mc.startsWith("26.3") -> Target("26.3-fabric", "0.166.2+26.3", "src/mc-26.3")
    else -> error("Unsupported Minecraft version $mc (supported: 26.1.x, 26.3)")
}

version = "$modVersion+$mc"

repositories {
    maven("https://repo.polyfrost.org/releases")
    maven("https://maven.fabricmc.net")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")

    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:${target.fabricApi}")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
    // API only: the real OneConfig mod provides it (and Compose) at runtime.
    compileOnly("org.polyfrost.oneconfig:${target.oneConfig}:1.7.1") { isTransitive = false }
    for (module in listOf("config", "config-impl", "ui", "utils", "events")) {
        compileOnly("org.polyfrost.oneconfig:$module:1.7.1") { isTransitive = false }
    }

    testImplementation(kotlin("test"))
}

sourceSets.main {
    kotlin.srcDir(target.adapterDir)
    // ^ version-specific adapter (main render target location differs per Minecraft version)
}

kotlin { jvmToolchain(25) }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

tasks.test { useJUnitPlatform() }

tasks.processResources {
    val props = mapOf("version" to version, "mc" to mc)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
