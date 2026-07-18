package io.nekohasekai.sagernet.ktx

import android.view.View

/**
 * Inversion seam so `ktx` (the app's leaf utility layer) can drive the main FAB without
 * importing up into the `ui`/`widget` layers (which would recreate the ktx -> ui dependency
 * cycle). MainActivity implements this over its ServiceButton FAB; the layout managers in
 * ktx only need the FAB's view bounds and show/hide, so the interface exposes exactly that.
 */
interface FabContainer {
    /** The FAB view, for visibility/bounds checks (neutral android.view.View type). */
    val fabView: View

    fun showFab()
    fun hideFab()
}
