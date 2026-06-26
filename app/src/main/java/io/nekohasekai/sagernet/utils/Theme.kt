package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val DRACULA = 23
    const val DYNAMIC = 24
    const val DARK_HIGH_CONTRAST = 25
    const val DRACULA_M3 = 26
    const val NORD = 27
    const val MONOKAI = 28
    const val AYU = 29
    const val CATPPUCCIN = 30

    /**
     * Themes that only make sense in dark mode: selecting one forces night mode
     * on so its dark canvas (values-night) takes effect, and the prior night
     * setting is restored on exit (see SettingsPreferenceFragment). Dracula was
     * the first such theme; the modern M3 themes are dark-only too.
     */
    val DARK_ONLY_THEMES = setOf(
        DRACULA,
        DARK_HIGH_CONTRAST,
        DRACULA_M3,
        NORD,
        MONOKAI,
        AYU,
        CATPPUCCIN,
    )

    private fun defaultTheme() = DARK_HIGH_CONTRAST

    /**
     * Metadata for a theme shown in the modern named picker.
     *
     * @param id          one of the Theme int constants above (persisted to appTheme)
     * @param nameRes     display name string resource
     * @param previewColor color resource for the preview swatch shown next to the name
     */
    /**
     * Metadata for a theme shown in the modern named picker.
     *
     * @param id          one of the Theme int constants above (persisted to appTheme)
     * @param nameRes     display name string resource
     * @param previewColor fill color for the preview swatch shown next to the name
     * @param ringColor   optional circumference-ring color; when non-zero the swatch
     *                    is drawn as [previewColor] fill + a thin [ringColor] frame.
     *                    Used by Dark High Contrast (black fill + white ring) so its
     *                    OLED-black identity doesn't read as "a green theme".
     */
    data class ThemeInfo(
        val id: Int,
        val nameRes: Int,
        val previewColor: Int,
        val ringColor: Int = 0,
    )

    /**
     * Modern, full-fledged M3 themes presented by name in the picker dialog.
     * The legacy single-accent palettes stay behind the "Legacy colors…" grid.
     * Order here is the display order in the dialog.
     */
    val MODERN_THEMES: List<ThemeInfo> = listOf(
        ThemeInfo(
            DARK_HIGH_CONTRAST,
            R.string.theme_dark_high_contrast,
            R.color.dhc_background,
            R.color.white,
        ),
        ThemeInfo(DRACULA_M3, R.string.theme_dracula_m3, R.color.draculam3_primary),
        ThemeInfo(NORD, R.string.theme_nord, R.color.nord_primary),
        ThemeInfo(MONOKAI, R.string.theme_monokai, R.color.monokai_primary),
        ThemeInfo(AYU, R.string.theme_ayu, R.color.ayu_primary),
        ThemeInfo(CATPPUCCIN, R.string.theme_catppuccin, R.color.catppuccin_primary),
        ThemeInfo(DYNAMIC, R.string.theme_dynamic, R.color.color_dynamic_swatch),
    )

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            RED -> R.style.Theme_SagerNet_Red
            PINK -> R.style.Theme_SagerNet
            PINK_SSR -> R.style.Theme_SagerNet_Pink_SSR
            PURPLE -> R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Indigo
            BLUE -> R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_LightBlue
            CYAN -> R.style.Theme_SagerNet_Cyan
            TEAL -> R.style.Theme_SagerNet_Teal
            GREEN -> R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_LightGreen
            LIME -> R.style.Theme_SagerNet_Lime
            YELLOW -> R.style.Theme_SagerNet_Yellow
            AMBER -> R.style.Theme_SagerNet_Amber
            ORANGE -> R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_VerdantMint
            DRACULA -> R.style.Theme_SagerNet_Dracula
            DARK_HIGH_CONTRAST -> R.style.Theme_SagerNet_DarkHighContrast
            DRACULA_M3 -> R.style.Theme_SagerNet_DraculaM3
            NORD -> R.style.Theme_SagerNet_Nord
            MONOKAI -> R.style.Theme_SagerNet_Monokai
            AYU -> R.style.Theme_SagerNet_Ayu
            CATPPUCCIN -> R.style.Theme_SagerNet_Catppuccin
            DYNAMIC -> R.style.Theme_SagerNet
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            RED -> R.style.Theme_SagerNet_Dialog_Red
            PINK -> R.style.Theme_SagerNet_Dialog
            PINK_SSR -> R.style.Theme_SagerNet_Dialog_Pink_SSR
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_Dialog_VerdantMint
            DRACULA -> R.style.Theme_SagerNet_Dialog_Dracula
            DARK_HIGH_CONTRAST -> R.style.Theme_SagerNet_Dialog_DarkHighContrast
            DRACULA_M3 -> R.style.Theme_SagerNet_Dialog_DraculaM3
            NORD -> R.style.Theme_SagerNet_Dialog_Nord
            MONOKAI -> R.style.Theme_SagerNet_Dialog_Monokai
            AYU -> R.style.Theme_SagerNet_Dialog_Ayu
            CATPPUCCIN -> R.style.Theme_SagerNet_Dialog_Catppuccin
            DYNAMIC -> R.style.Theme_SagerNet_Dialog
            else -> getDialogTheme(defaultTheme())
        }
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }
}
