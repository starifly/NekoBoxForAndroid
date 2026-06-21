package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.PointerIcon
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.BaseProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.getColorAttr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    FloatingActionButton(context, attrs, defStyleAttr), DynamicAnimation.OnAnimationEndListener {

    private val callback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) {
            super.onAnimationEnd(drawable)
            var next = animationQueue.peek() ?: return
            if (next.icon.current == drawable) {
                animationQueue.pop()
                next = animationQueue.peek() ?: return
            }
            next.start()
        }
    }

    private inner class AnimatedState(
        @DrawableRes resId: Int,
        private val onStart: BaseProgressIndicator<*>.() -> Unit = { hideProgress() }
    ) {
        val icon: AnimatedVectorDrawableCompat =
            AnimatedVectorDrawableCompat.create(context, resId)!!.apply {
                registerAnimationCallback(this@ServiceButton.callback)
            }

        fun start() {
            setImageDrawable(icon)
            icon.start()
            progress.onStart()
        }

        fun stop() = icon.stop()
    }

    private val iconStopped by lazy { AnimatedState(R.drawable.ic_service_stopped) }
    private val iconConnecting by lazy {
        AnimatedState(R.drawable.ic_service_connecting) {
            hideProgress()
            delayedAnimation = (context as LifecycleOwner).lifecycleScope.launch {
                delay(context.resources.getInteger(android.R.integer.config_mediumAnimTime) + 1000L)
                // Gate the UI mutation on STARTED so a delayed progress reveal doesn't run
                // while the activity is stopped (the old launchWhenStarted suspended here).
                (context as LifecycleOwner).withStarted {
                    isIndeterminate = true
                    show()
                }
            }
        }
    }
    private val iconConnected by lazy {
        AnimatedState(R.drawable.ic_service_connected) {
            delayedAnimation?.cancel()
            setProgressCompat(1, true)
        }
    }
    private val iconStopping by lazy { AnimatedState(R.drawable.ic_service_stopping) }
    private val animationQueue = ArrayDeque<AnimatedState>()

    private var checked = false
    private var delayedAnimation: Job? = null
    private lateinit var progress: BaseProgressIndicator<*>
    fun initProgress(progress: BaseProgressIndicator<*>) {
        this.progress = progress
        progress.progressDrawable?.addSpringAnimationEndListener(this)
    }

    override fun onAnimationEnd(
        animation: DynamicAnimation<out DynamicAnimation<*>>?, canceled: Boolean, value: Float,
        velocity: Float
    ) {
        if (!canceled) progress.hide()
    }

    private fun hideProgress() {
        delayedAnimation?.cancel()
        progress.hide()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) View.mergeDrawableStates(
            drawableState,
            intArrayOf(android.R.attr.state_checked)
        )
        return drawableState
    }

    fun changeState(state: BaseService.State, previousState: BaseService.State, animate: Boolean) {
        when (state) {
            BaseService.State.Connecting -> changeState(iconConnecting, animate)
            BaseService.State.Connected -> changeState(iconConnected, animate)
            BaseService.State.Stopping -> {
                changeState(iconStopping, animate && previousState == BaseService.State.Connected)
            }
            else -> changeState(iconStopped, animate)
        }
        checked = state == BaseService.State.Connected
        refreshDrawableState()
        applyStateTint(state)
        val description = context.getText(if (state.canStop) R.string.stop else R.string.connect)
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        val enabled = state.canStop || state == BaseService.State.Stopped
        isEnabled = enabled
        if (Build.VERSION.SDK_INT >= 24) pointerIcon = PointerIcon.getSystemIcon(
            context,
            if (enabled) PointerIcon.TYPE_HAND else PointerIcon.TYPE_WAIT
        )
    }

    private fun applyStateTint(state: BaseService.State) {
        // Tint the connect FAB icon by state: connected=green, stopped=red. For
        // transient states (and on non-Dracula themes) fall back to colorOnPrimary,
        // which is the FAB's normal icon color, so those themes look unchanged.
        val attr = when (state) {
            BaseService.State.Connected -> R.attr.statusConnectedColor
            BaseService.State.Stopped -> R.attr.fabStoppedColor
            else -> com.google.android.material.R.attr.colorOnPrimary
        }
        imageTintList = ColorStateList.valueOf(context.getColorAttr(attr))
    }

    private fun changeState(icon: AnimatedState, animate: Boolean) {
        fun counters(a: AnimatedState, b: AnimatedState): Boolean =
            a == iconStopped && b == iconConnecting ||
                    a == iconConnecting && b == iconStopped ||
                    a == iconConnected && b == iconStopping ||
                    a == iconStopping && b == iconConnected
        if (animate) {
            if (animationQueue.size < 2 || !counters(animationQueue.last, icon)) {
                animationQueue.add(icon)
                if (animationQueue.size == 1) icon.start()
            } else animationQueue.removeLast()
        } else {
            animationQueue.peekFirst()?.stop()
            animationQueue.clear()
            icon.start()    // force ensureAnimatorSet to be called so that stop() will work
            icon.stop()
        }
    }
}
