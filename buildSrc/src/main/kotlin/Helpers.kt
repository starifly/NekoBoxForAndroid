import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")
private val Project.androidComponents
    get() = extensions.getByName<ApplicationAndroidComponentsExtension>("androidComponents")

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("nb4a.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {
            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "36.0.0"
        compileSdk = 36
        defaultConfig {
            minSdk = 23
            targetSdk = 35
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            baseline = project.file("lint-baseline.xml")
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
                    "okhttp3/**",
                ),
            )
        }
        // Under AGP 9's new DSL the build types below are configured directly on
        // ApplicationExtension (no AbstractAppExtension cast needed). APK output renaming is no
        // longer done here via the removed applicationVariants/BaseVariantOutputImpl API; it is
        // handled by the RenameApkTask wired in setupApp() through androidComponents.onVariants.
        buildTypes {
            getByName("release") {
                isShrinkResources = true
                if (System.getenv("nkmr_minify") == "0") {
                    isShrinkResources = false
                    isMinifyEnabled = false
                }
                // Plan 027 Stage 3: release keeps the current main-thread-DB allowance until
                // debug has run StrictMode-clean for a full cycle; then this flips to false and
                // the allowMainThreadQueries() call is deleted.
                buildConfigField("boolean", "ALLOW_MAIN_THREAD_DB", "true")
            }
            getByName("debug") {
                applicationIdSuffix = "debug"
                isDebuggable = true
                isJniDebuggable = true
                // Debug ships with the allowance OFF so StrictMode + a main-thread DAO access
                // surfaces as a crash-free IllegalStateException the operator can catch on device.
                buildConfigField("boolean", "ALLOW_MAIN_THREAD_DB", "false")
            }
        }
    }

    // Kotlin JVM target. Configured via the compilerOptions DSL on the Kotlin compile tasks
    // (a project-level call, hence outside the android.apply { } block above); the legacy
    // `kotlinOptions { jvmTarget = ... }` / KotlinJvmOptions API was removed (turned into a
    // hard error) in Kotlin 2.3.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePwd = (lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS"))
        ?.takeIf { it.isNotBlank() }
    val alias = (lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME"))
        ?.takeIf { it.isNotBlank() }
    val pwd = (lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS"))
        ?.takeIf { it.isNotBlank() }
    val releaseKeystoreFile = rootProject.file("release.keystore")
    val debugKeystoreFile = rootProject.file("app/debug.keystore")

    android.apply {
        signingConfigs {
            if (keystorePwd != null && alias != null && pwd != null && releaseKeystoreFile.isFile) {
                create("release") {
                    storeFile = releaseKeystoreFile
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = pwd
                }
            }
            if (debugKeystoreFile.isFile) {
                create("ciDebug") {
                    storeFile = debugKeystoreFile
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
        buildTypes {
            val releaseKey = signingConfigs.findByName("release")
            val ciDebugKey = signingConfigs.findByName("ciDebug")
            when {
                releaseKey != null -> {
                    getByName("release").signingConfig = releaseKey
                    getByName("debug").signingConfig = releaseKey
                }

                ciDebugKey != null -> {
                    getByName("debug").signingConfig = ciDebugKey
                }
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
            // Runner for instrumented (androidTest) tests, e.g. the Room migration test.
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    setupAppCommon()

    android.apply {
        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro"),
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include("armeabi-v7a")
            include("arm64-v8a")
            include("x86")
            include("x86_64")
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
                    "\"${requireMetadata().getProperty("PRE_VERSION_NAME")}\"",
                )
            }
        }

        for (abi in listOf("Arm64", "Arm", "X64", "X86")) {
            tasks.register("assemble" + abi + "FdroidRelease") {
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.directories.add("executableSo")
        }
    }

    // APK output renaming. The legacy applicationVariants/BaseVariantOutputImpl API was removed in
    // AGP 9 (new DSL). Instead we react to artifact creation and copy the produced APKs into
    // build/outputs/renamed_apks/<variant> with friendly NekoBox-<version>[-<abi>].apk names.
    // The preview flavor uses PRE_VERSION_NAME to match the previous behaviour.
    val previewVersionName = requireMetadata().getProperty("PRE_VERSION_NAME")
    androidComponents.onVariants { variant ->
        val shortcutResourcesTask = tasks.register<GenerateShortcutResourcesTask>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}ShortcutResources",
        ) {
            val effectivePackageName = if (variant.buildType == "debug") "$pkgName.debug" else pkgName
            targetPackage.set(effectivePackageName)
            outputDirectory.set(
                layout.buildDirectory.dir("generated/shortcutResources/${variant.name}"),
            )
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            shortcutResourcesTask,
            GenerateShortcutResourcesTask::outputDirectory,
        )

        val isPreview = variant.flavorName == "preview" ||
            variant.productFlavors.any { (_, flavor) -> flavor == "preview" }
        val version = if (isPreview) previewVersionName else verName
        val renameTask = tasks.register<RenameApkTask>(
            "renameApksFor${variant.name.replaceFirstChar { it.uppercase() }}",
        ) {
            output.set(layout.buildDirectory.dir("outputs/renamed_apks/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            baseName.set("NekoBox-$version")
        }
        variant.artifacts.use(renameTask)
            .wiredWith { it.input }
            .toListenTo(SingleArtifact.APK)
    }
}

@CacheableTask
abstract class GenerateShortcutResourcesTask : DefaultTask() {
    @get:Input
    abstract val targetPackage: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val xmlDirectory = outputDirectory.dir("xml").get().asFile.apply { mkdirs() }
        xmlDirectory.resolve("shortcuts.xml").writeText(renderShortcuts(targetPackage.get()))
    }

    private fun renderShortcuts(packageName: String): String {
        val template = checkNotNull(javaClass.getResourceAsStream("/shortcuts-template.xml")) {
            "shortcuts-template.xml resource not found"
        }.bufferedReader().use { it.readText() }
        return template.replace("{{TARGET_PACKAGE}}", packageName)
    }
}
