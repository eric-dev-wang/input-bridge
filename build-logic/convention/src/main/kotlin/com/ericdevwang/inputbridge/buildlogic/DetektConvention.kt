package com.ericdevwang.inputbridge.buildlogic

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

private const val DETEKT_AUTO_CORRECT_PROPERTY = "detektAutoCorrect"

internal fun Project.configureDetekt() {
    pluginManager.apply("dev.detekt")

    val autoCorrectProvider = providers.gradleProperty(DETEKT_AUTO_CORRECT_PROPERTY)
        .map { value ->
            value.toBooleanStrictOrNull()
                ?: error("$DETEKT_AUTO_CORRECT_PROPERTY must be true or false, but was: $value")
        }
        .orElse(true)

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        allRules.set(false)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        ignoreFailures.set(false)
        autoCorrect.set(autoCorrectProvider)
    }

    tasks.withType<Detekt>().configureEach {
        autoCorrect.set(autoCorrectProvider)
        include("**/*.kt")
        exclude("**/build/**")
        exclude("**/generated/**")
        exclude("**/src/**/resources/**")
        reports {
            checkstyle.required.set(true)
            html.required.set(true)
            markdown.required.set(false)
            sarif.required.set(false)
        }
    }

    tasks.register("detektAll") {
        group = "verification"
        description = "Runs all Detekt analysis tasks for this module."
        dependsOn(
            tasks.withType<Detekt>().matching { task ->
                task.name != "detekt" && !task.name.endsWith("SourceSet")
            },
        )
    }
}
