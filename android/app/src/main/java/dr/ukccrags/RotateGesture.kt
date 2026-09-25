package dr.ukccrags

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Two-finger rotation that gets out of the way of zooming.
 *
 * osmdroid's own rotation overlay turns the map whenever the fingers twist,
 * including through a pinch, and a pinch is never a perfectly parallel one — so
 * zooming came with an unasked-for tilt of the whole map. Zoom is the gesture
 * that matters, so each two-finger gesture is claimed by one or the other and
 * keeps it until the fingers come up:
 *
 * - the distance between the fingers changing first means zoom, and rotation is
 *   locked out for the rest of the gesture;
 * - a real twist with the distance held means rotation.
 *
 * Zoom itself is left to the map's own handling, so nothing here can slow it
 * down or fight it.
 *
 * Angles are measured in raw screen coordinates on purpose. MapView rotates the
 * touch events it hands to its overlays into the map's own frame, so measuring
 * the twist in overlay coordinates while turning the map means the measurement
 * turns with it: each frame's rotation inflates the next and the map spins away
 * on a tiny twist.
 *
 * Below API 29 there are no per-pointer raw coordinates, so rotation is off
 * there rather than half-working: the overlay-frame values have the map's own
 * turn baked in and the same feedback spins it away.
 *
 * It also remembers where the last pinch was centred, which is the point the
 * settling zoom should hold still: see [takePinchFocus].
 */
class RotateGesture(private val map: MapView) : Overlay() {

    private enum class Claim { UNDECIDED, ZOOM, ROTATE }

    private var claim = Claim.UNDECIDED

    /** The finger spread and angle a gesture started with. */
    private var startSpread = 0f
    private var startAngle = 0f

    /** The angle last acted on, so only the change is applied. */
    private var lastAngle = 0f

    /**
     * Midpoint of the fingers in the frame MapView's own pinch sees — the
     * event as handed to overlays — so a zoom fixed on it continues the pinch
     * rather than sliding the map. NaN when there has been no pinch since the
     * last one was taken.
     */
    private var focusX = Float.NaN
    private var focusY = Float.NaN

    /**
     * Where the last pinch was centred, once. A zoom that settles about the
     * screen's centre instead shifts whatever was under the fingers.
     */
    fun takePinchFocus(): android.graphics.PointF? {
        if (focusX.isNaN()) return null

        val focus = android.graphics.PointF(focusX, focusY)
        focusX = Float.NaN
        focusY = Float.NaN
        return focus
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                focusX = Float.NaN
                focusY = Float.NaN
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && ROTATES) begin(event)

            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                focusX = (event.getX(0) + event.getX(1)) / 2f
                focusY = (event.getY(0) + event.getY(1)) / 2f
                if (event.pointerCount == 2 && ROTATES) move(event)
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> claim = Claim.UNDECIDED
        }

        // Never consumed: the map still gets every event, so its own pinch zoom
        // and panning work exactly as before.
        return false
    }

    @androidx.annotation.RequiresApi(29)
    private fun begin(event: MotionEvent) {
        claim = Claim.UNDECIDED
        startSpread = spread(event)
        startAngle = angle(event)
        lastAngle = startAngle
    }

    @androidx.annotation.RequiresApi(29)
    private fun move(event: MotionEvent) {
        val nowSpread = spread(event)
        val nowAngle = angle(event)

        if (claim == Claim.UNDECIDED) {
            val spreadChange = if (startSpread > 0) abs(nowSpread / startSpread - 1f) else 0f
            val turned = abs(shortestWay(nowAngle - startAngle))

            claim = when {
                spreadChange > SPREAD_SLOP -> Claim.ZOOM
                turned > ANGLE_SLOP -> Claim.ROTATE
                else -> return
            }

            lastAngle = nowAngle
            if (claim == Claim.ZOOM) return
        }

        if (claim != Claim.ROTATE) return

        // A finger lifting or a third landing can renumber the pointers and
        // swing the measured angle right round; a real twist between two frames
        // is small.
        val delta = shortestWay(nowAngle - lastAngle).coerceIn(-MAX_STEP, MAX_STEP)
        lastAngle = nowAngle

        map.mapOrientation = map.mapOrientation + delta
        map.invalidate()
    }

    @androidx.annotation.RequiresApi(29)
    private fun spread(event: MotionEvent): Float =
        hypot(rawX(event, 1) - rawX(event, 0), rawY(event, 1) - rawY(event, 0))

    /** Clockwise-positive, since screen y runs downwards. */
    @androidx.annotation.RequiresApi(29)
    private fun angle(event: MotionEvent): Float = Math.toDegrees(
        atan2(
            (rawY(event, 1) - rawY(event, 0)).toDouble(),
            (rawX(event, 1) - rawX(event, 0)).toDouble(),
        )
    ).toFloat()

    // Only ever reached where ROTATES holds.
    @androidx.annotation.RequiresApi(29)
    private fun rawX(event: MotionEvent, pointer: Int): Float = event.getRawX(pointer)

    @androidx.annotation.RequiresApi(29)
    private fun rawY(event: MotionEvent, pointer: Int): Float = event.getRawY(pointer)

    /** Keeps a turn through the ±180° seam from reading as a full spin. */
    private fun shortestWay(degrees: Float): Float {
        var turn = degrees % 360f
        if (turn > 180f) turn -= 360f
        if (turn < -180f) turn += 360f
        return turn
    }

    private companion object {
        /** Raw per-pointer coordinates, which rotation needs, arrived in API 29. */
        val ROTATES = android.os.Build.VERSION.SDK_INT >= 29

        /** Fingers moving 8% closer or further apart is a pinch, not a twist. */
        const val SPREAD_SLOP = 0.08f

        /**
         * How far the fingers must twist, in degrees, to claim a rotation. Low
         * enough to start on a small turn of the wrist; the spread test above is
         * what keeps a pinch from being read as one.
         */
        const val ANGLE_SLOP = 6f

        /** Most degrees one frame of twisting can be worth. */
        const val MAX_STEP = 20f
    }
}
