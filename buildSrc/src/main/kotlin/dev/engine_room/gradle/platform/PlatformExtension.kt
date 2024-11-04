package dev.engine_room.gradle.platform

import dev.engine_room.gradle.jarset.JarTaskSet
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

open class PlatformExtension(val project: Project) {
    var commonProject: Project by DependentProject(this.project)

    var modArtifactId: String = "flywheel-${project.name}-${project.property("artifact_minecraft_version")}"

    var apiArtifactId: String = "flywheel-${project.name}-api-${project.property("artifact_minecraft_version")}"

    private val commonSourceSets: SourceSetContainer by lazy { commonProject.the<SourceSetContainer>() }

    fun setupLoomMod(vararg sourceSets: SourceSet) {
        project.the<LoomGradleExtensionAPI>().mods.maybeCreate("main").apply {
            sourceSets.forEach(::sourceSet)
        }
    }

    fun setupLoomRuns() {
        project.the<LoomGradleExtensionAPI>().runs.apply {
            named("client") {
                isIdeConfigGenerated = true

                // Turn on our own debug flags
                property("flw.dumpShaderSource", "true")
                property("flw.debugMemorySafety", "true")

                // Turn on mixin debug flags
                property("mixin.debug.export", "true")
                property("mixin.debug.verbose", "true")

                // 720p baby!
                programArgs("--width", "1280", "--height", "720")
            }

            // We're a client mod, but we need to make sure we correctly render when playing on a server.
            named("server") {
                isIdeConfigGenerated = true
                programArgs("--nogui")
            }
        }
    }

    fun compileWithCommonSourceSets(vararg sourceSets: SourceSet) {
        project.tasks.apply {
            withType<JavaCompile>().configureEach {
                JarTaskSet.excludeDuplicatePackageInfos(this)
            }

            sourceSets.forEach {
                val commonSourceSet = commonSourceSets.named(it.name).get()

                named<JavaCompile>(it.compileJavaTaskName).configure {
                    source(commonSourceSet.allJava)
                }
                named<ProcessResources>(it.processResourcesTaskName).configure {
                    from(commonSourceSet.resources)
                }
            }
        }
    }

    fun setupFatJar(vararg sourceSets: SourceSet) {
        project.tasks.apply {
            val extraSourceSets = sourceSets.filter { it.name != "main" }.toList()
            val commonSources = sourceSets.map { commonSourceSets.named(it.name).get() }

            named<Jar>("jar").configure {
                extraSourceSets.forEach { from(it.output) }

                JarTaskSet.excludeDuplicatePackageInfos(this)
            }

            named<Javadoc>("javadoc").configure {
                commonSources.forEach { source(it.allJava) }
                extraSourceSets.forEach { source(it.allJava) }

                JarTaskSet.excludeDuplicatePackageInfos(this)
            }

            named<Jar>("sourcesJar").configure {
                commonSources.forEach { from(it.allJava) }
                extraSourceSets.forEach { from(it.allJava) }

                JarTaskSet.excludeDuplicatePackageInfos(this)
            }
        }
    }

    fun setupTestMod(sourceSet: SourceSet) {
        project.tasks.apply {
            val testModJar = register<Jar>("testModJar") {
                from(sourceSet.output)
                val file = File(project.layout.buildDirectory.asFile.get(), "devlibs");
                destinationDirectory.set(file)
                archiveClassifier = "testmod"
            }

            val remapTestModJar = register<RemapJarTask>("remapTestModJar") {
                dependsOn(testModJar)
                inputFile.set(testModJar.get().archiveFile)
                archiveClassifier = "testmod"
                addNestedDependencies = false
                classpath.from(sourceSet.compileClasspath)
            }

            named<Task>("build").configure {
                dependsOn(remapTestModJar)
            }
        }
    }

    private class DependentProject(private val thisProject: Project) : ReadWriteProperty<Any?, Project> {
        private var value: Project? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): Project {
            return value ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Project) {
            this.value = value
            thisProject.evaluationDependsOn(value.path)
        }

        override fun toString(): String =
            "NotNullProperty(${if (value != null) "value=$value" else "value not initialized yet"})"
    }
}
