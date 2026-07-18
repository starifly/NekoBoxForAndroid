package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.text.SpannableStringBuilder
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
  defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val INITIAL_HIDE_DELAY_MS = 100L
        private const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
    }

    private enum class Transition {
        ShowImmediate,
        ShowAnimated,
        HideImmediate,
        HideAfterStart,
    }

    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var speedRow: View
    private lateinit var textContainer: View
    private lateinit var behavior: YourBehavior
private val mainActivity: MainActivity
        get() {
            var current = context
            while (current is ContextWrapper) {
                if (current is MainActivity) return current
                current = current.baseContext
            }
            error("StatsBar must be hosted by MainActivity")
        }

    var allowShow = true
    private var hideOnScroll = true

    private var scrollHidden = false
    private var scrollDirection = 0 // 1 = hide (dy>0), -1 = show (dy<0), 0 = none
    private var scrollAccumulatedDy = 0
    private val scrollToggleThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    var useExternalScrollDriver = false
        set(value) {
            if (field == value) return
            field = value
            syncScrollHiddenFromView()
            resetScrollDriverState()
            updateHideOnScroll()
        }
    private fun ensureViews() {
        if (!::statusText.isInitialized) {
            statusText = findViewById(R.id.status)
            txText = findViewById(R.id.tx)
            rxText = findViewById(R.id.rx)
            speedRow = findViewById(R.id.speed_row)
            textContainer = findViewById(R.id.text_container)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val lp = layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val margins = (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
        val maxAllowedWidth = parentWidth - margins

        val newWidthMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxAllowedWidth, View.MeasureSpec.AT_MOST)
        super.onMeasure(newWidthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = h / 2f
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : HideBottomViewOnScrollBehavior<StatsBar>() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: StatsBar,
            directTargetChild: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean = child.hideOnScroll && super.onStartNestedScroll(
            coordinatorLayout, child, directTargetChild, target, nestedScrollAxes, type
        )

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: StatsBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed,
                dxUnconsumed,
                dyUnconsumed,
                type,
                consumed,
            )
        }

        override fun slideUp(child: StatsBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: StatsBar) {
            super.slideDown(child)
        }
    }

    fun performShow() = getBehavior().slideUp(this)

    fun performHide() = getBehavior().slideDown(this)

    override fun setOnClickListener(l: OnClickListener?) {
        ensureViews()
        refreshSpeedVisibility()
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence, tooltip: CharSequence = text) {
        ensureViews()
        statusText.text = text
        TooltipCompat.setTooltipText(this, tooltip)
    }

    fun refreshSpeedVisibility() {
        ensureViews()
        if (this::speedRow.isInitialized) speedRow.isVisible = DataStore.speedInterval > 0
        TooltipCompat.setTooltipText(this, text)
    }

    private fun updateHideOnScroll() {
        hideOnScroll =
            !useExternalScrollDriver && allowShow && currentState == BaseService.State.Connected
    }

    private fun shouldShow(): Boolean {
        return allowShow && currentState == BaseService.State.Connected
    }

    private fun resetScrollDriverState() {
        scrollDirection = 0
        scrollAccumulatedDy = 0
    }

    private fun syncScrollHiddenFromView() {
        if (!isLaidOut || height <= 0) return
        scrollHidden = translationY >= height / 2f
    }

    fun onListScrolled(dy: Int) {
        if (!useExternalScrollDriver || !shouldShow() || dy == 0) return

        val direction = if (dy > 0) 1 else -1
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollAccumulatedDy = 0
        }
        scrollAccumulatedDy += dy

        val wantHidden = scrollAccumulatedDy > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccumulatedDy) > scrollToggleThresholdPx) {
                scrollAccumulatedDy = direction * scrollToggleThresholdPx
            }
            return
        }
        if (abs(scrollAccumulatedDy) < scrollToggleThresholdPx) return

        scrollHidden = wantHidden
        scrollAccumulatedDy = 0
        if (wantHidden) performHide() else performShow()
    }

    fun syncMainControls(
        showControls: Boolean,
        state: BaseService.State,
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        currentState = state
        allowShow = showControls
        when {
            !showControls || state != BaseService.State.Connected -> {
                applyTransition(
                    if (animate && showControls) Transition.HideAfterStart else Transition.HideImmediate
                )
            }

            showWhenConnected -> applyTransition(
                if (animate) Transition.ShowAnimated else Transition.ShowImmediate
            )
            alpha == 0f && isLaidOut -> alpha = 1f
        }
    }

    // Two-tone status: color the lead segment (split at [sep], kept with the lead)
    // and the remainder separately. Used for "Connected, …" and "Success: …".
    private fun setStatusTwoTone(full: CharSequence, sep: Char, leadAttr: Int, restAttr: Int) {
        ensureViews()
        val s = full.toString()
        val idx = s.indexOf(sep)
        if (idx < 0) {
            statusText.setTextColor(context.getColorAttr(leadAttr))
            setStatus(full)
            return
        }
        val cut = idx + 1 // keep the separator char with the lead segment
        val span = SpannableStringBuilder(s)
        span.setSpan(
            ForegroundColorSpan(context.getColorAttr(leadAttr)),
            0,
            cut,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        span.setSpan(
            ForegroundColorSpan(context.getColorAttr(restAttr)),
            cut,
            s.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        setStatus(span)
    }

    private fun setStatusColorByState(state: BaseService.State) {
        ensureViews()
        val attr = when (state) {
            BaseService.State.Connected -> R.attr.statusConnectedColor
            BaseService.State.Stopped, BaseService.State.Stopping -> R.attr.statusStoppedColor
            BaseService.State.Connecting -> R.attr.statusConnectingColor
            else -> com.google.android.material.R.attr.colorOnPrimary
        }
        statusText.setTextColor(context.getColorAttr(attr))
    }

    fun changeState(state: BaseService.State) {
        val activity = mainActivity
        val showText = state != BaseService.State.Idle && state != BaseService.State.Stopped

        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.withStarted { what() }
    private fun commitHidden(animated: Boolean) {
        alpha = 1f
        scrollHidden = true
        resetScrollDriverState()
        if (!animated) {
            syncHiddenPosition()
            return
        }

        postWhenStarted {
            ensureViews()
            val transition = android.transition.AutoTransition().apply {
                duration = 250
            }
            (parent as? android.view.ViewGroup)?.let {
                android.transition.TransitionManager.beginDelayedTransition(it, transition)
            }
            textContainer.visibility = if (showText) View.VISIBLE else View.GONE

            if (allowShow) performShow()

            if (state == BaseService.State.Connected) {
                refreshSpeedVisibility()
                // 整合 HEAD 的雙色顯示："Connected," 為綠色，後續的點擊提示為細節顏色
                setStatusTwoTone(
                    app.getText(R.string.vpn_connected),
                    ',',
                    R.attr.statusConnectedColor,
                    R.attr.statusDetailColor,
                )
            } else {
                updateSpeed(0, 0)
                setStatusColorByState(state)
                setStatus(
                    context.getText(
                        when (state) {
                            BaseService.State.Connecting -> R.string.connecting
                            BaseService.State.Stopping -> R.string.stopping
                            else -> R.string.not_connected
                        }
                    )
                )
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        ensureViews()
        val speedColor = context.getColorAttr(R.attr.speedTextColor)
        txText.setTextColor(speedColor)
        rxText.setTextColor(speedColor)
        txText.text = "▲  ${
            context.getString(
                R.string.speed,
                Formatter.formatFileSize(context, txRate),
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed,
                Formatter.formatFileSize(context, rxRate),
            )
        }"
    }

    fun testConnection() {
        val activity = mainActivity
        isEnabled = false
        ensureViews()
        // "Testing…" in the testing color.
        statusText.setTextColor(context.getColorAttr(R.attr.statusTestingColor))
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    // "Success:" in green; the handshake detail in detail color.
                    setStatusTwoTone(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            },
                            elapsed,
                        ),
                        ':',
                        R.attr.statusConnectedColor,
                        R.attr.statusDetailColor,
                    )
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatusColorByState(BaseService.State.Idle) // 發生錯誤時重設顏色

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error,
                            e.readableMessage,
                        ),
                    ).show()
                }
            }
        }
    }
}