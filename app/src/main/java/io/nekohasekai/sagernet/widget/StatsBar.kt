package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val INITIAL_HIDE_DELAY_MS = 100L
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
    @Suppress("unused")
    private lateinit var behavior: YourBehavior
    private var currentState = BaseService.State.Idle
    private var pendingTransition: Transition? = Transition.HideImmediate
    private var transitionJob: Job? = null

    var useExternalScrollDriver = false

    var allowShow = false
        set(value) {
            field = value
            updateHideOnScroll()
        }

    init {
        alpha = 0f
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    inner class YourBehavior : Behavior() {
override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTarget: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean {
            if (useExternalScrollDriver) return false
            return super.onStartNestedScroll(
                coordinatorLayout,
                child,
                directTarget,
                target,
                nestedScrollAxes,
                type,
            )
        }

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            target: View,
            dxConsumed: Int,
            dyConsumed: Int,
            dxUnconsumed: Int,
            dyUnconsumed: Int,
            type: Int,
            consumed: IntArray,
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
        override fun slideUp(child: BottomAppBar) {
            if (!allowShow) return
            super.slideUp(child)
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        // findViewById (not ViewBinding): status/tx/rx are sibling views declared in the host
        // layout (layout_main), not children of a layout this custom BottomAppBar inflates.
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    // Two-tone status: color the lead segment (split at [sep], kept with the lead)
    // and the remainder separately. Used for "Connected, …" and "Success: …".
    private fun setStatusTwoTone(full: CharSequence, sep: Char, leadAttr: Int, restAttr: Int) {
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
        // Connected = statusConnectedColor (green), Stopped/Stopping = statusStoppedColor
        // (red - "Shutting down…" reads as red), Connecting = statusConnectingColor (Dracula
        // yellow; colorOnPrimary elsewhere), other = colorOnPrimary.
        // Non-Dracula themes default these attrs to colorOnPrimary, so no change.
        val attr = when (state) {
            BaseService.State.Connected -> R.attr.statusConnectedColor
            BaseService.State.Stopped, BaseService.State.Stopping -> R.attr.statusStoppedColor
            BaseService.State.Connecting -> R.attr.statusConnectingColor
            else -> com.google.android.material.R.attr.colorOnPrimary
        }
        statusText.setTextColor(context.getColorAttr(attr))
    }

    fun changeState(state: BaseService.State) {
        val activity = context.unwrap<MainActivity>()
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.withStarted { what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                // "Connected," in green; the "tap to check connection" hint in detail color.
                setStatusTwoTone(
                    app.getText(R.string.vpn_connected),
                    ',',
                    R.attr.statusConnectedColor,
                    R.attr.statusDetailColor,
                )
            }
        } else {
            getBehavior().slideUp(this)
            animate().cancel()
            translationY = 0f
        }
    }

    private fun commitHidden(animated: Boolean) {
        alpha = 1f
        if (!animated) {
            syncHiddenPosition()
            return
        }
        val activity = context as? MainActivity
        if (activity == null) {
            post {
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
            return
        }
        transitionJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(INITIAL_HIDE_DELAY_MS)
            activity.whenStarted {
                transitionJob = null
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
        }
    }

    private fun syncHiddenPosition() {
        getBehavior().slideDown(this)
        animate().cancel()
        translationY = height.toFloat()
        alpha = 1f
    }

    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    private fun hasPendingDelayedHide(): Boolean {
        return pendingTransition == Transition.HideAfterStart || transitionJob != null
    }

    fun changeState(state: BaseService.State) {
        currentState = state
        updateHideOnScroll()
        if (state == BaseService.State.Connected) {
            setStatus(app.getText(R.string.vpn_connected))
        } else {
            updateSpeed(0, 0)
            setStatusColorByState(state)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    },
                ),
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
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
        val activity = context.unwrap<MainActivity>()
        isEnabled = false
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
                    statusText.setTextColor(context.getColorAttr(R.attr.statusTestingColor))
                    setStatus(app.getText(R.string.connection_test_testing))

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
