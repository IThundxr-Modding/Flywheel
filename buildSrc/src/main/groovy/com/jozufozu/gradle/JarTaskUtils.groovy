package com.jozufozu.gradle

import groovy.transform.CompileStatic
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.RemapSourcesJarTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Javadoc

@CompileStatic
class JarTaskUtils {
    /**
     * We have duplicate packages between the common and platform dependent subprojects.
     * In theory the package-info.java files should be identical, so just take the first one we find.
     */
    static void excludeDuplicatePackageInfos(AbstractCopyTask copyTask) {
        copyTask.filesMatching('**/package-info.java') { FileCopyDetails details ->
            details.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    /**
     * The compile/javadoc tasks have a different base type that isn't so smart about exclusion handling.
     */
    static void excludeDuplicatePackageInfos(SourceTask sourceTask) {
        sourceTask.exclude('**/package-info.java')
    }

}
