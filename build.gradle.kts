plugins {
    kotlin("jvm") version "2.3.0"
    `maven-publish`
}

group = "simpledisplays"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.graalvm.polyglot:polyglot:24.2.2")
    runtimeOnly("org.graalvm.polyglot:js-community:24.2.2")
    testImplementation(kotlin("test"))
    testImplementation("net.kyori:adventure-api:4.26.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val apiJar by tasks.registering(Jar::class) {
    archiveBaseName.set("simpledisplays-api")
    from(sourceSets.main.get().output.classesDirs) {
        include("simpledisplays/api/**", "META-INF/*.kotlin_module")
    }
}

tasks.assemble {
    dependsOn(apiJar)
}

publishing {
    publications {
        register<MavenPublication>("displayApi") {
            artifactId = "simpledisplays-api"
            artifact(apiJar)
        }
    }
}

tasks.test { useJUnitPlatform() }
