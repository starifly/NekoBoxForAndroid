package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSpinner

class ReselectableSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatSpinner(context, attrs) {

    var onPopupClosed: (() -> Unit)? = null

    /**
     * Android's [Spinner] has no official "popup dismissed" callback, so we use
     * the window regaining focus as a proxy: when the dropdown popup closes,
     * focus returns to this spinner's window. This is intentionally a best-effort
     * signal, not a precise one - [onPopupClosed] may also fire for unrelated
     * focus changes (app foregrounding, keyboard/IME show-hide, system dialogs,
     * permission prompts, orientation changes, etc.). Callers must therefore
     * treat it only as a hint and stay correct if it fires spuriously (the
     * OutboundPreference reselect logic does, by re-checking value/position).
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) onPopupClosed?.invoke()
    }

    override fun setSelection(position: Int) {
        val reselected = position == selectedItemPosition
        super.setSelection(position)
        if (reselected) notifyReselected(position)
    }

    override fun setSelection(position: Int, animate: Boolean) {
        val reselected = position == selectedItemPosition
        super.setSelection(position, animate)
        if (reselected) notifyReselected(position)
    }

    private fun notifyReselected(position: Int) {
        if (position < 0) return
        onItemSelectedListener?.onItemSelected(
            this,
            selectedView,
            position,
            adapter?.getItemId(position) ?: 0L,
        )
    }
}
