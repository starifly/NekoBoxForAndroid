/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.core.graphics.ColorUtils
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

/**
 * Bend [DropDownPreference] to support
 * [Simple Menus](https://material.google.com/components/menus.html#menus-behavior).
 */

open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0,
) : DropDownPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    private lateinit var mAdapter: SimpleMenuAdapter

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // findViewById (not ViewBinding): binds into the AndroidX preference-row ViewHolder
        // (holder.itemView), which is not an app layout binding.
        val mSpinner = holder.itemView.findViewById<Spinner>(R.id.spinner)
        mSpinner.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        mSpinner.setPopupBackgroundResource(R.drawable.bg_spinner_dropdown)
    }

    override fun onClick() {
        val selected = entryValues.indexOf(value)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(entries, selected) { dialog, which ->
                val newValue = entryValues[which].toString()
                if (callChangeListener(newValue)) value = newValue
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun createAdapter(): ArrayAdapter<CharSequence?> {
        mAdapter = SimpleMenuAdapter(getContext(), R.layout.simple_menu_dropdown_item)
        return mAdapter
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        if (::mAdapter.isInitialized) {
            mAdapter.currentPosition = entryValues.indexOf(value)
            mAdapter.notifyDataSetChanged()
        }
    }

    private class SimpleMenuAdapter(context: Context, resource: Int) :
        ArrayAdapter<CharSequence?>(context, resource) {

        var currentPosition = -1

        private val radius = 12f * context.resources.displayMetrics.density

        // Highlight the selected item with a translucent primary tint rather than
        // an opaque colorMaterial100 fill: the light fill made the (light) item
        // text low-contrast on dark themes. ~20% alpha reads on any background
        // while keeping the text legible.
        private val selectedColor = ColorUtils.setAlphaComponent(
            context.getColorAttr(R.attr.colorPrimary),
            41, // 0.16 alpha, matches nav_item_fill.xml
        )

        private val topDrawable = GradientDrawable().apply {
            setColor(selectedColor)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }

        private val bottomDrawable = GradientDrawable().apply {
            setColor(selectedColor)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
        }

        private val middleDrawable = GradientDrawable().apply {
            setColor(selectedColor)
        }

        private val singleDrawable = GradientDrawable().apply {
            setColor(selectedColor)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View = super.getDropDownView(position, convertView, parent)

            if (position == currentPosition) {
                view.background = when {
                    position == 0 && count == 1 -> singleDrawable
                    position == 0 -> topDrawable
                    position == count - 1 -> bottomDrawable
                    else -> middleDrawable
                }
            } else {
                view.setBackgroundColor(
                    context.getColorAttr(com.google.android.material.R.attr.colorSurfaceContainer)
                )
            }
            return view
        }
    }
}
