import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies the APKs produced for a single variant into a dedicated `renamed_apks/<variant>`
 * directory, giving them friendly `NekoBox-<versionName>[-<abi>].apk` filenames.
 *
 * AGP 9 (new DSL) removed the legacy `applicationVariants` / `BaseVariantOutputImpl` API that
 * previously let us rewrite `outputFileName` in place. The supported replacement is to react to
 * artifact creation via `androidComponents.onVariants { ... }` and wire a task that *copies*
 * the produced APKs, using a [BuiltArtifactsLoader] to enumerate every split (one APK per ABI)
 * along with its metadata. The original APKs in `outputs/apk/...` are left untouched.
 *
 * Only public AGP/Gradle APIs are used here (no `com.android.build.gradle.internal.*` and no
 * `org.gradle.internal.impldep.*`).
 */
abstract class RenameApkTask : DefaultTask() {

    /** The directory of APKs produced by AGP for this variant (wired automatically by AGP). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    /** Destination directory: `build/outputs/renamed_apks/<variant>`. */
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    /** Loader that enumerates the built APKs (one per ABI split) and their metadata. */
    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    /**
     * Friendly base name to use instead of the default project/module name, e.g.
     * `NekoBox-1.4.2-mod-12` or `NekoBox-pre-1.4.2-20260214-1` for the preview flavor.
     * Declared as @Input so a version bump invalidates the task and the APKs are re-copied
     * with the new name rather than leaving stale files in the output directory.
     */
    @get:Input
    abstract val baseName: Property<String>

    @TaskAction
    fun taskAction() {
        val outputDir = output.get()
        val outputFile = outputDir.asFile

        // This task does not run incrementally; start from a clean output directory.
        outputFile.deleteRecursively()
        outputFile.mkdirs()

        val builtArtifacts = builtArtifactsLoader.get().load(input.get())
            ?: throw RuntimeException("Cannot load APKs from ${input.get().asFile}")

        val base = baseName.get()

        builtArtifacts.elements.forEach { artifact ->
            val source = File(artifact.outputFile)
            // Preserve the ABI substring from the original split filename (e.g. "arm64-v8a")
            // so downstream tooling (CI globs `*arm64-v8a*.apk`) keeps matching.
            val abi = ABIS.firstOrNull { source.name.contains(it) }

            val targetName = buildString {
                append(base)
                if (abi != null) append("-").append(abi)
                append(".apk")
            }

            Files.copy(
                source.toPath(),
                outputDir.file(targetName).asFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        // NOTE: we intentionally do NOT call builtArtifacts.save(outputDir). That would write an
        // output-metadata.json whose `outputFile` entries still point at the original (un-renamed)
        // APK paths in outputs/apk/<variant>/, not at the NekoBox-* copies next to it - which would
        // mislead any BuiltArtifactsLoader consumer back to the originals. Nothing downstream
        // consumes this metadata (CI just globs the *.apk files), so we omit it rather than emit a
        // misleading file.
    }

    private companion object {
        // Order matters: check the more specific names before substrings would collide.
        val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
