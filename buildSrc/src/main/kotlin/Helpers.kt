import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

fun Project.requireMetadata(): Properties {
    val file = rootProject.file("asteria.properties")
    val props = Properties()
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

fun Project.requireLocalProperties(): Properties {
    val props = Properties()
    val base64 = System.getenv("LOCAL_PROPERTIES")
    if (!base64.isNullOrBlank()) {
        props.load(Base64.getDecoder().decode(base64).inputStream())
    } else {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
    }
    return props
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "36.0.0"
        compileSdk = 36
        defaultConfig {
            minSdk = 36
            targetSdk = 36
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        (android as ExtensionAware).extensions.getByName<KotlinJvmOptions>("kotlinOptions").apply {
            jvmTarget = JavaVersion.VERSION_1_8.toString()
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            // Keep advisory dependency/style findings visible without promoting them
            // to runtime correctness errors. Lint errors still abort the build.
            warningsAsErrors = false
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
            applicationVariants.forEach { variant ->
                variant.outputs.forEach {
                    it as BaseVariantOutputImpl
                    it.outputFileName = it.outputFileName.replace(
                        "app", "${project.name}-" + variant.versionName
                    ).replace("-release", "").replace("-oss", "")
                }
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePath = lp.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")

    val asteriaKeystore = rootProject.file("asteria.keystore")
    val releaseKeystore = rootProject.file("release.keystore")
    val targetKeystore = when {
        !keystorePath.isNullOrBlank() -> rootProject.file(keystorePath)
        asteriaKeystore.exists() -> asteriaKeystore
        releaseKeystore.exists() -> releaseKeystore
        else -> null
    }

    android.apply {
        if (targetKeystore != null && targetKeystore.exists() && keystorePwd != null && alias != null && pwd != null) {
            signingConfigs {
                create("release") {
                    storeFile = targetKeystore
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = pwd
                }
            }
        } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
            logger.warn("Release signing configuration is missing or incomplete! Provide KEYSTORE_PASS, ALIAS_NAME, ALIAS_PASS.")
        }
        buildTypes {
            val releaseKey = signingConfigs.findByName("release")
            if (releaseKey != null) {
                getByName("release").signingConfig = releaseKey
            }
            val debugKey = signingConfigs.findByName("debug") ?: releaseKey
            if (debugKey != null) {
                getByName("debug").signingConfig = debugKey
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = requireMetadata().getProperty("VERSION_NAME")
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt()) * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include("arm64-v8a")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("play")
            create("preview") {
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireMetadata().getProperty("PRE_VERSION_NAME")}\""
                )
            }
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                val isPreview = outputFileName.contains("-preview")
                outputFileName = if (isPreview) {
                    outputFileName.replace(
                        project.name,
                        "Asteria-" + requireMetadata().getProperty("PRE_VERSION_NAME")
                    ).replace("-preview", "")
                } else {
                    outputFileName.replace(project.name, "Asteria-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                }
            }
        }

        for (abi in listOf("Arm64")) {
            tasks.create("assemble" + abi + "FdroidRelease") {
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}
