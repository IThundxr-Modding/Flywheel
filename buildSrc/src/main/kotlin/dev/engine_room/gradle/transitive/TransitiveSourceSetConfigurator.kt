package dev.engine_room.gradle.transitive

import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.named
import org.gradle.language.jvm.tasks.ProcessResources

class TransitiveSourceSetConfigurator(private val parent: TransitiveSourceSetsExtension, private val sourceSet: SourceSet) {
    internal val compileSourceSets = mutableSetOf<SourceSet>()
    internal val runtimeSourceSets = mutableSetOf<SourceSet>()

    fun rootCompile() {
        parent.compileClasspath?.let { sourceSet.compileClasspath = it }
    }

    fun rootRuntime() {
        parent.runtimeClasspath?.let { sourceSet.runtimeClasspath = it }
    }

    fun rootImplementation() {
        rootCompile()
        rootRuntime()
    }

    fun compile(vararg sourceSets: SourceSet) {
        compileSourceSets += sourceSets
        for (sourceSet in sourceSets) {
            this.sourceSet.compileClasspath += sourceSet.output
        }
    }

    fun runtime(vararg sourceSets: SourceSet) {
        runtimeSourceSets += sourceSets
        for (sourceSet in sourceSets) {
            this.sourceSet.runtimeClasspath += sourceSet.output
        }
    }

    fun implementation(vararg sourceSets: SourceSet) {
        compile(*sourceSets)
        runtime(*sourceSets)
    }

    fun outgoing() {
        outgoingClasses()
        outgoingResources()
    }

    fun outgoingResources() {
        val project = parent.project
        val exportResources = project.configurations.register("${sourceSet.name}Resources") {
            isCanBeResolved = false
            isCanBeConsumed = true
        }
        val processResources = project.tasks.named<ProcessResources>(sourceSet.processResourcesTaskName).get()

        project.artifacts.add(exportResources.name, processResources.destinationDir) {
            builtBy(processResources)
        }
    }

    fun outgoingClasses() {
        val project = parent.project
        val exportClasses = project.configurations.register("${sourceSet.name}Classes") {
            isCanBeResolved = false
            isCanBeConsumed = true
        }

        val compileTask = project.tasks.named<JavaCompile>(sourceSet.compileJavaTaskName).get()
        project.artifacts.add(exportClasses.name, compileTask.destinationDirectory) {
            builtBy(compileTask)
        }
    }
}
