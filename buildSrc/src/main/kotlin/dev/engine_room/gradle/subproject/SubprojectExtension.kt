package dev.engine_room.gradle.subproject

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources

open class SubprojectExtension(val project: Project) {
    fun init(group: String, version: String) {
        setBaseProperties(group, version)
        setupJava()
        addRepositories()
        setupLoom()
        setupDependencies()
        configureTasks()
        setupPublishing()
    }

    private fun setBaseProperties(group: String, version: String) {
        val dev = System.getenv("RELEASE")?.contentEquals("false", true) ?: true
        val buildNumber = System.getenv("BUILD_NUMBER")

        val versionSuffix = if (dev && buildNumber != null) "-${buildNumber}" else ""

        project.group = group
        project.version = "${version}${versionSuffix}"

        project.the<BasePluginExtension>().apply {
            archivesName = "flywheel-${project.name}-${project.property("artifact_minecraft_version")}"
        }
    }

    private fun setupLoom() {
        val loom = project.the<LoomGradleExtensionAPI>()
        loom.silentMojangMappingsLicense()
    }

    private fun setupJava() {
        val java_version: String by project

        project.the<JavaPluginExtension>().apply {
            val javaVersion = JavaVersion.toVersion(java_version)
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion

            toolchain.languageVersion = JavaLanguageVersion.of(java_version)

            withSourcesJar()
            withJavadocJar()
        }
    }

    private fun addRepositories() {
        project.repositories.apply {
            mavenCentral()
            maven("https://maven.parchmentmc.org") {
                name = "ParchmentMC"
            }
            maven("https://maven.tterrag.com/") {
                name = "tterrag maven"
            }
            maven("https://www.cursemaven.com") {
                name = "CurseMaven"
                content {
                    includeGroup("curse.maven")
                }
            }
            maven("https://api.modrinth.com/maven") {
                name = "Modrinth"
                content {
                    includeGroup("maven.modrinth")
                }
            }
        }
    }

    @Suppress("UnstableApiUsage")
    private fun setupDependencies() {
        project.dependencies.apply {
            val minecraft_version: String by project
            val parchment_minecraft_version: String by project
            val parchment_version: String by project
            val loom = project.the<LoomGradleExtensionAPI>()

            add("minecraft", "com.mojang:minecraft:${minecraft_version}")

            add("mappings", loom.layered {
                officialMojangMappings()
                parchment("org.parchmentmc.data:parchment-${parchment_minecraft_version}:${parchment_version}@zip")
            })

            add("api", "com.google.code.findbugs:jsr305:3.0.2")
        }
    }

    private fun configureTasks() {
        val java_version: String by project

        project.tasks.apply {
            // make builds reproducible
            withType<AbstractArchiveTask>().configureEach {
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }

            // module metadata is often broken on multi-platform projects
            withType<GenerateModuleMetadata>().configureEach {
                enabled = false
            }

            withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
                options.release = Integer.parseInt(java_version)
                options.compilerArgs.add("-Xdiags:verbose")
            }

            withType<Jar>().configureEach {
                from("${project.rootDir}/LICENSE.md") {
                    into("META-INF")
                }
            }

            withType<Javadoc>().configureEach {
                options.optionFiles(project.rootProject.file("javadoc-options.txt"))
                options.encoding = "UTF-8"
            }

            val replaceProperties = processResourcesExpandProperties.associateWith { project.property(it) as String }

            withType<ProcessResources>().configureEach {
                inputs.properties(replaceProperties)

                filesMatching(processResourcesExpandFiles) {
                    expand(replaceProperties)
                }
            }
        }
    }

    private fun setupPublishing() {
        project.the<PublishingExtension>().repositories.apply {
            maven("file://${project.rootProject.projectDir}/mcmodsrepo")

            if (project.hasProperty("mavendir")) {
                maven(project.rootProject.file(project.property("mavendir") as String))
            }
        }
    }
}

val processResourcesExpandFiles = listOf("pack.mcmeta", "fabric.mod.json", "META-INF/mods.toml")

val processResourcesExpandProperties = listOf(
    "mod_license",
    "mod_sources",
    "mod_issues",
    "mod_homepage",
    "flywheel_id",
    "flywheel_name",
    "flywheel_description",
    "flywheel_version",
    "vanillin_id",
    "vanillin_name",
    "vanillin_version",
    "vanillin_description",
    "flywheel_maven_version_range",
    "flywheel_semver_version_range",
    "minecraft_semver_version_range",
    "minecraft_maven_version_range",
    "fabric_api_version_range",
    "forge_version_range",
)
