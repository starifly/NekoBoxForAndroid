@file:Suppress("UnstableApiUsage")

import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")
}

val verifyLibcore by tasks.registering {
    val libcoreAar = file("libs/libcore.aar")
    doLast {
        if (!libcoreAar.isFile) {
            throw GradleException("Missing app/libs/libcore.aar. Build the native core with './run lib core' before building the APK.")
        }
        val requiredEntries = listOf(
            "classes.jar",
            "jni/arm64-v8a/libgojni.so"
        )
        ZipFile(libcoreAar).use { archive ->
            val missingEntries = requiredEntries.filter { archive.getEntry(it) == null }
            if (missingEntries.isNotEmpty()) {
                throw GradleException("Invalid app/libs/libcore.aar; missing: ${missingEntries.joinToString()}")
            }
            val classesJar = archive.getInputStream(archive.getEntry("classes.jar")).readBytes()
            var libcoreClass: ByteArray? = null
            val classNames = linkedSetOf<String>()
            ZipInputStream(classesJar.inputStream()).use { jar ->
                var entry = jar.nextEntry
                while (entry != null) {
                    classNames += entry.name
                    if (entry.name == "libcore/Libcore.class") libcoreClass = jar.readBytes()
                    entry = jar.nextEntry
                }
            }
            if ("libcore/HTTPClient.class" !in classNames || "libcore/HttpClient.class" in classNames) {
                throw GradleException("Invalid libcore Java ABI: expected libcore.HTTPClient and no legacy libcore.HttpClient")
            }
            val libcoreSymbols = libcoreClass?.toString(Charsets.ISO_8859_1).orEmpty()
            if ("newHttpClient" !in libcoreSymbols || "libcore/HTTPClient" !in libcoreSymbols) {
                throw GradleException("Invalid libcore Java ABI: newHttpClient must return libcore.HTTPClient")
            }
        }
    }
}

val verifyOssDebugLibcoreCallers by tasks.registering {
    dependsOn("compileOssDebugKotlin")
    doLast {
        val classesDir = layout.buildDirectory.dir("tmp/kotlin-classes/ossDebug").get().asFile
        val classFiles = fileTree(classesDir) { include("**/*.class") }.files
        val obsolete = classFiles.filter { file ->
            file.readBytes().toString(Charsets.ISO_8859_1).contains("libcore/HttpClient")
        }
        if (obsolete.isNotEmpty()) {
            throw GradleException(
                "Stale libcore.HttpClient bytecode detected; run a clean rebuild: " +
                    obsolete.joinToString { it.relativeTo(classesDir).path }
            )
        }
        if (classFiles.none { file ->
                file.readBytes().toString(Charsets.ISO_8859_1).contains("libcore/HTTPClient")
            }) {
            throw GradleException("No compiled caller references the current libcore.HTTPClient ABI")
        }
    }
}

tasks.matching { it.name == "assembleOssDebug" }.configureEach {
    dependsOn(verifyOssDebugLibcoreCallers)
}

tasks.named("preBuild") {
    dependsOn(verifyLibcore)
}
