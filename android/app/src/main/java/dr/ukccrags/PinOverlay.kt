package dr.ukccrags

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Whether a pin stands for a whole crag or one buttress of one. */
enum class PinKind { CRAG, BUTTRESS }

/** Something to put on the map: a crag, or one of its buttresses. */
data class Pin(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val colour: Int,
    val kind: PinKind = PinKind.CRAG,
    /** The crag this belongs to, so a group of its buttresses can be named. */
    val crag: String = "",
    /** True when the position is the parent crag's, not the thing's own. */
    val approximate: Boolean = false,
    val payload: Any? = null,
)

/**
 * Draws pins straight onto the map canvas.
 *
 * A library can hold thousands of climbs across hundreds of crags, and a
 * Marker per crag is both slow and unreadable, so pins are drawn directly and
 * grouped into a count bubble whenever several sit close together.
 *
 * Grouping is anchored to the map rather than to the screen. Bucketing by
 * screen position looked right standing still but crawled while panning: the
 * cell boundaries moved with the finger, so pins kept merging and splitting.
 * Cells are cut in Mercator world pixels at the current whole zoom instead, so
 * a pan changes nothing and only crossing a zoom level regroups anything.
 */
class PinOverlay(
    private val onPin: (Pin) -> Unit,
    private val onCluster: (GeoPoint, List<Pin>) -> Unit,
) : Overlay() {

    var pins: List<Pin> = emptyList()
        set(value) {
            field = value
            drawn = emptyList()
        }

    /** Where each pin or bubble ended up, for hit testing the last frame. */
    private var drawn: List<Triple<Float, Float, Any>> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val labelBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 165
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return

        val density = map.resources.displayMetrics.density
        val radius = 9f * density
        val cell = CELL_DP * density

        text.textSize = 11f * density
        label.textSize = 12f * density

        val projection = map.projection
        val point = android.graphics.Point()

        // World pixels at this zoom: the frame the cells are cut in.
        val zoom = map.zoomLevelDouble.toInt()
        val worldSize = 256.0 * (1 shl zoom)

        val cells = LinkedHashMap<Long, MutableList<Pin>>()
        val positions = HashMap<Pin, Pair<Float, Float>>()

        for (pin in pins) {
            projection.toPixels(GeoPoint(pin.latitude, pin.longitude), point)

            val x = point.x.toFloat()
            val y = point.y.toFloat()

            // Off-screen pins still count towards a cluster at the edge, but
            // anything far outside is not worth carrying.
            if (x < -cell || y < -cell || x > map.width + cell || y > map.height + cell) continue

            val world = worldPixels(pin.latitude, pin.longitude, worldSize)
            val column = (world.first / cell).toLong()
            val row = (world.second / cell).toLong()

            cells.getOrPut(column * 4_000_000L + row) { mutableListOf() }.add(pin)
            positions[pin] = x to y
        }

        val hits = mutableListOf<Triple<Float, Float, Any>>()

        /** Positions with the text to write beside them. */
        val labelled = mutableListOf<Triple<Float, Float, String>>()

        for (group in cells.values) {
            if (group.size == 1) {
                val pin = group.first()
                val (x, y) = positions[pin] ?: continue

                fill.color = pin.colour
                fill.alpha = if (pin.approximate) 150 else 255

                // A crag is a disc, a buttress a smaller diamond: shape carries
                // the difference even where the colours are hard to tell apart.
                if (pin.kind == PinKind.CRAG) {
                    canvas.drawCircle(x, y, radius, fill)
                    canvas.drawCircle(x, y, radius, edge)
                } else {
                    drawDiamond(canvas, x, y, radius * 0.85f, fill)
                    drawDiamond(canvas, x, y, radius * 0.85f, edge)
                }

                hits.add(Triple(x, y, pin))
                labelled.add(Triple(x, y, pin.label))
                continue
            }

            // A bubble sits at the group's own centre, not the cell's.
            var sumX = 0f
            var sumY = 0f
            for (pin in group) {
                val at = positions[pin] ?: continue
                sumX += at.first
                sumY += at.second
            }

            val x = sumX / group.size
            val y = sumY / group.size
            val bubble = radius * 1.7f

            fill.color = CLUSTER
            fill.alpha = 235

            // Same shape language as a single pin: buttresses are diamonds
            // however many of them are stacked up.
            val buttresses = group.all { it.kind == PinKind.BUTTRESS }

            if (buttresses) {
                drawDiamond(canvas, x, y, bubble * 1.15f, fill)
                drawDiamond(canvas, x, y, bubble * 1.15f, edge)
            } else {
                canvas.drawCircle(x, y, bubble, fill)
                canvas.drawCircle(x, y, bubble, edge)
            }

            canvas.drawText(
                group.size.toString(), x, y + text.textSize / 3f, text,
            )

            // The group travels with the bubble: some pins share a position
            // exactly — a buttress with no pin of its own sits on the crag's —
            // and no amount of zoom will ever separate those.
            hits.add(Triple(x, y, group.toList()))

            // Buttresses with no published position all pile onto their crag's
            // pin, and a bare count says nothing about where you are looking.
            // A group of crags stays a plain count: naming one would mislead.
            if (buttresses) {
                val crag = group.map { it.crag }.distinct().singleOrNull()
                if (!crag.isNullOrBlank()) labelled.add(Triple(x, y + bubble, crag))
            }
        }

        // Names last, and only when there is room: a screen of overlapping
        // labels is worse than none.
        if (labelled.size <= NAME_LIMIT) {
            val taken = mutableListOf<RectF>()

            for ((x, y, name) in labelled) {
                val width = label.measureText(name)
                val box = RectF(
                    x + radius + 4f, y - label.textSize / 2f - 4f,
                    x + radius + 12f + width, y + label.textSize / 2f + 4f,
                )

                if (taken.any { RectF.intersects(it, box) }) continue

                taken.add(box)
                canvas.drawRoundRect(box, 6f, 6f, labelBack)
                canvas.drawText(name, box.left + 4f, y + label.textSize / 3f, label)
            }
        }

        drawn = hits
    }

    override fun onSingleTapConfirmed(event: MotionEvent, map: MapView): Boolean {
        val reach = 26f * map.resources.displayMetrics.density

        var best: Any? = null
        var bestDistance = reach

        for ((x, y, what) in drawn) {
            val distance = kotlin.math.hypot(event.x - x, event.y - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = what
            }
        }

        return when (val hit = best) {
            is Pin -> { onPin(hit); true }

            is List<*> -> {
                val group = hit.filterIsInstance<Pin>()
                val centre = GeoPoint(
                    group.sumOf { it.latitude } / group.size,
                    group.sumOf { it.longitude } / group.size,
                )

                onCluster(centre, group)
                true
            }

            else -> false
        }
    }

    private val diamond = android.graphics.Path()

    private fun drawDiamond(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        diamond.reset()
        diamond.moveTo(x, y - size)
        diamond.lineTo(x + size, y)
        diamond.lineTo(x, y + size)
        diamond.lineTo(x - size, y)
        diamond.close()

        canvas.drawPath(diamond, paint)
    }

    /**
     * Mercator position in pixels for a whole world of [worldSize] pixels.
     * Independent of where the map happens to be scrolled to, which is the
     * whole point.
     */
    private fun worldPixels(
        latitude: Double,
        longitude: Double,
        worldSize: Double,
    ): Pair<Double, Double> {
        val x = (longitude + 180.0) / 360.0 * worldSize

        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        val radians = Math.toRadians(clamped)
        val y = (1.0 - kotlin.math.ln(
            kotlin.math.tan(radians) + 1.0 / kotlin.math.cos(radians)
        ) / Math.PI) / 2.0 * worldSize

        return x to y
    }

    private companion object {
        const val CLUSTER = 0xFF37474F.toInt()

        /**
         * Grouping distance. Kept tight on purpose: two pins touching is easier
         * to read than a bubble hiding what it holds, and the bubble lists its
         * contents anyway.
         */
        const val CELL_DP = 11f

        /** Above this many pins on screen, names are dropped as unreadable. */
        const val NAME_LIMIT = 40
    }
}
