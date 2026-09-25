package dr.ukccrags

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Whether a pin stands for a whole crag, one buttress of one, or its parking. */
enum class PinKind { CRAG, BUTTRESS, PARKING }

/** Something to put on the map: a crag, one of its buttresses, or its parking. */
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
    /** A crag's type as a letter, so the pin does not rest on colour alone. */
    val glyph: String = "",
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
 *
 * Car parks share the layer but not the rules. On a layer of their own they
 * sat under the crags' hit test, and a park within a thumb's width of its crag
 * could never be tapped; here one hit test picks whichever is nearest. They are
 * never grouped — two parks a hundred metres apart are two choices, and a
 * bubble with a 2 in it hides exactly the thing being chosen between — nor
 * named, since a park named after its crag doubles every name on screen, and
 * they are drawn first so the crags sit on top.
 */
class PinOverlay(
    private val onPin: (Pin) -> Unit,
    private val onCluster: (GeoPoint, List<Pin>) -> Unit,
    /** The letter in a crag's disc. Dark where night mode makes the discs pale. */
    private val glyphColour: Int = Color.WHITE,
) : Overlay() {

    var pins: List<Pin> = emptyList()
        set(value) {
            field = value
            hitCount = 0
            groupedZoom = -1
        }

    /**
     * One drawn thing: a lone pin, or several stacked into a bubble. Held in
     * world pixels as well as degrees so a pan can cull it with arithmetic
     * rather than a projection, and with everything a frame needs to draw it
     * already decided, since a frame is the wrong place to be deciding it.
     */
    private class Group(
        val pins: List<Pin>,
        val worldX: Double,
        val worldY: Double,
        val latitude: Double,
        val longitude: Double,
        val shape: PinKind,
        val colour: Int,
        val alpha: Int,
        /** Written inside: a crag's letter, P, or a bubble's count. */
        val mark: String,
        val markColour: Int,
        /** Written beside when there is room; null when it never should be. */
        val name: String?,
        val nameWidth: Float,
    ) {
        val lone: Boolean get() = pins.size == 1
    }

    /** Grouping only changes with the zoom, so it is kept until one changes. */
    private var groups: List<Group> = emptyList()
    private var groupedZoom = -1
    private var groupedDensity = 0f

    // Reused rather than allocated per pin per frame.
    private val scratch = GeoPoint(0.0, 0.0)
    private val point = Point()
    private val screen = Point()
    private val box = RectF()

    /** Where each pin or bubble ended up last frame, for hit testing. */
    private var hitX = FloatArray(0)
    private var hitY = FloatArray(0)
    private var hitGroup = arrayOfNulls<Group>(0)
    private var hitCount = 0

    /** Names waiting for a place: the group, its canvas and its screen position. */
    private var nameGroup = arrayOfNulls<Group>(0)
    private var nameX = FloatArray(0)
    private var nameY = FloatArray(0)
    private var nameScreenX = FloatArray(0)
    private var nameScreenY = FloatArray(0)
    private var nameCount = 0

    /** Screen rects, four floats each, that a name must not overlap. */
    private var blocked = FloatArray(0)
    private var blockedCount = 0

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
        val bubble = radius * 1.7f
        val cell = CELL_DP * density

        text.textSize = 11f * density
        label.textSize = 12f * density

        val projection = map.projection

        // MapView draws its contents turned by minus this, so anything that has
        // to stay readable is drawn back the other way about its own anchor.
        // Pins are round and do not care; text does.
        val orientation = map.mapOrientation

        // World pixels at this zoom: the frame the cells are cut in.
        val zoom = map.zoomLevelDouble.toInt()
        val worldSize = 256.0 * (1 shl zoom)

        // Grouping is anchored to the world, so panning cannot change it. It is
        // recut only when the zoom or the pins do, which takes the work out of
        // the frame the finger is dragging.
        if (zoom != groupedZoom || density != groupedDensity) {
            groups = groupPins(cell.toDouble(), worldSize)
            groupedZoom = zoom
            groupedDensity = density
        }

        // What is on screen, in the same world pixels, so a group is culled by
        // arithmetic instead of a projection each. The projection's own box,
        // not the screen's corners: turned, the screen's corners are no longer
        // its extremes, and pins near the rotated corners went undrawn.
        val view = projection.boundingBox
        val margin = cell * 3.0
        val left = worldX(view.lonWest, worldSize) - margin
        val right = worldX(view.lonEast, worldSize) + margin
        val top = worldY(view.latNorth, worldSize) - margin
        val bottom = worldY(view.latSouth, worldSize) + margin

        makeRoom(groups.size)
        hitCount = 0
        nameCount = 0
        blockedCount = 0

        for (group in groups) {
            if (group.worldX < left || group.worldX > right ||
                group.worldY < top || group.worldY > bottom
            ) continue

            scratch.setCoords(group.latitude, group.longitude)
            projection.toPixels(scratch, point)

            val x = point.x.toFloat()
            val y = point.y.toFloat()
            val size = if (group.lone) radius else bubble

            fill.color = group.colour
            fill.alpha = group.alpha

            // A crag is a disc, a buttress a smaller diamond, parking a
            // road-sign square with a P: shape carries the difference even
            // where the colours are hard to tell apart.
            when {
                group.shape == PinKind.PARKING -> {
                    drawSquare(canvas, x, y, size * 0.9f, fill)
                    drawSquare(canvas, x, y, size * 0.9f, edge)
                }
                group.shape == PinKind.BUTTRESS -> {
                    val corner = if (group.lone) size * 0.85f else size * 1.15f
                    drawDiamond(canvas, x, y, corner, fill)
                    drawDiamond(canvas, x, y, corner, edge)
                }
                else -> {
                    canvas.drawCircle(x, y, size, fill)
                    canvas.drawCircle(x, y, size, edge)
                }
            }

            if (group.mark.isNotEmpty()) {
                text.color = group.markColour
                canvas.withUpright(orientation, x, y) {
                    drawText(group.mark, x, y + text.textSize / 3f, text)
                }
            }

            // The group travels with the hit: some pins share a position
            // exactly — a buttress with no pin of its own sits on the crag's —
            // and no amount of zoom will ever separate those.
            hitX[hitCount] = x
            hitY[hitCount] = y
            hitGroup[hitCount] = group
            hitCount++

            // Names are laid out where they will be seen: upright, on the
            // turned screen. Testing them in the map's own frame and then
            // turning them passed labels that overlapped once drawn.
            val screenX: Float
            val screenY: Float
            if (orientation == 0f) {
                screenX = x
                screenY = y
            } else {
                projection.rotateAndScalePoint(point.x, point.y, screen)
                screenX = screen.x.toFloat()
                screenY = screen.y.toFloat()
            }

            block(screenX - size, screenY - size, screenX + size, screenY + size)

            if (group.name != null) {
                nameGroup[nameCount] = group
                nameX[nameCount] = x
                nameY[nameCount] = y
                nameScreenX[nameCount] = screenX
                nameScreenY[nameCount] = screenY
                nameCount++
            }
        }

        // Names last, and only when there is room: a screen of overlapping
        // labels is worse than none.
        if (nameCount <= NAME_LIMIT) placeNames(canvas, orientation, radius, bubble)
    }

    /**
     * Each name is tried beside its pin, then the other side, then above and
     * below, and dropped if every placement would sit on another pin or
     * another name. A bubble's name hangs from its foot.
     */
    private fun placeNames(canvas: Canvas, orientation: Float, radius: Float, bubble: Float) {
        val height = label.textSize + 8f
        val gap = radius + 4f

        for (i in 0 until nameCount) {
            val group = nameGroup[i] ?: continue
            val name = group.name ?: continue
            val width = group.nameWidth + 8f
            val drop = if (group.lone) 0f else bubble

            val screenX = nameScreenX[i]
            val screenY = nameScreenY[i]

            for (place in 0 until 4) {
                val offLeft: Float
                val offTop: Float
                when (place) {
                    0 -> { offLeft = gap; offTop = drop - height / 2f }
                    1 -> { offLeft = -gap - width; offTop = drop - height / 2f }
                    2 -> { offLeft = -width / 2f; offTop = drop - gap - height }
                    else -> { offLeft = -width / 2f; offTop = drop + gap }
                }

                val l = screenX + offLeft
                val t = screenY + offTop
                if (overlapsBlocked(l, t, l + width, t + height)) continue

                block(l, t, l + width, t + height)

                // The same offset from the pin, drawn upright about it, lands
                // exactly where it was tested on the turned screen.
                val x = nameX[i]
                val y = nameY[i]
                canvas.withUpright(orientation, x, y) {
                    box.set(x + offLeft, y + offTop, x + offLeft + width, y + offTop + height)
                    drawRoundRect(box, 6f, 6f, labelBack)
                    drawText(name, box.left + 4f, box.centerY() + label.textSize / 3f, label)
                }
                break
            }
        }
    }

    override fun onSingleTapConfirmed(event: MotionEvent, map: MapView): Boolean {
        val reach = 26f * map.resources.displayMetrics.density

        var best: Group? = null
        var bestDistance = reach

        for (i in 0 until hitCount) {
            val distance = kotlin.math.hypot(event.x - hitX[i], event.y - hitY[i])
            if (distance < bestDistance) {
                bestDistance = distance
                best = hitGroup[i]
            }
        }

        val hit = best ?: return false

        if (hit.lone) {
            onPin(hit.pins.first())
        } else {
            onCluster(GeoPoint(hit.latitude, hit.longitude), hit.pins)
        }
        return true
    }

    /** Grows the per-frame arrays only when there are more groups than ever before. */
    private fun makeRoom(size: Int) {
        if (hitX.size >= size) return

        hitX = FloatArray(size)
        hitY = FloatArray(size)
        hitGroup = arrayOfNulls(size)
        nameGroup = arrayOfNulls(size)
        nameX = FloatArray(size)
        nameY = FloatArray(size)
        nameScreenX = FloatArray(size)
        nameScreenY = FloatArray(size)
        // Every pin blocks once and every name may too.
        blocked = FloatArray(size * 8)
    }

    private fun block(left: Float, top: Float, right: Float, bottom: Float) {
        val at = blockedCount * 4
        if (at + 4 > blocked.size) return

        blocked[at] = left
        blocked[at + 1] = top
        blocked[at + 2] = right
        blocked[at + 3] = bottom
        blockedCount++
    }

    /** RectF.intersects, over the flat array. */
    private fun overlapsBlocked(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        for (i in 0 until blockedCount) {
            val at = i * 4
            if (left < blocked[at + 2] && blocked[at] < right &&
                top < blocked[at + 3] && blocked[at + 1] < bottom
            ) return true
        }
        return false
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

    private val square = RectF()

    private fun drawSquare(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        square.set(x - size, y - size, x + size, y + size)
        canvas.drawRoundRect(square, size * 0.3f, size * 0.3f, paint)
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
     *
     * Positions sit in arrays beside the pins rather than in a map keyed by
     * them: a pin's payload can be a whole crag, and hashing one means hashing
     * every climb in it.
     */
    private fun groupPins(cell: Double, worldSize: Double): List<Group> {
        val result = ArrayList<Group>(pins.size)

        // Parking first: what is drawn first is drawn underneath.
        for (pin in pins) {
            if (pin.kind != PinKind.PARKING) continue

            result += Group(
                pins = listOf(pin),
                worldX = worldX(pin.longitude, worldSize),
                worldY = worldY(pin.latitude, worldSize),
                latitude = pin.latitude,
                longitude = pin.longitude,
                shape = PinKind.PARKING,
                colour = pin.colour,
                alpha = 255,
                mark = "P",
                markColour = Color.WHITE,
                name = null,
                nameWidth = 0f,
            )
        }

        val marks = pins.filter { it.kind != PinKind.PARKING }
        val xs = DoubleArray(marks.size)
        val ys = DoubleArray(marks.size)
        val cells = LinkedHashMap<Long, MutableList<Int>>()

        for ((i, pin) in marks.withIndex()) {
            xs[i] = worldX(pin.longitude, worldSize)
            ys[i] = worldY(pin.latitude, worldSize)

            val column = (xs[i] / cell).toLong()
            val row = (ys[i] / cell).toLong()

            cells.getOrPut(column * 4_000_000L + row) { ArrayList(1) }.add(i)
        }

        for (members in cells.values) {
            if (members.size == 1) {
                val i = members[0]
                val pin = marks[i]
                val name = pin.label.takeIf { it.isNotBlank() }

                result += Group(
                    pins = listOf(pin),
                    worldX = xs[i],
                    worldY = ys[i],
                    latitude = pin.latitude,
                    longitude = pin.longitude,
                    shape = pin.kind,
                    colour = pin.colour,
                    alpha = if (pin.approximate) 150 else 255,
                    mark = if (pin.kind == PinKind.CRAG) pin.glyph else "",
                    markColour = glyphColour,
                    name = name,
                    nameWidth = name?.let { label.measureText(it) } ?: 0f,
                )
                continue
            }

            val group = members.map { marks[it] }
            val size = members.size

            // Buttresses with no published position all pile onto their crag's
            // pin, and a bare count says nothing about where you are looking.
            // A group of crags stays a plain count: naming one would mislead.
            val buttresses = group.all { it.kind == PinKind.BUTTRESS }
            val name = if (buttresses) {
                group.map { it.crag }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }

            result += Group(
                pins = group,
                worldX = members.sumOf { xs[it] } / size,
                worldY = members.sumOf { ys[it] } / size,
                latitude = group.sumOf { it.latitude } / size,
                longitude = group.sumOf { it.longitude } / size,
                // Same shape language as a single pin: buttresses are diamonds
                // however many of them are stacked up.
                shape = if (buttresses) PinKind.BUTTRESS else PinKind.CRAG,
                colour = CLUSTER,
                alpha = 235,
                mark = size.toString(),
                markColour = Color.WHITE,
                name = name,
                nameWidth = name?.let { label.measureText(it) } ?: 0f,
            )
        }

        return result
    }

    /*
     * Mercator position in pixels for a whole world of worldSize pixels.
     * Independent of where the map happens to be scrolled to, which is the
     * whole point.
     */

    private fun worldX(longitude: Double, worldSize: Double): Double =
        (longitude + 180.0) / 360.0 * worldSize

    private fun worldY(latitude: Double, worldSize: Double): Double {
        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        val radians = Math.toRadians(clamped)

        return (1.0 - kotlin.math.ln(
            kotlin.math.tan(radians) + 1.0 / kotlin.math.cos(radians)
        ) / Math.PI) / 2.0 * worldSize
    }

    private companion object {
        const val CLUSTER = 0xFF37474F.toInt()

        /**
         * Grouping distance. Kept tight on purpose: two pins touching is easier
         * to read than a bubble hiding what it holds, and the bubble lists its
         * contents anyway.
         */
        const val CELL_DP = 11f

        /** Above this many names on screen, they are dropped as unreadable. */
        const val NAME_LIMIT = 40
    }
}
