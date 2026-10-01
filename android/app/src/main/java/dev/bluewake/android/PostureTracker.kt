package dev.bluewake.android

import android.app.Activity
import android.graphics.Rect
import androidx.core.util.Consumer
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.WindowLayoutInfo

/**
 * Watches the fold. On a foldable such as the Oppo Find N3 the inner screen is
 * nearly square and flat when open; half-folded in a laptop posture its hinge
 * runs across the window, and the game is best drawn on the upper half with the
 * controls on the lower half ("Flex mode"). This reports when that is the case
 * and where the hinge is, in the window's own coordinates.
 */
class PostureTracker(private val activity: Activity, private val onChange: (Posture) -> Unit) {
    /** [hinge] is the folding feature's bounds when the device is half-open with a horizontal hinge. */
    data class Posture(val hinge: Rect?) {
        val flex get() = hinge != null
    }

    private val adapter = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity))
    private val listener = Consumer<WindowLayoutInfo> { info ->
        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        val flex = fold != null && fold.state == FoldingFeature.State.HALF_OPENED &&
            fold.orientation == FoldingFeature.Orientation.HORIZONTAL
        onChange(Posture(if (flex) Rect(fold!!.bounds) else null))
    }

    fun start() {
        // Delivered on the main thread, where the layout is changed.
        adapter.addWindowLayoutInfoListener(activity, activity.mainExecutor, listener)
    }

    fun stop() {
        adapter.removeWindowLayoutInfoListener(listener)
    }
}
