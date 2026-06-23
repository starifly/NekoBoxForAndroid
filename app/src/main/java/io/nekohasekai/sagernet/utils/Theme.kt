package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val STYLE_EXPRESSIVE = "expressive"
    const val STYLE_CLASSIC = "classic"

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

    private fun defaultTheme() = PINK_SSR

    fun isExpressive() = DataStore.uiStyle != STYLE_CLASSIC

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun applyDynamicColors(activity: Activity) {
        if (isExpressive() && DataStore.dynamicColors) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        if (isExpressive()) return getExpressiveTheme(theme)
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
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        if (isExpressive()) return getExpressiveDialogTheme(theme)
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
            else -> getDialogTheme(defaultTheme())
        }
    }

    private fun getExpressiveTheme(theme: Int): Int = when (theme) {
        RED -> R.style.Theme_SagerNet_Expressive_Red
        PINK_SSR -> R.style.Theme_SagerNet_Expressive_PinkSSR
        PINK -> R.style.Theme_SagerNet_Expressive_Pink
        PURPLE -> R.style.Theme_SagerNet_Expressive_Purple
        DEEP_PURPLE -> R.style.Theme_SagerNet_Expressive_DeepPurple
        INDIGO -> R.style.Theme_SagerNet_Expressive_Indigo
        BLUE -> R.style.Theme_SagerNet_Expressive_Blue
        LIGHT_BLUE -> R.style.Theme_SagerNet_Expressive_LightBlue
        CYAN -> R.style.Theme_SagerNet_Expressive_Cyan
        TEAL -> R.style.Theme_SagerNet_Expressive_Teal
        GREEN -> R.style.Theme_SagerNet_Expressive_Green
        LIGHT_GREEN -> R.style.Theme_SagerNet_Expressive_LightGreen
        LIME -> R.style.Theme_SagerNet_Expressive_Lime
        YELLOW -> R.style.Theme_SagerNet_Expressive_Yellow
        AMBER -> R.style.Theme_SagerNet_Expressive_Amber
        ORANGE -> R.style.Theme_SagerNet_Expressive_Orange
        DEEP_ORANGE -> R.style.Theme_SagerNet_Expressive_DeepOrange
        BROWN -> R.style.Theme_SagerNet_Expressive_Brown
        GREY -> R.style.Theme_SagerNet_Expressive_Grey
        BLUE_GREY -> R.style.Theme_SagerNet_Expressive_BlueGrey
        BLACK -> R.style.Theme_SagerNet_Expressive_Black
        else -> R.style.Theme_SagerNet_Expressive_PinkSSR
    }

    private fun getExpressiveDialogTheme(theme: Int): Int = when (theme) {
        RED -> R.style.Theme_SagerNet_Expressive_Dialog_Red
        PINK_SSR -> R.style.Theme_SagerNet_Expressive_Dialog_PinkSSR
        PINK -> R.style.Theme_SagerNet_Expressive_Dialog_Pink
        PURPLE -> R.style.Theme_SagerNet_Expressive_Dialog_Purple
        DEEP_PURPLE -> R.style.Theme_SagerNet_Expressive_Dialog_DeepPurple
        INDIGO -> R.style.Theme_SagerNet_Expressive_Dialog_Indigo
        BLUE -> R.style.Theme_SagerNet_Expressive_Dialog_Blue
        LIGHT_BLUE -> R.style.Theme_SagerNet_Expressive_Dialog_LightBlue
        CYAN -> R.style.Theme_SagerNet_Expressive_Dialog_Cyan
        TEAL -> R.style.Theme_SagerNet_Expressive_Dialog_Teal
        GREEN -> R.style.Theme_SagerNet_Expressive_Dialog_Green
        LIGHT_GREEN -> R.style.Theme_SagerNet_Expressive_Dialog_LightGreen
        LIME -> R.style.Theme_SagerNet_Expressive_Dialog_Lime
        YELLOW -> R.style.Theme_SagerNet_Expressive_Dialog_Yellow
        AMBER -> R.style.Theme_SagerNet_Expressive_Dialog_Amber
        ORANGE -> R.style.Theme_SagerNet_Expressive_Dialog_Orange
        DEEP_ORANGE -> R.style.Theme_SagerNet_Expressive_Dialog_DeepOrange
        BROWN -> R.style.Theme_SagerNet_Expressive_Dialog_Brown
        GREY -> R.style.Theme_SagerNet_Expressive_Dialog_Grey
        BLUE_GREY -> R.style.Theme_SagerNet_Expressive_Dialog_BlueGrey
        BLACK -> R.style.Theme_SagerNet_Expressive_Dialog_Black
        else -> R.style.Theme_SagerNet_Expressive_Dialog_PinkSSR
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
