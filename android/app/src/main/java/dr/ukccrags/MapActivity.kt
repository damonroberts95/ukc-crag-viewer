package dr.ukccrags

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivityMapBinding
import dr.ukccrags.databinding.ItemLegendBinding
import dr.ukccrags.databinding.SheetPinBinding
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** A buttress and the crag it belongs to, as carried by a pin. */
private data class ButtressAt(val crag: Crag, val buttress: Buttress)

/**
 * The library on a map.
 *
 * Two modes: every stored crag, or the buttresses of one crag. The second is
 * the one that matters at the rock, where the question is which lump of stone
 * you are standing under.
 *
 * Nothing here assumes the crags are near each other. A library grown from
 * ticklists can be scattered across countries, so the opening view is fitted
 * to whatever is actually stored.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    private var crags: List<Crag> = emptyList()
    private var single: Crag? = null

    private lateinit var overlay: PinOverlay
    private var locator: MyLocationNewOverlay? = null

    /** The walking line currently drawn: a dark casing under a bright core. */
    private var walkLine: List<Polyline> = emptyList()

    /** True while the map is drawn buttress by buttress rather than crag by crag. */
    private var detailed = false

    /** The opening view is framed once; later rebuilds must not move the map. */
    private var framed = false

    /** Where the pins were last built for, so a small pan can be ignored. */
    private var builtFor: org.osmdroid.util.BoundingBox? = null

    /** The current suggestions, in the order the dropdown lists them. */
    private var found: List<Pair<String, GeoPoint>> = emptyList()

    private var pendingQuery = ""
    private val suggestWhenStill = Runnable { suggest(pendingQuery) }

    /** What each crag mostly holds, and the pin colour that follows from it. */
    private var pinTypes: Map<String, String> = emptyMap()
    private var pinColours: Map<String, Int> = emptyMap()

    private val settle = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebuildWhenStill = Runnable { rebuildNow() }

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            startLocating()
            goToMe()
        } else {
            note(getString(R.string.need_location))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        crags = CragStore.load(this).filter { it.hasPin }

        // Working out what a crag mostly holds walks all of its climbs. A zoom
        // used to do that for every crag in the library, twice — once for the
        // pins and once for the legend — which is what made a pinch stutter.
        // None of it can change while this screen is open.
        pinTypes = crags.associate { it.area to it.dominantType() }
        pinColours = pinTypes.mapValues { (_, type) -> pinColour(type) }
        single = intent.getStringExtra(EXTRA_AREA)?.let { area ->
            CragStore.load(this).firstOrNull { it.area == area }
        }

        supportActionBar?.title = single?.area ?: getString(R.string.map)

        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        applySource(MapSources.chosen(this))

        // Out of signal and past what is cached, osmdroid draws a grey grid of
        // "no tile" squares. The pins, the walking line and the location dot
        // are the parts that actually navigate, so let them sit on a plain
        // ground instead of a chessboard.
        binding.map.overlayManager.tilesOverlay.apply {
            loadingBackgroundColor = ContextCompat.getColor(
                this@MapActivity, R.color.map_empty,
            )
            loadingLineColor = ContextCompat.getColor(
                this@MapActivity, R.color.map_empty_line,
            )
        }
        binding.map.setMultiTouchControls(true)

        // A pinch leaves the map on a fractional zoom, and a fractional zoom
        // means every tile is drawn scaled — soft at any distance, which is
        // what made even a zoomed-out map look blurry. Rounding settles on a
        // whole level, where a tile is drawn at the size it was made.
        binding.map.setZoomRounding(true)
        binding.map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )

        overlay = PinOverlay(
            onPin = { showSheet(it) },
            onCluster = { centre, group -> openCluster(centre, group) },
        )

        binding.map.overlays.add(overlay)

        // Two fingers turn the map. Stood under a crag, matching the map to
        // what you are looking at beats knowing where north is. Zoom takes
        // precedence: see RotateGesture.
        binding.map.overlays.add(RotateGesture(binding.map))

        // A turned map needs a way back, and a permanent button for it would be
        // clutter, so the chip appears only once the map is off north.
        binding.map.overlays.add(NorthWatcher())

        binding.north.setOnClickListener {
            binding.map.mapOrientation = 0f
            binding.map.invalidate()
        }

        startLocating()
        binding.here.setOnClickListener { goToMe() }
        setUpSearch()
        binding.clearWalk.setOnClickListener { clearWalk() }

        // Zooming in far enough swaps crags for their buttresses, so the map
        // answers "which lump of rock" once it can show them apart.
        binding.map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                if (detailed) rebuild()
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                rebuild()
                return false
            }
        })

        buildPins()
        buildLegend()
    }

    /**
     * A bubble says what it holds rather than making you guess by zooming. Some
     * groups never come apart anyway: buttresses UKC gives no position for all
     * sit on the crag's own pin.
     */
    private fun openCluster(centre: GeoPoint, group: List<Pin>) {
        val ordered = group.sortedBy { it.label.lowercase() }

        // Buttresses of one crag are that crag, so name it. A group of crags is
        // just a group, and naming any one of them would be misleading.
        val shared = group
            .takeIf { pins -> pins.all { it.kind == PinKind.BUTTRESS } }
            ?.map { it.crag }
            ?.distinct()
            ?.singleOrNull()
            ?.takeIf { it.isNotBlank() }

        val count = resources.getQuantityString(R.plurals.here_count, group.size, group.size)
        val title = if (shared == null) count else "$shared · $count"

        val labels = ordered.map { pin ->
            val kind = if (pin.kind == PinKind.CRAG) "" else " · " +
                getString(R.string.buttresses_legend).lowercase()

            // With the crag in the title, repeating it on every row is noise.
            val where = if (shared == null && pin.kind == PinKind.BUTTRESS) {
                pin.crag + " · "
            } else {
                ""
            }

            where + pin.label + kind +
                if (pin.approximate) " · " + getString(R.string.pin_approximate) else ""
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> goToPin(ordered[which]) }
            .setNeutralButton(R.string.zoom_in) { _, _ ->
                binding.map.controller.animateTo(centre, binding.map.zoomLevelDouble + 2.0, 400L)
            }
            .show()
    }

    /** Centres on one pin and opens it, since a shared position cannot be zoomed apart. */
    private fun goToPin(pin: Pin) {
        val target = GeoPoint(pin.latitude, pin.longitude)
        val zoom = binding.map.zoomLevelDouble.coerceAtLeast(16.5)

        binding.map.controller.animateTo(target, zoom, 400L)
        binding.map.postDelayed({ showSheet(pin) }, 450)
    }

    /**
     * Redraws when the view changes. Buttress pins are built only for the crags
     * on screen: every buttress in the library at once would be thousands of
     * pins, nearly all of them off screen.
     *
     * A scroll fires this continuously, and rebuilding the pin list under a
     * moving finger is what made panning stutter. While detailed, it rebuilds
     * only once the view has moved far enough to have brought a new crag in.
     */
    private fun rebuild() {
        // A pinch fires a zoom event per frame, and rebuilding the pin list on
        // each one is the jitter. Doing it once the fingers stop is invisible.
        settle.removeCallbacks(rebuildWhenStill)
        settle.postDelayed(rebuildWhenStill, SETTLE_MS)
    }

    private fun rebuildNow() {
        val wanted = single == null && binding.map.zoomLevelDouble >= BUTTRESS_ZOOM

        if (wanted == detailed && !wanted) return
        if (wanted && detailed && !movedFar()) return

        val swapped = wanted != detailed
        detailed = wanted

        buildPins()

        // The legend only says which kinds of pin are on screen, so it changes
        // when the mode does, not on every pan.
        if (swapped) buildLegend()
    }

    /**
     * Watches the map's own orientation. Rotation is a gesture on the map, not
     * an event the map reports, so this rides along with the drawing instead.
     */
    private inner class NorthWatcher : org.osmdroid.views.overlay.Overlay() {

        private var wasTurned = false

        override fun draw(canvas: android.graphics.Canvas, map: org.osmdroid.views.MapView, shadow: Boolean) {
            if (shadow) return

            val turned = map.mapOrientation != 0f
            if (turned == wasTurned) return

            wasTurned = turned
            binding.north.visibility = if (turned) View.VISIBLE else View.GONE
        }
    }

    /** True once the view has shifted by a third of its own width or height. */
    private fun movedFar(): Boolean {
        val box = binding.map.boundingBox
        val last = builtFor ?: return true

        val latitudeSpan = box.latNorth - box.latSouth
        val longitudeSpan = box.lonEast - box.lonWest

        return kotlin.math.abs(box.centerLatitude - last.centerLatitude) >
            latitudeSpan / 3 ||
            kotlin.math.abs(box.centerLongitude - last.centerLongitude) >
            longitudeSpan / 3
    }

    /**
     * Finding somewhere on the map.
     *
     * Two kinds of answer from one box. Stored crags are matched here, which
     * costs nothing and works with no signal — the case that matters at the
     * rock. Place names go to Android's own geocoder, which needs a connection;
     * it is how you get the map to the valley before you have imported anything
     * in it. Crags come first in the list either way: this app's own library is
     * the more likely thing to be looking for.
     */
    private fun setUpSearch() {
        binding.search.setOnItemClickListener { _, _, position, _ ->
            found.getOrNull(position)?.let { (_, where) ->
                binding.map.controller.animateTo(where, 15.0, 700L)

                // Out of the way once it has done its job.
                binding.search.setText("", false)
                binding.search.clearFocus()
                binding.searchBox.clearFocus()
            }
        }

        binding.search.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty().trim()

            settle.removeCallbacks(suggestWhenStill)
            if (query.length < 2) return@doAfterTextChanged

            pendingQuery = query
            settle.postDelayed(suggestWhenStill, SUGGEST_MS)
        }
    }

    private fun suggest(query: String) {
        val wanted = query.lowercase()

        // Already only the crags with a published position.
        val hits = crags
            .filter { it.area.lowercase().contains(wanted) }
            .take(CRAG_HITS)
            .map { crag ->
                getString(
                    R.string.map_search_crag,
                    crag.area,
                    resources.getQuantityString(
                        R.plurals.climbs, crag.climbCount, crag.climbCount,
                    ),
                ) to GeoPoint(crag.latitude!!, crag.longitude!!)
            }

        show(hits)

        // The geocoder is a network call, so the crags are offered first and
        // the places join them when they arrive.
        if (!android.location.Geocoder.isPresent()) return

        Thread {
            val places = runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(this)
                    .getFromLocationName(query, PLACE_HITS)
                    .orEmpty()
                    .map { place ->
                        val name = listOfNotNull(
                            place.featureName,
                            place.locality ?: place.subAdminArea,
                            place.countryName,
                        ).distinct().joinToString(", ")

                        name to GeoPoint(place.latitude, place.longitude)
                    }
            }.getOrDefault(emptyList())

            runOnUiThread {
                if (isFinishing || binding.search.text?.toString()?.trim() != query) {
                    return@runOnUiThread
                }

                show(hits + places)
            }
        }.start()
    }

    private fun show(hits: List<Pair<String, GeoPoint>>) {
        found = hits

        binding.search.setSimpleItems(hits.map { it.first }.toTypedArray())
        if (hits.isNotEmpty() && binding.search.hasFocus()) binding.search.showDropDown()
    }

    /** The reader's own position, when the permission is already granted. */
    private fun startLocating() {
        if (!Nearby.granted(this)) return

        locator = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map).apply {
            // osmdroid keeps two icons — a standing figure, and an arrow for
            // when a fix carries a bearing — and swaps between them as fixes
            // arrive, which reads as flickering. One dot for both states.
            val dot = locationDot()
            setDirectionArrow(dot, dot)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)

            enableMyLocation()
            binding.map.overlays.add(this)
        }
    }

    /** A plain dot: position, no implied heading. */
    private fun locationDot(): android.graphics.Bitmap {
        val density = resources.displayMetrics.density
        val size = (18 * density).toInt()

        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888,
        )

        val canvas = android.graphics.Canvas(bitmap)
        val middle = size / 2f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(middle, middle, middle - 1f, paint)

        paint.color = ContextCompat.getColor(this, R.color.here_dot)
        canvas.drawCircle(middle, middle, middle - 4f * density, paint)

        return bitmap
    }

    /**
     * An Approximate grant blurs the fix to about a kilometre, which is the
     * difference between finding a boulder and standing in the wrong field, so
     * it is worth asking to upgrade — once. Android shows its own precise or
     * approximate choice, and a refusal is remembered rather than raised again.
     */
    private fun goToMe() {
        if (!Nearby.granted(this)) {
            askLocation.launch(Nearby.PERMISSIONS)
            return
        }

        if (!Nearby.precise(this) && !prefs().getBoolean(KEY_ASKED_PRECISE, false)) {
            prefs().edit().putBoolean(KEY_ASKED_PRECISE, true).apply()

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.precise_location)
                .setMessage(R.string.precise_location_why)
                .setPositiveButton(R.string.precise_location_ask) { _, _ ->
                    askLocation.launch(Nearby.PERMISSIONS)
                }
                .setNegativeButton(R.string.keep_approximate) { _, _ -> centreOnFix() }
                .show()
            return
        }

        centreOnFix()
    }

    private fun centreOnFix() {
        val fix = locator?.myLocation ?: Nearby.lastKnown(this)?.let {
            GeoPoint(it.latitude, it.longitude)
        }

        if (fix == null) {
            note(getString(R.string.no_location))
            return
        }

        // A blurred fix should not be shown at street zoom: it would look far
        // more certain than it is.
        val precise = Nearby.precise(this)

        binding.map.controller.animateTo(fix, if (precise) 15.5 else 13.0, 500L)
        if (!precise) note(getString(R.string.approximate_fix))
    }

    private fun prefs() = getSharedPreferences("location", MODE_PRIVATE)

    private fun buildPins() {
        builtFor = binding.map.boundingBox
        val crag = single

        val pins = when {
            crag != null -> buttressPins(crag)
            detailed -> onScreenButtressPins()
            else -> crags.map {
                Pin(
                    label = it.area,
                    latitude = it.latitude!!,
                    longitude = it.longitude!!,
                    colour = pinColours[it.area] ?: pinColour(""),
                    crag = it.area,
                    payload = it,
                )
            }
        }

        overlay.pins = pins

        if (pins.isEmpty() && !detailed) {
            note(getString(R.string.map_nothing))
        } else if (!framed && pins.isNotEmpty()) {
            framed = true
            fitTo(pins)
        }

        binding.map.invalidate()
    }

    /**
     * UKC publishes a pin for only about half of all buttresses. The rest fall
     * back to the crag's own position, drawn faded and labelled as such, so
     * every buttress can still be reached from here.
     */
    private fun buttressPins(crag: Crag): List<Pin> {
        val fallbackNeeded = crag.buttresses.count { !it.hasPin }
        if (fallbackNeeded > 0 && crag.hasPin) {
            note(resources.getQuantityString(
                R.plurals.map_approximate, fallbackNeeded, fallbackNeeded,
            ))
        }

        return crag.buttresses.mapNotNull { buttress ->
            val latitude = buttress.latitude ?: crag.latitude
            val longitude = buttress.longitude ?: crag.longitude
            if (latitude == null || longitude == null) return@mapNotNull null

            Pin(
                label = buttress.name.ifBlank { crag.area },
                latitude = latitude,
                longitude = longitude,
                colour = ContextCompat.getColor(this, R.color.pin_buttress),
                kind = PinKind.BUTTRESS,
                crag = crag.area,
                approximate = !buttress.hasPin,
                payload = ButtressAt(crag, buttress),
            )
        }
    }

    /** Buttresses of the crags currently in view, the crag pin standing in
     * where UKC publishes no position for one. */
    private fun onScreenButtressPins(): List<Pin> {
        val box = binding.map.boundingBox
        val margin = 0.02

        val nearby = crags.filter {
            it.latitude!! in (box.latSouth - margin)..(box.latNorth + margin) &&
                it.longitude!! in (box.lonWest - margin)..(box.lonEast + margin)
        }

        return nearby.flatMap { crag ->
            crag.buttresses.mapNotNull { buttress ->
                val latitude = buttress.latitude ?: crag.latitude
                val longitude = buttress.longitude ?: crag.longitude
                if (latitude == null || longitude == null) return@mapNotNull null

                Pin(
                    label = buttress.name.ifBlank { crag.area },
                    latitude = latitude,
                    longitude = longitude,
                    colour = ContextCompat.getColor(this, R.color.pin_buttress),
                    kind = PinKind.BUTTRESS,
                    crag = crag.area,
                    approximate = !buttress.hasPin,
                    payload = ButtressAt(crag, buttress),
                )
            }
        }
    }

    /** Frames whatever is stored, however widely spread. */
    private fun fitTo(pins: List<Pin>) {
        val north = pins.maxOf { it.latitude }
        val south = pins.minOf { it.latitude }
        val east = pins.maxOf { it.longitude }
        val west = pins.minOf { it.longitude }

        // A single pin, or several at one spot, has no extent to fit to.
        if (north - south < 0.002 && east - west < 0.002) {
            binding.map.controller.setZoom(15.0)
            binding.map.controller.setCenter(GeoPoint(north, east))
            return
        }

        val box = BoundingBox(north, east, south, west)

        // Waiting for layout: zoomToBoundingBox needs a measured map.
        binding.map.post {
            binding.map.zoomToBoundingBox(box.increaseByScale(1.2f), false)
        }
    }


    /**
     * Only the types actually present, since a fixed key would list Winter to
     * a reader whose library is all bouldering.
     */
    private fun buildLegend() {
        binding.legend.removeAllViews()

        if (single != null || detailed) {
            addLegend(getString(R.string.buttresses_legend), R.color.pin_buttress)
            return
        }

        val present = crags.mapNotNull { pinTypes[it.area] }
            .distinct()
            .map { it to pinColour(it) }
            .distinctBy { it.second }

        for ((type, colour) in present.sortedBy { it.first }) {
            addLegend(type.ifBlank { getString(R.string.type_unknown) }, null, colour)
        }
    }

    private fun addLegend(text: String, colourRes: Int?, colour: Int? = null) {
        val row = ItemLegendBinding.inflate(layoutInflater, binding.legend, false)

        row.name.text = text
        row.dot.background = ColorDrawable(
            colour ?: ContextCompat.getColor(this, colourRes!!)
        )

        binding.legend.addView(row.root)
    }

    private fun note(message: String) {
        binding.note.text = message
        binding.note.visibility = View.VISIBLE
        binding.note.postDelayed({ binding.note.visibility = View.GONE }, 6000)
    }

    private fun showSheet(pin: Pin) {
        val view = SheetPinBinding.inflate(layoutInflater)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(view.root)

        when (val what = pin.payload) {
            is Crag -> fillCrag(view, what, pin, sheet)
            is ButtressAt -> fillButtress(view, what, pin, sheet)
        }

        sheet.show()
    }

    private fun fillCrag(
        view: SheetPinBinding,
        crag: Crag,
        pin: Pin,
        sheet: BottomSheetDialog,
    ) {
        val ticks = Ticks(this)
        val away = crag.metresFrom(Nearby.lastKnown(this))

        view.name.text = crag.area
        view.detail.text = buildString {
            append(resources.getQuantityString(
                R.plurals.climbs, crag.climbCount, crag.climbCount,
            ))
            append(" · ").append(getString(R.string.crag_progress, ticks.countIn(crag), crag.climbCount))
            if (away != null) append(" · ").append(Units.distance(this@MapActivity, away))
        }

        view.open.text = getString(R.string.open_crag)
        view.open.setOnClickListener {
            sheet.dismiss()
            startActivity(
                Intent(this, CragActivity::class.java)
                    .putExtra(CragActivity.EXTRA_AREA, crag.area)
            )
        }

        view.directions.setOnClickListener {
            sheet.dismiss()
            Maps.open(this, pin.latitude, pin.longitude, crag.area)
        }

        view.walk.setOnClickListener {
            sheet.dismiss()
            walkTo(crag, pin)
        }

        view.topos.visibility = if (crag.topos.isEmpty()) View.GONE else View.VISIBLE
        view.topos.text = getString(R.string.topo_count, crag.topos.size)
        view.topos.setOnClickListener {
            sheet.dismiss()
            startActivity(
                Intent(this, TopoActivity::class.java)
                    .putExtra(TopoActivity.EXTRA_AREA, crag.area)
            )
        }
    }

    private fun fillButtress(
        view: SheetPinBinding,
        at: ButtressAt,
        pin: Pin,
        sheet: BottomSheetDialog,
    ) {
        val crag = at.crag
        val buttress = at.buttress

        view.name.text = buttress.name.ifBlank { crag.area }
        view.detail.text = buildString {
            // On the library map the crag is not the screen's title, so say it.
            if (single == null) append(crag.area).append(" · ")
            append(resources.getQuantityString(
                R.plurals.climbs, buttress.climbs.size, buttress.climbs.size,
            ))
            if (pin.approximate) append(" · ").append(getString(R.string.pin_approximate))
        }

        view.open.text = getString(R.string.show_these_climbs)
        view.open.setOnClickListener {
            sheet.dismiss()
            startActivity(
                Intent(this, CragActivity::class.java)
                    .putExtra(CragActivity.EXTRA_AREA, crag.area)
                    // Into the search box rather than a hidden filter, so it
                    // is visible, removable, and narrows the topos as well.
                    .putExtra(CragActivity.EXTRA_FIND, buttress.name)
            )
        }

        view.directions.setOnClickListener {
            sheet.dismiss()
            Maps.open(this, pin.latitude, pin.longitude, buttress.name)
        }

        view.walk.setOnClickListener {
            sheet.dismiss()
            walkTo(crag, pin)
        }

        view.topos.visibility = View.GONE
    }

    /**
     * Draws a walking line from the reader to a pin.
     *
     * The paths come from Overpass, asked for only here and cached per crag, so
     * a second buttress at the same crag costs nothing and a later visit works
     * with no signal. With no paths within reach the line is straight, and says
     * so rather than pretending.
     */
    private fun walkTo(crag: Crag, pin: Pin) {
        val fix = locator?.myLocation ?: Nearby.lastKnown(this)?.let {
            GeoPoint(it.latitude, it.longitude)
        }

        if (fix == null) {
            note(getString(R.string.no_location))
            return
        }

        val away = Walk.metresBetween(
            fix.latitude, fix.longitude, pin.latitude, pin.longitude,
        )

        // Too far to walk from where you are standing, so route the leg that
        // matters: the crag's own pin to the buttress. That is the approach you
        // actually want when planning from home, and it keeps the query small.
        val distant = away > Walk.MAX_SPAN_METRES && crag.hasPin

        val fromLat = if (distant) crag.latitude!! else fix.latitude
        val fromLon = if (distant) crag.longitude!! else fix.longitude

        note(
            if (distant) getString(R.string.walk_from_crag, Units.distance(this, away.toFloat()))
            else getString(R.string.walk_working)
        )

        Thread {
            val route = Walk.route(this, crag.id, fromLat, fromLon, pin.latitude, pin.longitude)

            runOnUiThread { drawWalk(route, pin, distant) }
        }.start()
    }

    private fun drawWalk(route: WalkRoute, pin: Pin, fromCrag: Boolean = false) {
        walkLine.forEach { binding.map.overlays.remove(it) }

        val points = route.points.map { GeoPoint(it.first, it.second) }

        // A map is already full of greens and greys, so the line gets an orange
        // core over a dark casing: readable over fields, woods, water or rock.
        val casing = Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.strokeWidth = 18f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.color = ContextCompat.getColor(this@MapActivity, R.color.walk_casing)
            outlinePaint.alpha = 210
        }

        val core = Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.color = ContextCompat.getColor(this@MapActivity, R.color.walk_line)

            // A guessed line should not look as certain as a followed path.
            if (!route.onPaths) outlinePaint.pathEffect =
                android.graphics.DashPathEffect(floatArrayOf(22f, 18f), 0f)
        }

        walkLine = listOf(casing, core)
        binding.clearWalk.visibility = View.VISIBLE
        binding.map.overlays.add(0, core)
        binding.map.overlays.add(0, casing)
        binding.map.invalidate()

        val distance = Units.distance(this, route.metres.toFloat())
        val where = if (fromCrag) getString(R.string.from_crag_pin) else ""

        // Far enough that no route was attempted: say that, rather than let it
        // read as "there are no paths here".
        if (route.tooFar) {
            Toast.makeText(
                this, getString(R.string.walk_too_far, distance), Toast.LENGTH_LONG,
            ).show()
        }

        note(
            when {
                route.tooFar -> getString(R.string.walk_too_far, distance)
                route.partial -> getString(R.string.walk_partly, distance, pin.label)
                route.onPaths -> getString(R.string.walk_on_paths, distance, pin.label)
                else -> getString(R.string.walk_straight, distance, pin.label)
            } + where
        )
    }

    private fun clearWalk() {
        walkLine.forEach { binding.map.overlays.remove(it) }
        walkLine = emptyList()
        binding.clearWalk.visibility = View.GONE
        binding.map.invalidate()
    }

    /**
     * Draws the map from whichever source was last chosen. Everything on offer
     * is online: what makes the map work at a crag is the cache, not a download
     * button, since OpenStreetMap's terms forbid pulling their tiles down ahead
     * of time and no aerial provider gives an offline basemap away.
     */
    private fun applySource(id: String) {
        MapSources.choose(this, id)

        val tiles = MapSources.tileSource(id)

        binding.map.tileProvider?.detach()
        binding.map.setTileProvider(org.osmdroid.tileprovider.MapTileProviderBasic(this))
        binding.map.setTileSource(tiles)

        // Every source runs out of data somewhere — 14 for Sentinel-2, 20 for
        // Esri — and past that osmdroid enlarges the deepest tile it has. Soft
        // pixels beat a wall you cannot zoom through when you are trying to see
        // which side of a wall a boulder sits on, so the map goes further in
        // than any of them can actually draw.
        binding.map.maxZoomLevel = MAP_MAX_ZOOM

        // All of these require crediting, and the credit belongs on the map.
        binding.credit.text = MapSources.attribution(this, id)
        binding.map.invalidate()

        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        // Built by hand rather than from XML: which sources exist depends on
        // whether the reader has dropped an API key in beside their maps.
        val chosen = MapSources.chosen(this)

        for ((order, id) in MapSources.available().withIndex()) {
            menu.add(MENU_SOURCES, order, order, MapSources.label(this, id)).apply {
                isCheckable = true
                isChecked = id == chosen
            }
        }

        menu.setGroupCheckable(MENU_SOURCES, true, true)
        menu.add(0, MENU_CACHE, 100, R.string.map_cache_size)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val sources = MapSources.available()

        if (item.groupId == MENU_SOURCES && item.itemId in sources.indices) {
            applySource(sources[item.itemId])
            return true
        }

        if (item.itemId == MENU_CACHE) {
            Toast.makeText(
                this,
                getString(R.string.map_cached, MapSources.cachedMegabytes(this)),
                Toast.LENGTH_LONG,
            ).show()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        settle.removeCallbacks(rebuildWhenStill)
        binding.map.onPause()
        super.onPause()
    }

    companion object {
        /** Set to a crag's name to map that crag's buttresses instead. */
        const val EXTRA_AREA = "area"

        /** Zoom at which buttresses are far enough apart to be worth drawing. */
        private const val BUTTRESS_ZOOM = 15.0

        /** As far in as the map will go, whatever the source can supply. */
        private const val MAP_MAX_ZOOM = 21.0

        private const val MENU_SOURCES = 1
        private const val MENU_CACHE = 900

        /** How long the map has to sit still before the pins are rebuilt. */
        private const val SETTLE_MS = 140L

        /** How long typing has to stop before anything is looked up. */
        private const val SUGGEST_MS = 300L

        private const val CRAG_HITS = 6
        private const val PLACE_HITS = 3

        /** Precision is asked for once, then left alone. */
        private const val KEY_ASKED_PRECISE = "asked_precise"
    }
}
