import org.gradle.api.artifacts.dsl.LockMode

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val resolveIdeRuntimeClasspathCopyLocks = tasks.register("resolveIdeRuntimeClasspathCopyLocks") {
    group = "build setup"
    description = "Resolves Android Studio runtime classpath copies when refreshing lock state."
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    pluginManager.withPlugin("com.android.application") {
        val resolveIdeRuntimeClasspathCopyLock = tasks.register("resolveIdeRuntimeClasspathCopyLock") {
            group = "build setup"
            description = "Resolves this module's Android Studio runtime classpath copies."
            notCompatibleWithConfigurationCache("Resolves copied configurations at execution time")
            doFirst {
                check(gradle.startParameter.isWriteDependencyLocks) {
                    "$path must be run with --write-locks"
                }
            }
            doLast {
                listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { configurationName ->
                    configurations.getByName(configurationName).copy().resolve()
                }
            }
        }
        resolveIdeRuntimeClasspathCopyLocks.configure {
            dependsOn(resolveIdeRuntimeClasspathCopyLock)
        }
    }
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

tasks.register("clientRewriteContractTest") {
    group = "verification"
    description = "Runs the complete compatibility gate for a from-scratch client rewrite."
    dependsOn(":app:testDebugUnitTest")
}
