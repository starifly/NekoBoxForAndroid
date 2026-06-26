package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

/**
 * Two-tier theme picker.
 *
 * The preference opens a modern named list of full-fledged Material 3 themes
 * (each row: preview swatch + name). A trailing "Classic colors…" row opens the
 * legacy single-accent color grid (the original swatch-grid UX).
 *
 * Both paths persist the same int [Theme] id via [persistInt] so the rest of the
 * app (Theme.getTheme / DataStore.appTheme) is unchanged.
 */
class ThemePickerPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle,
    ),
) : Preference(
    context,
    attrs,
    defStyle,
) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        // findViewById (not ViewBinding): android.R.id.widget_frame is a framework id inside the
        // AndroidX preference-row ViewHolder, not an app layout binding.
        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as? LinearLayout
            ?: return

        // Key off the holder's actual view state, not an instance flag: the
        // RecyclerView can recycle/re-inflate this row's holder, in which case a
        // stale instance flag would leave the new frame without its swatch.
        if (widgetFrame.childCount == 0) {
            widgetFrame.addView(
                nekoImageView(context.getColorAttr(R.attr.colorPrimary), 48, 0),
            )
            widgetFrame.visibility = View.VISIBLE
        }
    }

    private fun nekoImageView(color: Int, sizeDp: Int, paddingDp: Int): ImageView {
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val paddingSize = (paddingDp * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            setImageDrawable(nekoAtColor(resources, color))
        }
    }

    private fun nekoAtColor(res: Resources, color: Int): Drawable {
        val neko = ResourcesCompat.getDrawable(
            res,
            R.drawable.ic_baseline_fiber_manual_record_24,
            null,
        )!!
        DrawableCompat.setTint(neko.mutate(), color)
        return neko
    }

    /**
     * A circular swatch with a [fillColor] interior and a thin [ringColor]
     * circumference ring. Used for themes (e.g. Dark High Contrast) whose solid
     * accent dot would misrepresent the theme - a black fill + white ring reads
     * as "OLED black" rather than "a green theme".
     */
    private fun ringedSwatchView(fillColor: Int, ringColor: Int, sizeDp: Int): ImageView {
        val size = dp(sizeDp)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(dp(2), ringColor)
        }
        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageDrawable(ring)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    override fun onClick() {
        super.onClick()
        showModernDialog()
    }

    /** Modern named list: one row per [Theme.MODERN_THEMES] entry + a "Classic colors…" row. */
    private fun showModernDialog() {
        lateinit var dialog: AlertDialog

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        for (info in Theme.MODERN_THEMES) {
            val ring = if (info.ringColor != 0) {
                ResourcesCompat.getColor(context.resources, info.ringColor, context.theme)
            } else {
                null
            }
            container.addView(
                buildRow(
                    name = context.getString(info.nameRes),
                    swatchColor = ResourcesCompat.getColor(context.resources, info.previewColor, context.theme),
                    ringColor = ring,
                ) {
                    select(info.id)
                    dialog.dismiss()
                },
            )
        }

        // Trailing entry: open the legacy single-accent color grid.
        container.addView(
            buildRow(
                name = context.getString(R.string.theme_classic_colors),
                swatchColor = null,
                icon = R.drawable.ic_baseline_color_lens_24,
            ) {
                dialog.dismiss()
                showClassicGrid()
            },
        )

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            // Wrap in a ScrollView so the list stays usable on compact screens as
            // Theme.MODERN_THEMES grows beyond the dialog's height.
            .setView(ScrollView(context).apply { addView(container) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildRow(
        name: String,
        swatchColor: Int?,
        ringColor: Int? = null,
        icon: Int? = null,
        onClick: () -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // Selectable item background for ripple feedback.
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                outValue,
                true,
            )
            setBackgroundResource(outValue.resourceId)
            // Announce the theme name to screen readers when the row is focused.
            contentDescription = name

            val leading = when {
                swatchColor != null && ringColor != null ->
                    ringedSwatchView(swatchColor, ringColor, 28)
                swatchColor != null -> nekoImageView(swatchColor, 28, 0)
                icon != null -> ImageView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(dp(28), dp(28))
                    setImageDrawable(
                        ResourcesCompat.getDrawable(resources, icon, context.theme)?.also {
                            DrawableCompat.setTint(
                                it.mutate(),
                                context.getColorAttr(android.R.attr.textColorPrimary),
                            )
                        },
                    )
                }
                else -> View(context).apply {
                    layoutParams = ViewGroup.LayoutParams(dp(28), dp(28))
                }
            }
            addView(leading)

            addView(
                TextView(context).apply {
                    text = name
                    setPadding(dp(16), 0, 0, 0)
                    textSize = 16f
                    setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )

            setOnClickListener { onClick() }
        }
    }

    /** Legacy single-accent color grid (the original swatch-grid UX). */
    private fun showClassicGrid() {
        lateinit var dialog: AlertDialog

        val grid = GridLayout(context).apply {
            columnCount = 4

            val colors = context.resources.getIntArray(R.array.material_colors)
            for ((index, color) in colors.withIndex()) {
                // Swatch index+1 is the Theme id (see Theme.kt). Skip themes that
                // are presented in the modern named list instead of the grid.
                val themeId = index + 1
                if (Theme.MODERN_THEMES.any { it.id == themeId }) continue

                addView(
                    nekoImageView(color, 64, 0).apply {
                        contentDescription =
                            context.getString(R.string.theme_classic_color_swatch, themeId)
                        setOnClickListener {
                            select(themeId)
                            dialog.dismiss()
                        }
                    },
                )
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.theme_classic_colors)
            .setView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    addView(grid)
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun select(themeId: Int) {
        // Notify the change listener first (while DataStore.appTheme still holds the
        // previous theme, which SettingsPreferenceFragment reads to drive dark-only
        // night-mode handling), then persist if accepted. This is the standard
        // Preference contract; the listener always returns true here.
        if (callChangeListener(themeId)) {
            persistInt(themeId)
        }
    }
}
