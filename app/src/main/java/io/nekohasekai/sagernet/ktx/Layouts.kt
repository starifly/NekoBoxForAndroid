package io.nekohasekai.sagernet.ktx

import android.graphics.Rect
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.database.DataStore

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }
private var listenerDisabled = false

    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }

    override fun scrollVerticallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        // Matsuri style
        if (!DataStore.showBottomBar) return super.scrollVerticallyBy(dx, recycler, state)

        // SagerNet Style
        val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
        if (listenerDisabled) return scrollRange
        val activity = recyclerView.context as? FabContainer
        if (activity == null) {
            listenerDisabled = true
            return scrollRange
        }

        val overscroll = dx - scrollRange
        if (overscroll > 0) {
            val view =
                (
                    recyclerView.findViewHolderForAdapterPosition(findLastVisibleItemPosition())
                        ?: return scrollRange
                    ).itemView
            val itemLocation = Rect().also { view.getGlobalVisibleRect(it) }
            val fabLocation = Rect().also { activity.fabView.getGlobalVisibleRect(it) }
            if (!itemLocation.contains(fabLocation.left, fabLocation.top) && !itemLocation.contains(
                    fabLocation.right,
                    fabLocation.bottom,
                )
            ) {
                return scrollRange
            }
            if (activity.fabView.isShown) activity.hideFab()
        } else {
            /*val screen = Rect().also { activity.window.decorView.getGlobalVisibleRect(it) }
            val location = Rect().also { activity.stats.getGlobalVisibleRect(it) }
            if (screen.bottom < location.bottom) {
                return scrollRange
            }
            val height = location.bottom - location.top
            val mH = activity.stats.measuredHeight

            if (mH > height) {
                return scrollRange
            }*/

            if (!activity.fabView.isShown) activity.showFab()
        }
        return scrollRange
    }
}

class FixedGridLayoutManager(val recyclerView: RecyclerView, spanCount: Int) :
    GridLayoutManager(recyclerView.context, spanCount) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }

    fun rowIndexOf(position: Int): Int =
        position / spanCount

    override fun scrollVerticallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        // Matsuri style
        if (!DataStore.showBottomBar) return super.scrollVerticallyBy(dx, recycler, state)

        // SagerNet Style
        val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
        if (listenerDisabled) return scrollRange
        val activity = recyclerView.context as? FabContainer
        if (activity == null) {
            listenerDisabled = true
            return scrollRange
        }

        val overscroll = dx - scrollRange
        if (overscroll > 0) {
            val view =
                (
                    recyclerView.findViewHolderForAdapterPosition(findLastVisibleItemPosition())
                        ?: return scrollRange
                    ).itemView
            val itemLocation = Rect().also { view.getGlobalVisibleRect(it) }
            val fabLocation = Rect().also { activity.fabView.getGlobalVisibleRect(it) }
            if (!itemLocation.contains(fabLocation.left, fabLocation.top) && !itemLocation.contains(
                    fabLocation.right,
                    fabLocation.bottom,
                )
            ) {
                return scrollRange
            }
            if (activity.fabView.isShown) activity.hideFab()
        } else {
            if (!activity.fabView.isShown) activity.showFab()
        }
        return scrollRange
    }
}