plugins {
    idea
    java
    `maven-publish`
    id("dev.architectury.loom")
    id("flywheel.subproject")
}

subproject.init(property("flywheel_group") as String, property("flywheel_version") as String)

val api = sourceSets.create("api")
val lib = sourceSets.create("lib")
val backend = sourceSets.create("backend")
val stubs = sourceSets.create("stubs")
val main = sourceSets.getByName("main")
val vanillin = sourceSets.create("vanillin")

transitiveSourceSets {
    compileClasspath = main.compileClasspath

    sourceSet(api) {
        rootCompile()
        outgoingClasses()
    }
    sourceSet(lib) {
        rootCompile()
        compile(api)
        outgoing()
    }
    sourceSet(backend) {
        rootCompile()
        compile(api, lib)
        outgoing()
    }
    sourceSet(stubs) {
        rootCompile()
        outgoingClasses()
    }
    sourceSet(main) {
        compile(api, lib, backend, stubs)
        outgoing()
    }
    sourceSet(sourceSets.getByName("test")) {
        implementation(api, lib, backend)
    }
    sourceSet(vanillin) {
        rootCompile()
        compile(api, lib)
        outgoing()
    }
}

defaultPackageInfos {
    sources(api, lib, backend, main, vanillin)
}

jarSets {
    // For publishing.
    create("api", api, lib).apply {
        addToAssemble()
        publish("flywheel-common-intermediary-api-${property("artifact_minecraft_version")}")

        configureJar {
            manifest {
                attributes("Fabric-Loom-Remap" to "true")
            }
        }

        // Don't publish the un-remapped jars because they don't have the correct manifest populated by Loom.
        forkRemap("apiMojmap").apply {
            addToAssemble()
            configureRemap {
                // "named" == mojmap
                // We're probably remapping from named to named so Loom should noop this.
                targetNamespace = "named"
            }

            publish("flywheel-common-mojmap-api-${property("artifact_minecraft_version")}")
        }
    }
}

dependencies {
    modCompileOnly("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
}

tasks.test {
    useJUnitPlatform()
}
