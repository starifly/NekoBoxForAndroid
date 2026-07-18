package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
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

        // Only the explicit Dynamic (Material You) theme should use wallpaper colors.
        // The hand-picked themes keep their legacy palettes instead of being reseeded
        // into Material 3's generated tonal roles.
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

        // findViewById (not ViewBinding): ThemedActivity is a base class applied over arbitrary
        // child-activity layouts. android.R.id.content is a framework id, and appbar/stats are
        // resolved across whatever layout the subclass set - no single binding owns them.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            findViewById<AppBarLayout>(R.id.appbar)?.apply {
                updatePadding(top = bars.top)
            }
            // Lift the bottom bar (and the FAB docked into it, plus the FAB's
            // progress ring anchored to the FAB) above the navigation bar so the
            // ring isn't clipped by the system inset under edge-to-edge.
            findViewById<View>(R.id.stats)?.apply {
                updatePadding(bottom = bars.bottom)
            }
            insets
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    /**
     * Apply Material 3 dynamic color ONLY when the user explicitly picks the Dynamic
     * (Material You) theme, and only on Android 12+ where a wallpaper palette exists.
     * The hand-designed themes keep their own colors untouched - forcing a content-based
     * reseed on them mangled their palettes into arbitrary M3 tones.
     */
    private fun applyDynamicColors() {
        if (DataStore.appTheme == Theme.DYNAMIC) {
            DynamicColors.applyToActivityIfAvailable(this)
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
            // findViewById (not ViewBinding): snackbar_text is owned by the Material library's
            // internal Snackbar layout, not by an app layout binding.
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()
}
