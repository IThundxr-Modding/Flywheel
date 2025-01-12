plugins {
    idea
    java
    `maven-publish`
    id("dev.architectury.loom")
    id("flywheel.subproject")
    id("flywheel.platform")
}

subproject.init(property("flywheel_group") as String, property("flywheel_version") as String)

val api = sourceSets.create("api")
val lib = sourceSets.create("lib")
val backend = sourceSets.create("backend")
val stubs = sourceSets.create("stubs")
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
    sourceSet(stubs) {
        rootCompile()
    }
    sourceSet(main) {
        compile(api, lib, backend, stubs)
    }
    sourceSet(testMod) {
        rootCompile()
    }

    createCompileConfigurations()
}

platform {
    commonProject = project(":common")
    compileWithCommonSourceSets(api, lib, backend, stubs, main)
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

val config = project.configurations.register("flywheelForge") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

project.artifacts.add(config.name, jarSets.mainSet.remapJar)

defaultPackageInfos {
    sources(api, lib, backend, main)
}

loom {
    mixin {
        useLegacyMixinAp = true
        add(main, "flywheel.refmap.json")
        add(backend, "backend-flywheel.refmap.json")
    }

    forge {
        mixinConfig("flywheel.backend.mixins.json")
        mixinConfig("flywheel.impl.mixins.json")
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

    "forApi"(project(path = ":common", configuration = "apiClasses"))
    "forLib"(project(path = ":common", configuration = "libClasses"))
    "forBackend"(project(path = ":common", configuration = "backendClasses"))
    "forStubs"(project(path = ":common", configuration = "stubsClasses"))
    "forMain"(project(path = ":common", configuration = "mainClasses"))

    "forLib"(project(path = ":common", configuration = "libResources"))
    "forBackend"(project(path = ":common", configuration = "backendResources"))
    "forMain"(project(path = ":common", configuration = "mainResources"))
}
