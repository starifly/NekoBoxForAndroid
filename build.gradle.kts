// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.diffplug.spotless") version "7.0.4"
}

allprojects {
    apply(from = "${rootProject.projectDir}/repositories.gradle.kts")
}

// Spotless applies a base plugin that already provides a `clean` task, so only register ours
// if it isn't present (avoids "task with that name already exists").
if (tasks.findByName("clean") == null) {
    tasks.register<Delete>("clean") {
        delete(rootProject.layout.buildDirectory)
    }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.2.1")
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.2.1")
    }
}
