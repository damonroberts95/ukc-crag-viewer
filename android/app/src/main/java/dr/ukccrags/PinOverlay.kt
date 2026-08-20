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
            groupedZoom = -1
        }

    /**
     * One drawn thing: a lone pin, or several stacked into a bubble. Held in
     * world pixels as well as degrees so a pan can cull it with arithmetic
     * rather than a projection.
     */
    private class Group(
        val pins: List<Pin>,
        val worldX: Double,
        val worldY: Double,
        val latitude: Double,
        val longitude: Double,
    )

    /** Grouping only changes with the zoom, so it is kept until one changes. */
    private var groups: List<Group> = emptyList()
    private var groupedZoom = -1

    /** Reused rather than allocated per pin per frame. */
    private val scratch = GeoPoint(0.0, 0.0)

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

        // MapView draws its contents turned by minus this, so anything that has
        // to stay readable is drawn back the other way about its own anchor.
        // Pins are round and do not care; text does.
        val upright = map.mapOrientation

        // World pixels at this zoom: the frame the cells are cut in.
        val zoom = map.zoomLevelDouble.toInt()
        val worldSize = 256.0 * (1 shl zoom)

        // Grouping is anchored to the world, so panning cannot change it. It is
        // recut only when the zoom or the pins do, which takes the work out of
        // the frame the finger is dragging.
        if (zoom != groupedZoom) {
            groups = groupPins(cell.toDouble(), worldSize)
            groupedZoom = zoom
        }

        // What is on screen, in the same world pixels, so a group is culled by
        // arithmetic instead of a projection each.
        val topLeft = projection.fromPixels(0, 0)
        val bottomRight = projection.fromPixels(map.width, map.height)

        val (leftWorld, topWorld) = worldPixels(
            topLeft.latitude, topLeft.longitude, worldSize,
        )
        val (rightWorld, bottomWorld) = worldPixels(
            bottomRight.latitude, bottomRight.longitude, worldSize,
        )

        val margin = cell * 3.0
        val visible = groups.filter {
            it.worldX >= leftWorld - margin && it.worldX <= rightWorld + margin &&
                it.worldY >= topWorld - margin && it.worldY <= bottomWorld + margin
        }

        val hits = mutableListOf<Triple<Float, Float, Any>>()

        /** Positions with the text to write beside them. */
        val labelled = mutableListOf<Triple<Float, Float, String>>()

        /** Where a label must not go: every pin and bubble already drawn. */
        val obstacles = mutableListOf<RectF>()

        for (entry in visible) {
            val group = entry.pins

            scratch.latitude = entry.latitude
            scratch.longitude = entry.longitude
            projection.toPixels(scratch, point)

            val x = point.x.toFloat()
            val y = point.y.toFloat()

            if (group.size == 1) {
                val pin = group.first()

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
                obstacles.add(RectF(x - radius, y - radius, x + radius, y + radius))
                continue
            }

            // The bubble sits at the group's own centre, not the cell's.
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

            canvas.withUpright(upright, x, y) {
                drawText(group.size.toString(), x, y + text.textSize / 3f, text)
            }

            // The group travels with the bubble: some pins share a position
            // exactly — a buttress with no pin of its own sits on the crag's —
            // and no amount of zoom will ever separate those.
            hits.add(Triple(x, y, group.toList()))
            obstacles.add(RectF(x - bubble, y - bubble, x + bubble, y + bubble))

            // Buttresses with no published position all pile onto their crag's
            // pin, and a bare count says nothing about where you are looking.
            // A group of crags stays a plain count: naming one would mislead.
            if (buttresses) {
                val crag = group.map { it.crag }.distinct().singleOrNull()
                if (!crag.isNullOrBlank()) labelled.add(Triple(x, y + bubble, crag))
            }
        }

        // Names last, and only when there is room: a screen of overlapping
        // labels is worse than none. Each one is tried beside its pin, then the
        // other side, then above and below, and dropped if every placement would
        // sit on another pin or another name.
        if (labelled.size <= NAME_LIMIT) {
            val taken = mutableListOf<RectF>()

            for ((x, y, name) in labelled) {
                val width = label.measureText(name) + 8f
                val height = label.textSize + 8f
                val gap = radius + 4f

                val places = listOf(
                    RectF(x + gap, y - height / 2f, x + gap + width, y + height / 2f),
                    RectF(x - gap - width, y - height / 2f, x - gap, y + height / 2f),
                    RectF(x - width / 2f, y - gap - height, x + width / 2f, y - gap),
                    RectF(x - width / 2f, y + gap, x + width / 2f, y + gap + height),
                )

                val box = places.firstOrNull { place ->
                    taken.none { RectF.intersects(it, place) } &&
                        obstacles.none { RectF.intersects(it, place) }
                } ?: continue

                taken.add(box)

                canvas.withUpright(upright, x, y) {
                    drawRoundRect(box, 6f, 6f, labelBack)
                    drawText(name, box.left + 4f, box.centerY() + label.textSize / 3f, label)
                }
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

    /**
     * Draws with the map's rotation undone about ([x], [y]), so a name stays
     * the right way up however the map is turned while staying attached to its
     * pin. A no-op — and no save/restore — while the map faces north.
     */
    private inline fun Canvas.withUpright(
        orientation: Float,
        x: Float,
        y: Float,
        draw: Canvas.() -> Unit,
    ) {
        if (orientation == 0f) {
            draw()
            return
        }

        save()
        rotate(-orientation, x, y)
        draw()
        restore()
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
     * Cuts the pins into cells of [cell] world pixels and averages each cell
     * into one drawn thing. Done once per zoom rather than once per frame.
     */
    private fun groupPins(cell: Double, worldSize: Double): List<Group> {
        val cells = LinkedHashMap<Long, MutableList<Pin>>()
        val places = HashMap<Pin, Pair<Double, Double>>(pins.size)

        for (pin in pins) {
            val world = worldPixels(pin.latitude, pin.longitude, worldSize)
            places[pin] = world

            val column = (world.first / cell).toLong()
            val row = (world.second / cell).toLong()

            cells.getOrPut(column * 4_000_000L + row) { mutableListOf() }.add(pin)
        }

        return cells.values.map { group ->
            val size = group.size

            Group(
                pins = group.toList(),
                worldX = group.sumOf { places.getValue(it).first } / size,
                worldY = group.sumOf { places.getValue(it).second } / size,
                latitude = group.sumOf { it.latitude } / size,
                longitude = group.sumOf { it.longitude } / size,
            )
        }
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
