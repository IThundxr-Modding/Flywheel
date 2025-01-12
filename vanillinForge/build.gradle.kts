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

subproject.init(property("vanillin_group") as String, property("vanillin_version") as String)

val main = sourceSets.getByName("main")

platform {
    modArtifactId = "vanillin-forge-${project.property("artifact_minecraft_version")}"
    commonProject = project(":common")
    setupLoomRuns()
}

listOf("api", "lib")
    .map { project(":forge").sourceSets.named(it).get() }
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

    forge {
//        mixinConfig("flywheel.backend.mixins.json")
//        mixinConfig("flywheel.impl.mixins.json")
    }

    runs {
        configureEach {
            property("forge.logging.markers", "")
            property("forge.logging.console.level", "debug")
        }
    }
}

dependencies {
    forge("net.minecraftforge:forge:${property("minecraft_version")}-${property("forge_version")}")

    modCompileOnly("maven.modrinth:embeddium:${property("embeddium_version")}")

    compileOnly(project(path = ":common", configuration = "vanillinClasses"))
    compileOnly(project(path = ":common", configuration = "vanillinResources"))

    // JiJ flywheel proper
    include(project(path = ":forge", configuration = "flywheelForge"))
    modRuntimeOnly(project(path = ":forge", configuration = "flywheelForge"))
}
