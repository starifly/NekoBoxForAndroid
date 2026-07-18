plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

apply(from = "../repositories.gradle.kts")

dependencies {
    // Gradle Plugins
    // AGP 9.2.1 has a runtime dependency on KGP and bundles Kotlin Gradle Plugin 2.3.10,
    // which matches the version we were pinning explicitly. Keep KGP pinned for clarity so
    // the buildSrc classpath is unambiguous.
    implementation("com.android.tools.build:gradle:9.2.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
}
