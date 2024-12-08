plugins {
    idea
    java
    `maven-publish`
    id("dev.architectury.loom")
    id("flywheel.subproject")
    id("flywheel.platform")
}

val api = sourceSets.create("api")
val lib = sourceSets.create("lib")
val backend = sourceSets.create("backend")
val main = sourceSets.getByName("main")
val testMod = sourceSets.create("testMod")

transitiveSourceSets {
    compileClasspath = main.compileClasspath

    sourceSet(api) {
        rootCompile()
    }
    sourceSet(lib) {
        rootCompile()
        compile(api)
    }
    sourceSet(backend) {
        rootCompile()
        compile(api, lib)
    }
    sourceSet(main) {
        compile(api, lib, backend)
    }
    sourceSet(testMod) {
        rootCompile()
    }

    createCompileConfigurations()
}

platform {
    commonProject = project(":common")
    compileWithCommonSourceSets(api, lib, backend, main)
    setupLoomMod(api, lib, backend, main)
    setupLoomRuns()
    setupFatJar(api, lib, backend, main)
    setupTestMod(testMod)
}

jarSets {
    mainSet.publish(platform.modArtifactId)
    create("api", api, lib).apply {
        addToAssemble()
        publish(platform.apiArtifactId)

        configureJar {
            manifest {
                attributes("Fabric-Loom-Remap" to "true")
            }
        }
    }
}

defaultPackageInfos {
    sources(api, lib, backend, main)
}

loom {
    mixin {
        useLegacyMixinAp = true
        add(main, "flywheel.refmap.json")
        add(backend, "backend-flywheel.refmap.json")
    }

    runs {
        configureEach {
            property("forge.logging.markers", "")
            property("forge.logging.console.level", "debug")
        }
    }
}

repositories {
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    neoForge("net.neoforged:neoforge:${property("neoforge_version")}")

    modCompileOnly("maven.modrinth:sodium:${property("sodium_version")}-neoforge")
    modCompileOnly("maven.modrinth:iris:${property("iris_version")}-neoforge")

    modCompileOnly("maven.modrinth:embeddium:${property("embeddium_version")}")

    "forApi"(project(path = ":common", configuration = "commonApiOnly"))
    "forLib"(project(path = ":common", configuration = "commonLib"))
    "forBackend"(project(path = ":common", configuration = "commonBackend"))
    "forMain"(project(path = ":common", configuration = "commonImpl"))
}
