package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        // Material 3 "to the bone": derive a full, correct M3 tonal palette for the active
        // theme. When the user picks the Dynamic (Material You) theme on Android 12+, seed
        // from the system wallpaper; otherwise seed from the selected theme's colorPrimary so
        // every theme (and pre-12 device) gets proper M3 roles (container/surface-variant/
        // outline/...) generated from its seed instead of hand-authored values.
        if (!isDialog) applyDynamicColors()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()
            insetController.isAppearanceLightStatusBars = 
                if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            findViewById<AppBarLayout>(R.id.appbar)?.apply {
                updatePadding(top = bars.top)
            }
            insets
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    /**
     * Apply Material 3 dynamic colors. For the Dynamic (Material You) theme on Android 12+ the
     * palette comes from the system wallpaper; for every other theme we seed a content-based
     * palette from the theme's own colorPrimary so all M3 roles are generated correctly
     * (works on all API levels, no hand-authored per-theme palettes).
     */
    private fun applyDynamicColors() {
        if (DataStore.appTheme == Theme.DYNAMIC) {
            // Wallpaper-based; only takes effect on Android 12+, otherwise a no-op and the
            // base theme's colors stand.
            DynamicColors.applyToActivityIfAvailable(this)
            return
        }
        val seed = resolveColorAttr(com.google.android.material.R.attr.colorPrimary)
        if (seed == 0) return
        DynamicColors.applyToActivityIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setContentBasedSource(seed)
                .build()
        )
    }

    private fun resolveColorAttr(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
