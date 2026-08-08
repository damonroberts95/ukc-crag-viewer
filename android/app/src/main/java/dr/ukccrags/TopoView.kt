package dr.ukccrags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Draws a topo photo with each climb's line over it.
 *
 * UKC's coordinates do not reliably share the photo's orientation, and nothing
 * in the payload says which way round a given topo is, so the turn is not
 * guessed: [quarterTurns] is set per topo by the screen and remembered.
 */
class TopoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var photo: Bitmap? = null
    private var topo: Topo? = null

    /** Index of the highlighted line, or -1 for none. */
    private var focused: Int = -1

    /** The photo letterboxed into the view, before zoom and pan. */
    private val fitted = RectF()

    /** The photo as drawn, after zoom and pan. All placement works off this. */
    private val frame = RectF()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    /** Called when a tap lands on a line, or on nothing. */
    var onTap: ((TopoLine?) -> Unit)? = null

    /** Grade for each climb id, so a line can be read without opening it. */
    var grades: Map<Long, String> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    /** Draws every climb's name, not just the tapped one. */
    var showNames: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Label boxes already drawn this pass, so names do not stack up. */
    private val placed = mutableListOf<RectF>()

    /** Anticlockwise quarter turns applied to the line coordinates, 0 to 3. */
    var quarterTurns: Int = 0
        set(value) {
            field = ((value % 4) + 4) % 4
            invalidate()
        }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        alpha = 160
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
    }

    private val labelBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 170
    }

    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val path = Path()

    fun show(topo: Topo, photo: Bitmap?) {
        this.topo = topo
        this.photo = photo
        focused = -1
        resetZoom()
        invalidate()
    }

    private fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** Zooms about [focusX], [focusY] so whatever is under the fingers stays put. */
    private fun zoomTo(next: Float, focusX: Float, focusY: Float) {
        val capped = next.coerceIn(1f, MAX_ZOOM)
        val factor = capped / zoom

        panX = focusX - (focusX - panX) * factor
        panY = focusY - (focusY - panY) * factor
        zoom = capped
    }

    private val scaleGestures = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        },
    )

    private val tapGestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                down: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (zoom <= 1f) return false

                panX -= distanceX
                panY -= distanceY
                invalidate()
                return true
            }

            /** Confirmed, so a double tap does not also select a line. */
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                val line = lineAt(event.x, event.y)
                focus(line?.climbId)
                onTap?.invoke(line)
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (zoom > 1f) resetZoom() else zoomTo(DOUBLE_TAP_ZOOM, event.x, event.y)
                invalidate()
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The topo lives in a scrolling-free screen, but claim the gesture anyway
        // so a pinch is never stolen by a parent.
        parent?.requestDisallowInterceptTouchEvent(true)

        scaleGestures.onTouchEvent(event)
        if (!scaleGestures.isInProgress) tapGestures.onTouchEvent(event)

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Highlights one climb, or clears the highlight when [climbId] is null. */
    fun focus(climbId: Long?) {
        focused = topo?.lines?.indexOfFirst { it.climbId == climbId } ?: -1
        invalidate()
    }

    /**
     * Where a stored point falls on the photo, as a fraction of each edge,
     * after [quarterTurns] anticlockwise quarter turns.
     */
    private fun fraction(point: Pair<Float, Float>): Pair<Float, Float> {
        val current = topo ?: return 0f to 0f

        /*
         * Measured against UKC's own rendering, on both a portrait and a
         * landscape topo, a point lands on the stored photo at
         *
         *     across = y / image_x,  down = (image_y - x) / image_y
         *
         * which reproduced the drawn lines to within a thousandth of the
         * photo's width. The axes are swapped and one is inverted, so the
         * coordinates are effectively a quarter turn from the photo.
         */
        val imageX = if (current.width > 0) current.width.toFloat() else 1f
        val imageY = if (current.height > 0) current.height.toFloat() else 1f

        val x = point.second / imageX
        val y = (imageY - point.first) / imageY

        return when (quarterTurns) {
            1 -> y to (1f - x)
            2 -> (1f - x) to (1f - y)
            3 -> (1f - y) to x
            else -> x to y
        }
    }

    private fun placeX(point: Pair<Float, Float>): Float =
        frame.left + fraction(point).first * frame.width()

    private fun placeY(point: Pair<Float, Float>): Float =
        frame.top + fraction(point).second * frame.height()

    /** Shortest distance from [x], [y] to the segment ab. */
    private fun distanceToSegment(
        x: Float, y: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy

        // A zero-length segment is just its endpoint.
        val t = if (lengthSquared <= 0f) 0f
        else (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0f, 1f)

        return kotlin.math.hypot(x - (ax + t * dx), y - (ay + t * dy))
    }

    /**
     * The climb whose line is nearest [x], [y], within a finger's reach.
     *
     * Measured against the whole line, not just its stored points: those can
     * sit a long way apart on a straight run, which used to leave the middle
     * of a line untappable.
     */
    fun lineAt(x: Float, y: Float): TopoLine? {
        val current = topo ?: return null
        if (!layOutFrame() || current.width <= 0 || current.height <= 0) return null

        val reach = REACH_DP * resources.displayMetrics.density

        var best: TopoLine? = null
        var bestDistance = reach

        for (line in current.lines) {
            for (index in 0 until line.points.size - 1) {
                val a = line.points[index]
                val b = line.points[index + 1]

                val distance = distanceToSegment(
                    x, y,
                    placeX(a), placeY(a),
                    placeX(b), placeY(b),
                )

                if (distance < bestDistance) {
                    bestDistance = distance
                    best = line
                }
            }
        }

        return best
    }

    /**
     * Letterboxes the photo, then applies the zoom and pan. Panning is held to
     * the photo's edges, so it cannot be flicked off the screen.
     */
    private fun layOutFrame(): Boolean {
        val current = topo ?: return false
        val bitmap = photo

        val sourceWidth = (bitmap?.width ?: current.width).toFloat()
        val sourceHeight = (bitmap?.height ?: current.height).toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f || width == 0 || height == 0) return false

        val fit = minOf(width / sourceWidth, height / sourceHeight)
        val fittedWidth = sourceWidth * fit
        val fittedHeight = sourceHeight * fit
        fitted.set(
            (width - fittedWidth) / 2f,
            (height - fittedHeight) / 2f,
            (width + fittedWidth) / 2f,
            (height + fittedHeight) / 2f,
        )

        val drawnWidth = fitted.width() * zoom
        val drawnHeight = fitted.height() * zoom

        // Wider than the view: hold an edge against each side. Narrower: centre it.
        panX = if (drawnWidth <= width) (width - drawnWidth) / 2f - fitted.left * zoom
        else panX.coerceIn(width - fitted.right * zoom, -fitted.left * zoom)

        panY = if (drawnHeight <= height) (height - drawnHeight) / 2f - fitted.top * zoom
        else panY.coerceIn(height - fitted.bottom * zoom, -fitted.top * zoom)

        frame.set(
            fitted.left * zoom + panX,
            fitted.top * zoom + panY,
            fitted.right * zoom + panX,
            fitted.bottom * zoom + panY,
        )

        return true
    }

    override fun onDraw(canvas: Canvas) {
        val current = topo ?: return
        val bitmap = photo

        if (!layOutFrame()) return

        if (bitmap != null) canvas.drawBitmap(bitmap, null, frame, null)

        placed.clear()

        current.lines.forEachIndexed { index, line ->
            path.reset()

            line.points.forEachIndexed { pointIndex, point ->
                val x = placeX(point)
                val y = placeY(point)
                if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Each line keeps its own colour whether focused or not: swapping to
            // a highlight colour would break the match to its label.
            val highlighted = index == focused || focused == -1
            stroke.color = colourOf(index)
            stroke.alpha = if (highlighted) 255 else 80
            stroke.strokeWidth = if (index == focused) 11f else 6f
            outline.strokeWidth = stroke.strokeWidth + 4f
            outline.alpha = if (highlighted) 160 else 60

            canvas.drawPath(path, outline)
            canvas.drawPath(path, stroke)
        }

        // Labels last, so no line is drawn over a name.
        current.lines.forEachIndexed { index, line ->
            if (index == focused || (showNames && focused == -1)) {
                drawLabel(canvas, line, colourOf(index))
            }
        }
    }

    /** The climb's name with its grade, when one is known. */
    fun labelFor(line: TopoLine): String {
        val grade = grades[line.climbId].orEmpty()
        return if (grade.isBlank()) line.name else line.name + "  " + grade
    }

    private fun drawLabel(canvas: Canvas, line: TopoLine, colour: Int) {
        val start = line.points.first()
        val x = placeX(start)
        val y = placeY(start)

        val text = labelFor(line)
        val swatch = label.textSize * 0.55f
        val width = label.measureText(text) + swatch * 2.2f
        val left = x.coerceIn(frame.left, (frame.right - width - 16f).coerceAtLeast(frame.left))
        var top = (y - 48f).coerceAtLeast(frame.top + 34f)

        // Nudge down past anything already there, so two close starts stay legible.
        val box = RectF(left - 8f, top - 34f, left + width + 8f, top + 10f)
        var guard = 0
        while (placed.any { RectF.intersects(it, box) } && guard < 12) {
            top += 44f
            box.offset(0f, 44f)
            guard++
        }

        placed.add(RectF(box))

        canvas.drawRoundRect(box, 8f, 8f, labelBack)

        swatchPaint.color = colour
        canvas.drawCircle(left + swatch * 0.6f, top - label.textSize * 0.3f, swatch, swatchPaint)

        canvas.drawText(text, left + swatch * 2.2f, top, label)
    }

    /** The colour this line is drawn in, so a label can be matched to it. */
    fun colourOf(index: Int): Int = PALETTE[index % PALETTE.size]

    private companion object {
        /**
         * One colour per line, cycled. Neighbouring problems on a boulder are
         * what get confused, so consecutive entries are far apart in hue, and
         * every one is bright enough to survive over wet granite.
         */
        val PALETTE = intArrayOf(
            0xFFFFD24B.toInt(), // amber
            0xFF4BD2FF.toInt(), // cyan
            0xFFFF6EC7.toInt(), // pink
            0xFFA6E22E.toInt(), // lime
            0xFFFF8C42.toInt(), // orange
            0xFFB39DFF.toInt(), // violet
            0xFF2EE6C5.toInt(), // teal
            0xFFFF5C5C.toInt(), // red
        )

        /** How far off a line a tap can land, in dp — roughly a fingertip. */
        const val REACH_DP = 28f

        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 3f
    }
}
