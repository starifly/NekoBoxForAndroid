package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * One-time, process-wide setup of the TextMate engine used by the JSON config editor
 * (sora-editor's language-textmate). Loading grammars and themes into the global
 * registries only needs to happen once regardless of how many editors are created, so
 * this is guarded by an idempotent flag.
 *
 * Assets live under app/src/main/assets/textmate/ (JSON grammar + config + two themes,
 * derived from VSCode — see the LICENSE file there).
 */
object TextMateSetup {

    const val SCOPE_JSON = "source.json"

    private const val THEME_DARK = "dark"
    private const val THEME_LIGHT = "light"

    @Volatile
    private var initialized = false

    /** Loads the file resolver, both themes, and the JSON grammar. Safe to call repeatedly. */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return

        val assets = context.applicationContext.assets
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

        val themeRegistry = ThemeRegistry.getInstance()
        loadTheme(themeRegistry, THEME_DARK, "textmate/dark_vs.json", dark = true)
        loadTheme(themeRegistry, THEME_LIGHT, "textmate/light_vs.json", dark = false)

        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

        initialized = true
    }

    private fun loadTheme(
        registry: ThemeRegistry,
        name: String,
        assetPath: String,
        dark: Boolean,
    ) {
        val input = FileProviderRegistry.getInstance().tryGetInputStream(assetPath)
            ?: error("Missing TextMate theme asset: $assetPath")
        val source = input.use { IThemeSource.fromInputStream(it, assetPath, null) }
        registry.loadTheme(ThemeModel(source, name).apply { isDark = dark })
    }

    /**
     * Activate the dark or light TextMate theme and build a matching color scheme for the
     * given editor. Call whenever the editor is created so its colors follow the app's
     * night-mode preference.
     */
    fun applyTheme(editor: CodeEditor, nightMode: Boolean) {
        val registry = ThemeRegistry.getInstance()
        registry.setTheme(if (nightMode) THEME_DARK else THEME_LIGHT)
        editor.colorScheme = TextMateColorScheme.create(registry)
    }
}
