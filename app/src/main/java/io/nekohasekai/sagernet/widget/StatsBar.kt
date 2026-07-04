package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
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
    context: Context, attrs: AttributeSet? = null,
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
    private lateinit var behavior: YourBehavior
    private var currentState = BaseService.State.Idle
    private var pendingTransition: Transition? = Transition.HideImmediate
    private var transitionJob: Job? = null

    var allowShow = false
        set(value) {
            field = value
            updateHideOnScroll()
        }

    init {
        alpha = 0f
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            super.slideDown(child)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val transition = pendingTransition
        if (transition != null) {
            pendingTransition = null
            applyTransition(transition)
        } else if (alpha == 0f) {
            alpha = 1f
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun updateHideOnScroll() {
        hideOnScroll = allowShow && currentState == BaseService.State.Connected
    }

    private fun shouldShow(): Boolean {
        return allowShow && currentState == BaseService.State.Connected
    }

    fun onFingerScroll(fingerUp: Boolean) {
        if (!shouldShow()) return
        if (fingerUp) performHide() else performShow()
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

    private fun applyTransition(transition: Transition) {
        if (transition == Transition.HideImmediate && hasPendingDelayedHide()) return
        cancelPendingTransition()
        if (!isLaidOut || height == 0) {
            pendingTransition = transition
            alpha = if (transition == Transition.HideImmediate && !allowShow) 0f else 1f
            return
        }
        pendingTransition = null
        when (transition) {
            Transition.ShowImmediate -> commitVisible(animated = false)
            Transition.ShowAnimated -> commitVisible(animated = true)
            Transition.HideImmediate -> commitHidden(animated = false)
            Transition.HideAfterStart -> commitHidden(animated = true)
        }
    }

    private fun commitVisible(animated: Boolean) {
        alpha = 1f
        if (animated) {
            performShow()
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

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
