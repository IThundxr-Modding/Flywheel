package dev.engine_room.gradle.jarset

import net.fabricmc.loom.task.AbstractRemapJarTask
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.RemapSourcesJarTask
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.language.jvm.tasks.ProcessResources

class JarTaskSet(
    private val project: Project,
    private val name: String,
    val jar: TaskProvider<Jar>,
    val sources: TaskProvider<Jar>,
    val javadocJar: TaskProvider<Jar>,
    val remapJar: TaskProvider<RemapJarTask>,
    val remapSources: TaskProvider<RemapSourcesJarTask>
) {

    fun publish(artifactId: String) {
        project.the<PublishingExtension>().publications {
            register<MavenPublication>("${name}RemapMaven") {
                artifact(remapJar)
                artifact(remapSources)
                artifact(javadocJar)
                this.artifactId = artifactId
            }
        }
    }

    fun outgoing(name: String) {
        outgoingRemapJar("${name}Remap")
        outgoingJar("${name}Dev")
    }

    fun outgoingRemapJar(name: String) {
        val config = project.configurations.register(name) {
            isCanBeConsumed = true
            isCanBeResolved = false
        }

        project.artifacts.add(config.name, remapJar)
    }

    fun outgoingJar(name: String) {
        val config = project.configurations.register(name) {
            isCanBeConsumed = true
            isCanBeResolved = false
        }

        project.artifacts.add(config.name, jar)
    }

    /**
     * Configure the assemble task to depend on the remap tasks and javadoc jar.
     */
    fun addToAssemble() {
        project.tasks.named("assemble").configure {
            dependsOn(remapJar, remapSources, javadocJar)
        }
    }

    /**
     * Configure the remap tasks with the given action.
     */
    fun configureRemap(action: Action<AbstractRemapJarTask>) {
        remapJar.configure(action)
        remapSources.configure(action)
    }

    /**
     * Configure the jar tasks with the given action.
     */
    fun configureJar(action: Action<Jar>) {
        jar.configure(action)
        sources.configure(action)
    }

    /**
     * Create a new JarTaskSet with the same base jars but new tasks for remapping.
     */
    fun forkRemap(newName: String): JarTaskSet {
        val remapJarTask = createRemapJar(project, newName, jar)
        val remapSourcesTask = createRemapSourcesJar(project, newName, sources)

        return JarTaskSet(project, newName, jar, sources, javadocJar, remapJarTask, remapSourcesTask)
    }

    companion object {
        private const val PACKAGE_INFOS_JAVA_PATTERN = "**/package-info.java"
        private const val BUILD_GROUP: String = "build"
        private const val LOOM_GROUP: String = "loom"
        private const val JAVADOC_CLASSIFIER: String = "javadoc"
        private const val SOURCES_CLASSIFIER: String = "sources"

        /**
         * We have duplicate packages between the common and platform dependent subprojects.
         * In theory the package-info.java files should be identical, so just take the first one we find.
         */
        fun excludeDuplicatePackageInfos(copyTask: AbstractCopyTask) {
            copyTask.filesMatching(PACKAGE_INFOS_JAVA_PATTERN) {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        }

        /**
         * The compile/javadoc tasks have a different base type that isn't so smart about exclusion handling.
         */
        fun excludeDuplicatePackageInfos(sourceTask: SourceTask) {
            sourceTask.exclude(PACKAGE_INFOS_JAVA_PATTERN)
        }

        fun create(project: Project, name: String, vararg sourceSetSet: SourceSet): JarTaskSet {
            val jarTask = createJar(project, name, sourceSetSet)
            val sourcesTask = createSourcesJar(project, name, sourceSetSet)
            val javadocJarTask = createJavadocJar(project, name, sourceSetSet)

            val remapJarTask = createRemapJar(project, name, jarTask)
            val remapSourcesTask = createRemapSourcesJar(project, name, sourcesTask)

            return JarTaskSet(project, name, jarTask, sourcesTask, javadocJarTask, remapJarTask, remapSourcesTask)
        }

        private fun createJar(
            project: Project,
            name: String,
            sourceSetSet: Array<out SourceSet>
        ): TaskProvider<Jar> {
            return project.tasks.register<Jar>("${name}Jar") {
                group = BUILD_GROUP
                destinationDirectory.set(project.layout.buildDirectory.dir("devlibs/${name}"))

                for (set in sourceSetSet) {
                    from(set.output)
                }
                excludeDuplicatePackageInfos(this)
            }
        }

        private fun createSourcesJar(
            project: Project,
            name: String,
            sourceSetSet: Array<out SourceSet>
        ): TaskProvider<Jar> {
            return project.tasks.register<Jar>("${name}SourcesJar") {
                group = BUILD_GROUP
                destinationDirectory.set(project.layout.buildDirectory.dir("devlibs/${name}"))
                archiveClassifier.set(SOURCES_CLASSIFIER)

                for (set in sourceSetSet) {
                    // In the platform projects we inject common sources into these tasks to avoid polluting the
                    // source set itself, but that bites us here, and we need to roll in all the sources in this kinda
                    // ugly way. There's probably a better way to do this but JarTaskSet can't easily accommodate it.
                    from(project.tasks.named<ProcessResources>(set.processResourcesTaskName).map { it.source })
                    from(project.tasks.named<JavaCompile>(set.compileJavaTaskName).map { it.source })
                }
                excludeDuplicatePackageInfos(this)
            }
        }

        private fun createJavadocJar(
            project: Project,
            name: String,
            sourceSetSet: Array<out SourceSet>
        ): TaskProvider<Jar> {
            val javadocTask = project.tasks.register<Javadoc>("${name}Javadoc") {
                group = BUILD_GROUP
                setDestinationDir(project.layout.buildDirectory.dir("docs/${name}-javadoc").get().asFile)

                for (set in sourceSetSet) {
                    // See comment in #createSourcesJar.
                    source(project.tasks.named<JavaCompile>(set.compileJavaTaskName).map { it.source })
                    classpath += set.compileClasspath
                }
                excludeDuplicatePackageInfos(this)
            }
            return project.tasks.register<Jar>("${name}JavadocJar") {
                dependsOn(javadocTask)
                group = BUILD_GROUP
                destinationDirectory.set(project.layout.buildDirectory.dir("libs/${name}"))
                archiveClassifier.set(JAVADOC_CLASSIFIER)

                from(javadocTask.map { it.outputs })
            }
        }

        private fun createRemapJar(
            project: Project,
            name: String,
            jar: TaskProvider<Jar>
        ): TaskProvider<RemapJarTask> {
            return project.tasks.register<RemapJarTask>("${name}RemapJar") {
                dependsOn(jar)
                group = LOOM_GROUP
                destinationDirectory.set(project.layout.buildDirectory.dir("libs/${name}"))

                inputFile.set(jar.flatMap { it.archiveFile })
            }
        }

        private fun createRemapSourcesJar(
            project: Project,
            name: String,
            jar: TaskProvider<Jar>
        ): TaskProvider<RemapSourcesJarTask> {
            return project.tasks.register<RemapSourcesJarTask>("${name}RemapSourcesJar") {
                dependsOn(jar)
                group = LOOM_GROUP
                destinationDirectory.set(project.layout.buildDirectory.dir("libs/${name}"))
                archiveClassifier.set(SOURCES_CLASSIFIER)

                inputFile.set(jar.flatMap { it.archiveFile })
            }
        }
    }
}
