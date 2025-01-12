import dev.engine_room.gradle.jarset.JarTaskSet
import org.gradle.jvm.tasks.Jar

plugins {
    idea
    java
    `maven-publish`
    id("dev.architectury.loom")
    id("flywheel.subproject")
    id("flywheel.platform")
}

group = "dev.engine_room.vanillin"

val main = sourceSets.getByName("main")

platform {
    modArtifactId = "vanillin-fabric-${project.property("artifact_minecraft_version")}"
    commonProject = project(":common")
    setupLoomRuns()
}

listOf("api", "lib")
    .map { project(":fabric").sourceSets.named(it).get() }
    .forEach { main.compileClasspath += it.output }

val commonSourceSet = platform.commonSourceSets.named("vanillin").get()

tasks.named<Javadoc>("javadoc").configure {
    source(commonSourceSet.allJava)

    JarTaskSet.excludeDuplicatePackageInfos(this)
}

tasks.named<Jar>("sourcesJar").configure {
    from(commonSourceSet.allJava)

    JarTaskSet.excludeDuplicatePackageInfos(this)
}

tasks.withType<JavaCompile>().configureEach {
    JarTaskSet.excludeDuplicatePackageInfos(this)
}
tasks.named<JavaCompile>(main.compileJavaTaskName).configure {
    source(commonSourceSet.allJava)
}
tasks.named<ProcessResources>(main.processResourcesTaskName).configure {
    from(commonSourceSet.resources)
}

jarSets {
    mainSet.publish(platform.modArtifactId)
}

defaultPackageInfos {
    sources(main)
}

loom {
    mixin {
        useLegacyMixinAp = true
        add(main, "vanillin.refmap.json")
    }
}

dependencies {
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modApi("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    modCompileOnly("maven.modrinth:sodium:${property("sodium_version")}")

    compileOnly(project(path = ":common", configuration = "commonVanillin"))
    include(project(path = ":fabric", configuration = "flywheelFabric"))
    modRuntimeOnly(project(path = ":fabric", configuration = "flywheelFabric"))
}
