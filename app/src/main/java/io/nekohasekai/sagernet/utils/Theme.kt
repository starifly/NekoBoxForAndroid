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

    fun apply(context: Context) = context.setTheme(R.style.Theme_SagerNet_Expressive)

    fun applyDialog(context: Context) =
        context.setTheme(R.style.Theme_SagerNet_Expressive_Dialog)

    fun applyDynamicColors(activity: Activity) {
        if (DataStore.dynamicColors) DynamicColors.applyToActivityIfAvailable(activity)
    }

    fun getTheme(): Int = R.style.Theme_SagerNet_Expressive

    fun getDialogTheme(): Int = R.style.Theme_SagerNet_Expressive_Dialog

    var currentNightMode = -1

    fun getNightMode(): Int {
        if (currentNightMode == -1) currentNightMode = DataStore.nightTheme
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int = when (mode) {
        0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        1 -> AppCompatDelegate.MODE_NIGHT_YES
        2 -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
    }

    fun usingNightMode(): Boolean = when (DataStore.nightTheme) {
        1 -> true
        2 -> false
        else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun applyNightTheme() = AppCompatDelegate.setDefaultNightMode(getNightMode())
}
