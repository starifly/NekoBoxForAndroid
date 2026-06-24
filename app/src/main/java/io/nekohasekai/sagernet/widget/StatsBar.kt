package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr), CoordinatorLayout.AttachedBehavior {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var speedRow: View
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
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
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

    fun performShow() = behavior.slideUp(this)

    fun performHide() = behavior.slideDown(this)


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        speedRow = findViewById(R.id.speed_row)
        refreshSpeedVisibility()
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence, tooltip: CharSequence = text) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, tooltip)
    }

    fun refreshSpeedVisibility() {
        if (this::speedRow.isInitialized) speedRow.isVisible = DataStore.speedInterval > 0
    }

    fun changeState(state: BaseService.State) {
        val activity = mainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        if (state == BaseService.State.Connected) {
            postWhenStarted {
                if (allowShow) performShow()
                refreshSpeedVisibility()
                setStatus(
                    app.getText(R.string.connection_status_connected),
                    app.getText(R.string.vpn_connected)
                )
            }
        } else {
            postWhenStarted {
                if (allowShow) performShow()
            }
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
        val activity = mainActivity
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
