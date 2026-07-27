package com.ericdevwang.inputbridge.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

abstract class AndroidStudioPluginConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            configureDetekt()

            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(21)
                compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            }

            tasks.withType<JavaCompile>().configureEach {
                sourceCompatibility = "21"
                targetCompatibility = "21"
            }
        }
    }
}
