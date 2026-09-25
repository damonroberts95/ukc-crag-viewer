package dr.ukccrags

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Filter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivityMapBinding
import dr.ukccrags.databinding.ItemLegendBinding
import dr.ukccrags.databinding.SheetPinBinding
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
 *
 * Every read of the library happens off the main thread. At four thousand
 * crags the card list alone is a noticeable pause, and a box query under a
 * moving map is a dropped frame each time; each read carries a generation
 * number so an answer that arrives after the map has moved on is dropped.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding

    private var crags: List<CragCard> = emptyList()
    private var single: Crag? = null

    /** False until the library, or the one crag, has been read. */
    private var loaded = false

    private lateinit var overlay: PinOverlay
    private lateinit var rotate: RotateGesture

    /** Crag and buttress pins, and every crag's pin for the library view. */
    private var marks: List<Pin> = emptyList()
    private var cragPins: List<Pin> = emptyList()

    /**
     * Parking shares the crags' layer — so one hit test picks whichever is
     * nearest — but the overlay draws it beneath them and never groups it.
     */
    private var parking: List<Pin> = emptyList()
    private var showParking = true

    /**
     * The boxes, margin included, that the pins on screen were asked for. A
     * rebuild is due once the view leaves one — including by zooming out,
     * which moves no centre but uncovers every edge.
     */
    private var marksAskedFor: Area? = null
    private var parkingAskedFor: Area? = null
    private var marksGeneration = 0
    private var parkingGeneration = 0

    private var locator: MyLocationNewOverlay? = null

    /** What to do with the first fix, while one is being waited for. */
    private var waitingForFix: ((GeoPoint) -> Unit)? = null

    /** The walking line currently drawn: a dark casing under a bright core. */
    private var walkLine: List<Polyline> = emptyList()
    private var walkPoints: List<GeoPoint> = emptyList()
    private var walkOnPaths = false

    /** True while the map is drawn buttress by buttress rather than crag by crag. */
    private var detailed = false

    /** The opening view is framed once; later rebuilds must not move the map. */
    private var framed = false

    /** The current suggestions, in the order the dropdown lists them. */
    private var found: List<Pair<String, GeoPoint>> = emptyList()
    private lateinit var suggestions: Suggestions

    private var pendingQuery = ""
    private val suggestWhenStill = Runnable { suggest(pendingQuery) }

    /** One colour per climbing type, worked out once rather than per pin. */
    private val typeColours = HashMap<String, Int>()

    /** What the legend last showed, so an unchanged one is not rebuilt. */
    private var legendShown: List<String> = emptyList()

    private val settle = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebuildWhenStill = Runnable { rebuildNow() }
    private val snapWhenStill = Runnable { snapZoom() }

    /** One hide for the note, so a new message is not cut short by an old timer. */
    private val hideNote = Runnable { binding.note.visibility = View.GONE }

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            startLocating()
            goToMe()
        } else {
            note(getString(R.string.map_need_location))
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

        val wantedId = intent.getStringExtra(EXTRA_CRAG_ID)
        val wantedArea = intent.getStringExtra(EXTRA_AREA)

        // The bar holds either a crag's name or the search box, not both: one
        // crag's buttresses are not something you search a library for.
        if (wantedId != null || wantedArea != null) {
            supportActionBar?.title = wantedArea
            binding.searchBox.visibility = View.GONE
        } else {
            supportActionBar?.title = null
            binding.searchBox.visibility = View.VISIBLE
        }

        applySource(MapSources.chosen(this))
        binding.map.setMultiTouchControls(true)

        // Left alone, a pinch settles on a fractional zoom where every tile is
        // drawn scaled — soft at any distance. osmdroid's own rounding fixes
        // the softness by jumping to the nearest whole level the instant the
        // fingers lift, which is sharp and horrible. Instead the snap is
        // animated once the gesture has actually stopped: see snapZoom().
        binding.map.setZoomRounding(false)
        binding.map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )

        overlay = PinOverlay(
            onPin = { showSheet(it) },
            onCluster = { centre, group -> openCluster(centre, group) },
            glyphColour = ContextCompat.getColor(this, R.color.pin_glyph),
        )
        showParking = Settings.showParking(this)
        binding.map.overlays.add(overlay)

        // Two fingers turn the map. Stood under a crag, matching the map to
        // what you are looking at beats knowing where north is. Zoom takes
        // precedence: see RotateGesture.
        rotate = RotateGesture(binding.map)
        binding.map.overlays.add(rotate)

        // A turned map needs a way back, and a permanent button for it would be
        // clutter, so the chip appears only once the map is off north.
        binding.map.overlays.add(NorthWatcher())

        binding.north.setOnClickListener {
            binding.map.mapOrientation = 0f
            binding.map.invalidate()
        }

        // No configChanges, so a rotation or a dark-mode switch rebuilds this
        // screen; without this it jumped back to the fitted opening view.
        savedInstanceState?.let { restore(it) }

        startLocating()
        binding.here.setOnClickListener { goToMe() }
        setUpSearch()
        binding.clearWalk.setOnClickListener { clearWalk() }

        // Zooming in far enough swaps crags for their buttresses, so the map
        // answers "which lump of rock" once it can show them apart.
        binding.map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                if (detailed || (showParking && single == null)) rebuild()
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                rebuild()

                settle.removeCallbacks(snapWhenStill)
                settle.postDelayed(snapWhenStill, SNAP_WAIT_MS)
                return false
            }
        })

        load(wantedId, wantedArea)
    }

    /**
     * Reads what the map shows. One crag is read whole — its buttresses and
     * climbs are the screen — and then the library is not needed at all; the
     * library map only ever needs cards and pins. The id is asked for first,
     * since two crags can share a name.
     */
    private fun load(wantedId: String?, wantedArea: String?) {
        val buttressColour = ContextCompat.getColor(this, R.color.pin_buttress)

        Thread {
            val crag = wantedId?.let { CragStore.byId(this, it) }
                ?: wantedArea?.let { CragStore.byArea(this, it) }
            val cards = if (crag == null) CragStore.cards(this).filter { it.hasPin } else emptyList()

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread

                single = crag
                crags = cards
                cragPins = cards.map { cragPin(it) }
                loaded = true

                if (crag != null) {
                    supportActionBar?.title = crag.area
                    marks = buttressPins(crag, buttressColour)
                    publish()
                    frame(marks)
                    buildParking(force = true)
                } else {
                    // The library — also when the one crag asked for has since
                    // gone, which leaves the bar to the search box after all.
                    supportActionBar?.title = null
                    binding.searchBox.visibility = View.VISIBLE
                    buildPins()

                    // Once laid out, so the view has a box to ask for — and a
                    // view restored zoomed in gets its buttresses and parking.
                    binding.map.post { rebuildNow() }
                }
            }
        }.start()
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
            .takeIf { pins -> pins.none { it.kind == PinKind.CRAG } }
            ?.map { it.crag }
            ?.distinct()
            ?.singleOrNull()
            ?.takeIf { it.isNotBlank() }

        val count = resources.getQuantityString(R.plurals.here_count, group.size, group.size)
        val title = if (shared == null) count else "$shared · $count"

        val labels = ordered.map { pin ->
            val kind = when (pin.kind) {
                PinKind.CRAG -> ""
                PinKind.BUTTRESS -> " · " + getString(R.string.buttresses_legend).lowercase()
                PinKind.PARKING -> " · " + getString(R.string.parking_legend).lowercase()
            }

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
                // Whole levels only: a fraction here is animated to, then
                // animated again by the snap.
                val zoom = Math.round(binding.map.zoomLevelDouble) + 2.0
                binding.map.controller.animateTo(centre, zoom, 400L)
            }
            .show()
    }

    /** Centres on one pin and opens it, since a shared position cannot be zoomed apart. */
    private fun goToPin(pin: Pin) {
        val target = GeoPoint(pin.latitude, pin.longitude)
        val zoom = kotlin.math.ceil(binding.map.zoomLevelDouble).coerceAtLeast(PIN_ZOOM)

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
     * only once the view has left the box the pins were asked for.
     */
    private fun rebuild() {
        // A pinch fires a zoom event per frame, and rebuilding the pin list on
        // each one is the jitter. Doing it once the fingers stop is invisible.
        settle.removeCallbacks(rebuildWhenStill)
        settle.postDelayed(rebuildWhenStill, SETTLE_MS)
    }

    private fun rebuildNow() {
        if (!loaded) return

        buildParking()

        val wanted = single == null && binding.map.zoomLevelDouble >= BUTTRESS_ZOOM

        if (wanted != detailed) {
            detailed = wanted
            buildPins()
        } else if (detailed && !covered(marksAskedFor)) {
            buildPins()
        }
    }

    /**
     * Watches the map's own orientation. Rotation is a gesture on the map, not
     * an event the map reports, so this rides along with the drawing instead.
     * The chip is changed after the frame rather than inside it: a visibility
     * change mid-draw asks for a layout pass while one is being drawn.
     */
    private inner class NorthWatcher : org.osmdroid.views.overlay.Overlay() {

        private var wasTurned = false

        override fun draw(canvas: android.graphics.Canvas, map: org.osmdroid.views.MapView, shadow: Boolean) {
            if (shadow) return

            val turned = map.mapOrientation != 0f
            if (turned == wasTurned) return

            wasTurned = turned
            binding.north.post {
                binding.north.visibility = if (turned) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Eases the map onto a whole zoom level once the fingers have stopped.
     *
     * Tiles are only drawn at their own size on a whole level, so staying on a
     * fraction leaves the map permanently soft — but jumping there the moment a
     * pinch ends feels like the map twitching out from under you. A short
     * animation does the same job and reads as the map settling.
     *
     * It settles about the point the pinch was centred on. About the screen's
     * centre, the last bit of zoom slid whatever was under the fingers away
     * from them.
     */
    private fun snapZoom() {
        val focus = rotate.takePinchFocus()
        val zoom = binding.map.zoomLevelDouble
        val whole = Math.round(zoom).toDouble()

        // Already as good as level: leave it alone rather than animate nothing.
        if (kotlin.math.abs(zoom - whole) < 0.04) return

        if (focus != null) {
            binding.map.controller.zoomToFixing(whole, focus.x.toInt(), focus.y.toInt(), SNAP_MS)
        } else {
            binding.map.controller.zoomTo(whole, SNAP_MS)
        }
    }

    /** True while the whole view sits inside [box]. */
    private fun covered(box: Area?): Boolean {
        if (box == null) return false
        val view = binding.map.boundingBox

        return view.latNorth <= box.north && view.latSouth >= box.south &&
            view.lonEast <= box.east && view.lonWest >= box.west
    }

    /**
     * The view grown by half itself on every side, so an ordinary pan stays
     * inside what was asked for and costs no query.
     */
    private fun askingBox(): Area {
        val view = binding.map.boundingBox
        val latMargin = ((view.latNorth - view.latSouth) / 2).coerceAtLeast(MIN_MARGIN)
        val lonMargin = ((view.lonEast - view.lonWest) / 2).coerceAtLeast(MIN_MARGIN)

        return Area(
            view.latNorth + latMargin,
            view.lonEast + lonMargin,
            view.latSouth - latMargin,
            view.lonWest - lonMargin,
        )
    }

    /**
     * A box of degrees. Not osmdroid's BoundingBox, which can refuse one that
     * overhangs a pole or the date line — and a margin round the view does.
     */
    private class Area(val north: Double, val east: Double, val south: Double, val west: Double) {
        fun holds(latitude: Double, longitude: Double): Boolean =
            latitude in south..north && longitude in west..east
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
        suggestions = Suggestions(this)
        binding.search.setAdapter(suggestions)

        binding.search.setOnItemClickListener { parent, _, position, _ ->
            // By what was tapped, not by position in a list that may not match.
            val picked = parent.getItemAtPosition(position) as? String
            found.firstOrNull { it.first == picked }?.let { (_, where) ->
                binding.map.controller.animateTo(where, SEARCH_ZOOM, 700L)

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

    /**
     * The dropdown's list, shown exactly as given. The stock adapter filters
     * again by prefix, which hid crags matched mid-name and shifted positions
     * away from the list they were chosen from.
     */
    private class Suggestions(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {

        private val everything = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
                count = this@Suggestions.count
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
                notifyDataSetChanged()
        }

        override fun getFilter(): Filter = everything
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
        if (!android.location.Geocoder.isPresent()) {
            if (hits.isEmpty()) note(getString(R.string.map_search_nothing, query))
            return
        }

        Thread {
            val answer = runCatching {
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
            }
            val places = answer.getOrDefault(emptyList())

            runOnUiThread {
                if (isFinishing || binding.search.text?.toString()?.trim() != query) {
                    return@runOnUiThread
                }

                show(hits + places)

                // The geocoder throws rather than answering when there is no
                // signal, which is worth saying: "nothing" would be a lie.
                if (hits.isEmpty() && places.isEmpty()) {
                    note(
                        getString(R.string.map_search_nothing, query) +
                            if (answer.isFailure) "\n" + getString(R.string.map_search_offline) else ""
                    )
                }
            }
        }.start()
    }

    private fun show(hits: List<Pair<String, GeoPoint>>) {
        found = hits

        suggestions.clear()
        suggestions.addAll(hits.map { it.first })
        suggestions.notifyDataSetChanged()

        if (hits.isNotEmpty() && binding.search.hasFocus()) binding.search.showDropDown()
    }

    /** The reader's own position, when the permission is already granted. */
    private fun startLocating() {
        // Once only: the grant callback comes back here, and a second overlay
        // meant a second GPS listener and two dots.
        if (locator != null || !Nearby.granted(this)) return

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
        whenFixed { fix ->
            // A blurred fix should not be shown at street zoom: it would look
            // far more certain than it is. Whole levels, so the snap has
            // nothing left to do.
            val precise = Nearby.precise(this)

            binding.map.controller.animateTo(fix, if (precise) FIX_ZOOM else 13.0, 500L)
            if (!precise) note(getString(R.string.approximate_fix))
        }
    }

    /**
     * The reader's position now: the live overlay's, or a last known fix from
     * the last couple of minutes. An older one can be a valley away.
     */
    private fun currentFix(): GeoPoint? =
        locator?.myLocation ?: Nearby.recent(this)?.let { GeoPoint(it.latitude, it.longitude) }

    /**
     * Runs [action] with a fix, waiting for the first one if there is none
     * yet. Just after a grant there never is, and "no fix yet, try again" was
     * the answer to the reader doing exactly what was asked. Only the latest
     * request waits: tapping twice does not centre twice.
     */
    private fun whenFixed(action: (GeoPoint) -> Unit) {
        currentFix()?.let {
            action(it)
            return
        }

        val live = locator
        if (live == null) {
            note(getString(R.string.no_location))
            return
        }

        val alreadyWaiting = waitingForFix != null
        waitingForFix = action
        note(getString(R.string.map_locating))
        if (alreadyWaiting) return

        live.runOnFirstFix {
            runOnUiThread {
                val next = waitingForFix ?: return@runOnUiThread
                waitingForFix = null
                if (isDestroyed) return@runOnUiThread

                val fix = currentFix()
                if (fix != null) next(fix) else note(getString(R.string.no_location))
            }
        }
    }

    private fun prefs() = getSharedPreferences("location", MODE_PRIVATE)

    private fun colourFor(type: String): Int = typeColours.getOrPut(type) { pinColour(type) }

    private fun cragPin(card: CragCard) = Pin(
        label = card.area,
        latitude = card.latitude!!,
        longitude = card.longitude!!,
        colour = colourFor(card.dominantType),
        crag = card.area,
        glyph = pinGlyph(card.dominantType),
        payload = card,
    )

    /** The library's own pins: every crag, or the buttresses in view. */
    private fun buildPins() {
        if (single != null) return

        val generation = ++marksGeneration

        if (!detailed) {
            marksAskedFor = null
            marks = cragPins
            publish()

            if (marks.isEmpty()) note(getString(R.string.map_nothing)) else frame(marks)
            return
        }

        val box = askingBox()
        marksAskedFor = box

        val colour = ContextCompat.getColor(this, R.color.pin_buttress)
        val library = cragPins

        Thread {
            val pins = onScreenButtressPins(box, colour) +
                // A crag with no buttress rows at all has nothing in the
                // buttress index, and vanished on zooming in. Its own pin
                // stands in.
                library.filter { pin ->
                    (pin.payload as CragCard).buttressCount == 0 &&
                        box.holds(pin.latitude, pin.longitude)
                }

            runOnUiThread {
                if (generation != marksGeneration || isDestroyed) return@runOnUiThread
                marks = pins
                publish()
            }
        }.start()
    }

    /** Fits the opening view to the pins, once. */
    private fun frame(pins: List<Pin>) {
        if (framed || pins.isEmpty()) return
        framed = true
        fitTo(pins)
    }

    /** Hands both layers to the overlay, parking first so it draws beneath. */
    private fun publish() {
        overlay.pins = if (parking.isEmpty()) marks else parking + marks
        buildLegend()
        binding.map.invalidate()
    }

    /**
     * UKC publishes a pin for only about half of all buttresses. The rest fall
     * back to the crag's own position, drawn faded and labelled as such, so
     * every buttress can still be reached from here.
     */
    private fun buttressPins(crag: Crag, colour: Int): List<Pin> {
        val fallbackNeeded = crag.buttresses.count { !it.hasPin }
        if (fallbackNeeded > 0 && crag.hasPin) {
            note(resources.getQuantityString(
                R.plurals.map_approximate, fallbackNeeded, fallbackNeeded,
            ))
        }

        val pins = crag.buttresses.mapNotNull { buttress ->
            val latitude = buttress.latitude ?: crag.latitude
            val longitude = buttress.longitude ?: crag.longitude
            if (latitude == null || longitude == null) return@mapNotNull null

            Pin(
                label = buttress.name.ifBlank { crag.area },
                latitude = latitude,
                longitude = longitude,
                colour = colour,
                kind = PinKind.BUTTRESS,
                crag = crag.area,
                approximate = !buttress.hasPin,
                payload = ButtressAt(crag, buttress),
            )
        }

        if (pins.isEmpty()) note(getString(R.string.map_nothing))
        return pins
    }

    /**
     * Buttresses of the crags in [box], the crag pin standing in where UKC
     * publishes no position for one. Blocking: asked of the index by bounding
     * box, off the main thread.
     */
    private fun onScreenButtressPins(box: Area, colour: Int): List<Pin> {
        val found = CragDb.pinsWithin(this, box.south, box.north, box.west, box.east)

        return found.mapNotNull { at ->
            val latitude = at.latitude ?: return@mapNotNull null
            val longitude = at.longitude ?: return@mapNotNull null

            Pin(
                label = at.name.ifBlank { at.cragArea },
                latitude = latitude,
                longitude = longitude,
                colour = colour,
                kind = PinKind.BUTTRESS,
                crag = at.cragArea,
                approximate = at.approximate,
                payload = at,
            )
        }
    }

    /**
     * Car parks, each its own square with no name: which crag one serves is on
     * tap, not written beside it. One crag's map always has its own; the
     * library map shows them only once zoomed in to where a car park is a
     * decision rather than noise, and asks the index for just the ones in view.
     * [then] runs once the answer is on the map.
     */
    private fun buildParking(force: Boolean = false, then: (() -> Unit)? = null) {
        if (!loaded) return

        val crag = single
        val zoomed = binding.map.zoomLevelDouble >= PARKING_ZOOM
        val wanted = showParking && (crag != null || zoomed)

        if (!wanted) {
            parkingAskedFor = null
            parkingGeneration++
            if (parking.isNotEmpty()) {
                parking = emptyList()
                publish()
            }
            then?.invoke()
            return
        }

        // One crag's parking is the whole of it, so it never needs asking again.
        if (!force && covered(parkingAskedFor)) return

        val box = if (crag == null) askingBox() else WORLD
        parkingAskedFor = box

        val generation = ++parkingGeneration
        val colour = ContextCompat.getColor(this, R.color.pin_parking)

        Thread {
            val spots = if (crag != null) {
                CragDb.parking(this, crag.id)
            } else {
                CragDb.parkingWithin(this, box.south, box.north, box.west, box.east)
            }

            val pins = spots.map { spot ->
                Pin(
                    label = spot.cragArea,
                    latitude = spot.latitude,
                    longitude = spot.longitude,
                    colour = colour,
                    kind = PinKind.PARKING,
                    crag = spot.cragArea,
                    payload = spot,
                )
            }

            runOnUiThread {
                if (generation != parkingGeneration || isDestroyed) return@runOnUiThread
                parking = pins
                publish()
                then?.invoke()
            }
        }.start()
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
     * Only what is actually drawn, since a fixed key would list Winter to a
     * reader whose library is all bouldering. Each row carries the pin's own
     * shape and letter, so the key reads without its colours as well.
     */
    private fun buildLegend() {
        val rows = mutableListOf<LegendRow>()

        if (parking.isNotEmpty()) {
            rows += LegendRow(
                getString(R.string.parking_legend),
                ContextCompat.getColor(this, R.color.pin_parking),
                "P", android.graphics.Color.WHITE, R.drawable.legend_square,
            )
        }

        if (marks.any { it.kind == PinKind.BUTTRESS }) {
            rows += LegendRow(
                getString(R.string.buttresses_legend),
                ContextCompat.getColor(this, R.color.pin_buttress),
                "", android.graphics.Color.WHITE, R.drawable.legend_diamond,
            )
        }

        val glyphColour = ContextCompat.getColor(this, R.color.pin_glyph)
        val types = marks
            .mapNotNull { (it.payload as? CragCard)?.dominantType }
            .distinct()
            .sorted()
            .distinctBy { colourFor(it) }

        for (type in types) {
            rows += LegendRow(
                type.ifBlank { getString(R.string.type_unknown) },
                colourFor(type), pinGlyph(type), glyphColour, R.drawable.legend_disc,
            )
        }

        val shown = rows.map { it.text }
        if (shown == legendShown) return
        legendShown = shown

        binding.legend.removeAllViews()
        for (row in rows) addLegend(row)
    }

    private class LegendRow(
        val text: String,
        val colour: Int,
        val glyph: String,
        val glyphColour: Int,
        val shape: Int,
    )

    private fun addLegend(entry: LegendRow) {
        val row = ItemLegendBinding.inflate(layoutInflater, binding.legend, false)

        row.name.text = entry.text
        row.dot.setBackgroundResource(entry.shape)
        row.dot.backgroundTintList = ColorStateList.valueOf(entry.colour)
        row.dot.text = entry.glyph
        row.dot.setTextColor(entry.glyphColour)

        binding.legend.addView(row.root)
    }

    private fun note(message: String) {
        binding.note.text = message
        binding.note.visibility = View.VISIBLE
        binding.note.removeCallbacks(hideNote)
        binding.note.postDelayed(hideNote, NOTE_MS)
    }

    private fun showSheet(pin: Pin) {
        val view = SheetPinBinding.inflate(layoutInflater)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(view.root)

        when (val what = pin.payload) {
            is CragCard -> fillCrag(view, what, pin, sheet)
            is ButtressAt -> fillButtress(view, what, pin, sheet)
            is ButtressPin -> fillPin(view, what, pin, sheet)
            is ParkingPin -> fillParking(view, what, sheet)
        }

        sheet.show()
    }

    /** Opens a crag by id, with its name for a CragActivity that predates ids. */
    private fun cragIntent(target: Class<*>, id: String, area: String): Intent =
        Intent(this, target)
            .putExtra(EXTRA_CRAG_ID, id)
            .putExtra(CragActivity.EXTRA_AREA, area)

    /**
     * A crag's parking, read off the main thread. The sheet's directions wait
     * for it: routing before it lands would skip the car park.
     */
    private fun withParking(cragId: String, use: (List<Parking>) -> Unit) {
        Thread {
            val spots = CragDb.parking(this, cragId).map { Parking(it.name, it.latitude, it.longitude) }
            runOnUiThread { if (!isDestroyed) use(spots) }
        }.start()
    }

    /**
     * Directions from a sheet, by the same rule everywhere: the crag's parking
     * when there is one and the reader has not turned that off, the pin
     * otherwise. A buttress on a hillside is no place to send a satnav.
     */
    private fun directionsButton(
        view: SheetPinBinding,
        sheet: BottomSheetDialog,
        area: String,
        latitude: Double,
        longitude: Double,
        parking: List<Parking>,
    ) {
        // The crag in hand names its car parks by their nearest buttress too.
        val buttresses = single?.takeIf { it.area == area }?.buttresses.orEmpty()
        view.directions.isEnabled = true
        // Same wording as the crag screen's button, from the same setting.
        view.directions.setText(
            when {
                Settings.asksBetween(this, true, parking.isNotEmpty()) -> R.string.directions
                Settings.directionsToParking(this) && parking.isNotEmpty() -> R.string.directions_to_parking
                else -> R.string.directions
            }
        )
        view.directions.setOnClickListener {
            sheet.dismiss()
            Maps.directionsTo(this, area, latitude, longitude, parking, buttresses = buttresses)
        }
        view.directions.setOnLongClickListener {
            sheet.dismiss()
            Maps.directionsTo(this, area, latitude, longitude, parking, choose = true, buttresses = buttresses)
            true
        }
    }

    private fun fillCrag(
        view: SheetPinBinding,
        crag: CragCard,
        pin: Pin,
        sheet: BottomSheetDialog,
    ) {
        val away = crag.metresFrom(Nearby.lastKnown(this))

        fun detail(ticked: Int?) = buildString {
            append(resources.getQuantityString(
                R.plurals.climbs, crag.climbCount, crag.climbCount,
            ))
            if (ticked != null) append(" · ").append(
                resources.getQuantityString(R.plurals.crag_ticked, crag.climbCount, ticked, crag.climbCount)
            )
            if (away != null) append(" · ").append(Units.distance(this@MapActivity, away))
        }

        view.name.text = crag.area
        view.detail.text = detail(null)

        // The tick count reads the crag's climb list from the index, so it
        // joins the line once that is done rather than holding the sheet up.
        Thread {
            val ticked = Ticks(this).countIn(this, crag.id)
            runOnUiThread { if (!isDestroyed) view.detail.text = detail(ticked) }
        }.start()

        view.open.text = getString(R.string.open_crag)
        view.open.setOnClickListener {
            sheet.dismiss()
            startActivity(cragIntent(CragActivity::class.java, crag.id, crag.area))
        }

        // Parking comes from the index, so the sheet can route to it without
        // reading the crag.
        view.directions.isEnabled = false
        withParking(crag.id) { parking ->
            directionsButton(view, sheet, crag.area, pin.latitude, pin.longitude, parking)
        }

        view.walk.setOnClickListener {
            sheet.dismiss()
            walkTo(crag.id, crag.latitude, crag.longitude, pin)
        }

        view.topos.visibility = if (crag.topoCount == 0) View.GONE else View.VISIBLE
        view.topos.text = resources.getQuantityString(R.plurals.topos_n, crag.topoCount, crag.topoCount)
        view.topos.setOnClickListener {
            sheet.dismiss()
            startActivity(
                Intent(this, TopoActivity::class.java)
                    .putExtra(EXTRA_CRAG_ID, crag.id)
                    .putExtra(TopoActivity.EXTRA_AREA, crag.area)
            )
        }
    }

    /**
     * A buttress the library map drew from the index. Its crag has not been
     * read, and does not need to be: the sheet says what it is, opens the crag
     * filtered to it, and can walk to it.
     */
    private fun fillPin(
        view: SheetPinBinding,
        at: ButtressPin,
        pin: Pin,
        sheet: BottomSheetDialog,
    ) {
        val home = crags.firstOrNull { it.id == at.cragId }

        view.name.text = at.name.ifBlank { at.cragArea }
        view.detail.text = buildString {
            append(at.cragArea).append(" · ")
            append(resources.getQuantityString(R.plurals.climbs, at.climbCount, at.climbCount))
            if (pin.approximate) append(" · ").append(getString(R.string.pin_approximate))
        }

        view.open.text = getString(R.string.show_these_climbs)
        view.open.setOnClickListener {
            sheet.dismiss()
            startActivity(
                cragIntent(CragActivity::class.java, at.cragId, at.cragArea)
                    .putExtra(CragActivity.EXTRA_FIND, at.name)
            )
        }

        view.directions.isEnabled = false
        withParking(at.cragId) { parking ->
            directionsButton(view, sheet, at.cragArea, pin.latitude, pin.longitude, parking)
        }

        view.walk.setOnClickListener {
            sheet.dismiss()
            walkTo(at.cragId, home?.latitude, home?.longitude, pin)
        }

        view.topos.visibility = View.GONE
    }

    /**
     * A car park: which crag it serves, a way into that crag, the drive there,
     * and the walk in from it — which is the walk anybody standing at a car
     * park wants, wherever the reader happens to be now.
     */
    private fun fillParking(
        view: SheetPinBinding,
        at: ParkingPin,
        sheet: BottomSheetDialog,
    ) {
        val away = Nearby.lastKnown(this)?.let {
            Walk.metresBetween(it.latitude, it.longitude, at.latitude, at.longitude).toFloat()
        }

        view.name.text = at.cragArea

        fun detail(label: String) = buildString {
            append(label)
            if (away != null) append(" · ").append(Units.distance(this@MapActivity, away))
        }

        val spot = Parking(at.name, at.latitude, at.longitude)
        view.detail.text = detail(ParkingNames.labels(this, at.cragArea, listOf(spot)).first())

        // Which of the crag's car parks this is needs the others, and its
        // buttresses: read off the main thread, then relabel.
        Thread {
            val crag = single?.takeIf { it.id == at.cragId } ?: CragStore.byId(this, at.cragId)
            val all = crag?.parking ?: CragDb.parking(this, at.cragId).map {
                Parking(it.name, it.latitude, it.longitude)
            }
            val label = ParkingNames.labelFor(this, at.cragArea, spot, all, crag?.buttresses.orEmpty())

            runOnUiThread {
                if (!isDestroyed && sheet.isShowing) view.detail.text = detail(label)
            }
        }.start()

        view.open.text = getString(R.string.open_crag)
        view.open.setOnClickListener {
            sheet.dismiss()
            startActivity(cragIntent(CragActivity::class.java, at.cragId, at.cragArea))
        }

        view.directions.setText(R.string.directions_to_parking)
        view.directions.setOnClickListener {
            sheet.dismiss()
            Maps.open(this, at.latitude, at.longitude, getString(R.string.parking_for, at.cragArea))
        }

        view.walk.setOnClickListener {
            sheet.dismiss()

            val home = single?.takeIf { it.id == at.cragId }
            val card = crags.firstOrNull { it.id == at.cragId }
            val toLat = home?.latitude ?: card?.latitude
            val toLon = home?.longitude ?: card?.longitude

            if (toLat == null || toLon == null) {
                note(getString(R.string.walk_no_crag_pin))
                return@setOnClickListener
            }

            note(getString(R.string.walk_working))
            startWalk(
                at.cragId, at.latitude, at.longitude, toLat, toLon,
                at.cragArea, getString(R.string.from_parking),
            )
        }

        view.topos.visibility = View.GONE
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
                cragIntent(CragActivity::class.java, crag.id, crag.area)
                    // Into the search box rather than a hidden filter, so it
                    // is visible, removable, and narrows the topos as well.
                    .putExtra(CragActivity.EXTRA_FIND, buttress.name)
            )
        }

        // The whole crag is in hand, parking and all.
        directionsButton(view, sheet, crag.area, pin.latitude, pin.longitude, crag.parking)

        view.walk.setOnClickListener {
            sheet.dismiss()
            walkTo(crag.id, crag.latitude, crag.longitude, pin)
        }

        view.topos.visibility = View.GONE
    }

    /**
     * Draws a walking line from the reader to a pin.
     *
     * A walk needs the crag's id to cache its paths and its pin to fall back
     * to, and nothing else about it — so it takes those rather than a crag.
     *
     * Too far to walk from where you are standing, the leg that matters is the
     * walk-in: from the crag's car park, the nearest to the pin when there are
     * several. That is the approach you want when planning from home. With no
     * parking known, a buttress is still worth routing to from its crag's own
     * pin; a crag is not, since that would be a line to itself.
     */
    private fun walkTo(cragId: String, cragLat: Double?, cragLon: Double?, pin: Pin) {
        whenFixed { fix ->
            val away = Walk.metresBetween(fix.latitude, fix.longitude, pin.latitude, pin.longitude)
            val distance = Units.distance(this, away.toFloat())

            if (away <= Walk.MAX_SPAN_METRES) {
                note(getString(R.string.walk_working))
                startWalk(cragId, fix.latitude, fix.longitude, pin.latitude, pin.longitude, pin.label, "")
                return@whenFixed
            }

            Thread {
                val spot = CragDb.parking(this, cragId).minByOrNull {
                    Walk.metresBetween(it.latitude, it.longitude, pin.latitude, pin.longitude)
                }

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread

                    val fromCragPin = cragLat != null && cragLon != null &&
                        Walk.metresBetween(cragLat, cragLon, pin.latitude, pin.longitude) > SAME_SPOT_METRES

                    when {
                        spot != null -> {
                            note(getString(R.string.walk_from_parking, distance))
                            startWalk(
                                cragId, spot.latitude, spot.longitude, pin.latitude, pin.longitude,
                                pin.label, getString(R.string.from_parking),
                            )
                        }
                        fromCragPin -> {
                            note(getString(R.string.walk_from_crag_no_parking, distance))
                            startWalk(
                                cragId, cragLat!!, cragLon!!, pin.latitude, pin.longitude,
                                pin.label, getString(R.string.from_crag_pin),
                            )
                        }
                        else -> note(getString(R.string.walk_far_no_parking, distance))
                    }
                }
            }.start()
        }
    }

    /**
     * The paths come from Overpass, asked for only here and cached per crag, so
     * a second buttress at the same crag costs nothing and a later visit works
     * with no signal. With no paths within reach the line is straight, and says
     * so rather than pretending.
     */
    private fun startWalk(
        cragId: String,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        label: String,
        from: String,
    ) {
        Thread {
            val route = Walk.route(this, cragId, fromLat, fromLon, toLat, toLon)

            runOnUiThread { if (!isDestroyed) drawWalk(route, label, from) }
        }.start()
    }

    private fun drawWalk(route: WalkRoute, label: String, from: String) {
        drawLine(route.points.map { GeoPoint(it.first, it.second) }, route.onPaths)

        val distance = Units.distance(this, route.metres.toFloat())

        // Far enough that no route was attempted: say that, rather than let it
        // read as "there are no paths here".
        note(
            when {
                route.tooFar -> getString(R.string.walk_too_far, distance)
                route.partial -> getString(R.string.walk_partly, distance, label)
                route.onPaths -> getString(R.string.walk_on_paths, distance, label)
                else -> getString(R.string.walk_straight, distance, label)
            } + from
        )
    }

    private fun drawLine(points: List<GeoPoint>, onPaths: Boolean) {
        walkLine.forEach { binding.map.overlays.remove(it) }

        walkPoints = points
        walkOnPaths = onPaths

        // Sized in dp: in pixels the line was a hair on a dense screen.
        val density = resources.displayMetrics.density

        // A map is already full of greens and greys, so the line gets an orange
        // core over a dark casing: readable over fields, woods, water or rock.
        val casing = Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.strokeWidth = 6.5f * density
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.color = ContextCompat.getColor(this@MapActivity, R.color.walk_casing)
            outlinePaint.alpha = 210
        }

        val core = Polyline(binding.map).apply {
            setPoints(points)
            outlinePaint.strokeWidth = 3.5f * density
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.color = ContextCompat.getColor(this@MapActivity, R.color.walk_line)

            // A guessed line should not look as certain as a followed path.
            if (!onPaths) outlinePaint.pathEffect =
                android.graphics.DashPathEffect(floatArrayOf(8f * density, 6.5f * density), 0f)
        }

        walkLine = listOf(casing, core)
        binding.clearWalk.visibility = View.VISIBLE
        binding.map.overlays.add(0, core)
        binding.map.overlays.add(0, casing)
        binding.map.invalidate()
    }

    private fun clearWalk() {
        walkLine.forEach { binding.map.overlays.remove(it) }
        walkLine = emptyList()
        walkPoints = emptyList()
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

        val provider = org.osmdroid.tileprovider.MapTileProviderBasic(this)

        // Cache and approximation before the network. osmdroid can fill a tile
        // it has not got by scaling the parent tile it has, which is a soft
        // version of the right picture — but only if it is asked before the
        // download rather than after it. That turns every zoom from a flash of
        // empty ground into a blurred version of what is already there,
        // sharpening as the real tiles land.
        provider.setOfflineFirst(true)

        // setTileProvider detaches the provider it replaces itself.
        binding.map.setTileProvider(provider)
        binding.map.setTileSource(MapSources.tileSource(id))

        // Out of signal and past what is cached, osmdroid draws a grey grid of
        // "no tile" squares. The pins, the walking line and the location dot
        // are the parts that actually navigate, so let them sit on a plain
        // ground instead of a chessboard. A new provider brings a new tiles
        // overlay with the chessboard back, so this follows every switch.
        binding.map.overlayManager.tilesOverlay.apply {
            loadingBackgroundColor = ContextCompat.getColor(this@MapActivity, R.color.map_empty)
            loadingLineColor = ContextCompat.getColor(this@MapActivity, R.color.map_empty_line)
        }

        // Every source runs out of data somewhere — 14 for Sentinel-2, 19 for
        // Esri — and past that osmdroid enlarges the deepest tile it has. Soft
        // pixels beat a wall you cannot zoom through when you are trying to see
        // which side of a wall a boulder sits on, so the map goes further in
        // than any of them can actually draw.
        binding.map.maxZoomLevel = MAP_MAX_ZOOM

        // All of these require crediting, and the credit belongs on the map —
        // as a way to the licence, not just a line of small print.
        binding.credit.text = MapSources.attribution(this, id)
        binding.credit.setOnClickListener { Maps.openUrl(this, MapSources.attributionUrl(id)) }
        binding.map.invalidate()

        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Built by hand rather than from XML: the sources are MapSources' list,
        // and the checked one is whatever was chosen last.
        val chosen = MapSources.chosen(this)

        for ((order, id) in MapSources.available().withIndex()) {
            menu.add(MENU_SOURCES, order, order, MapSources.label(this, id)).apply {
                isCheckable = true
                isChecked = id == chosen
            }
        }

        menu.setGroupCheckable(MENU_SOURCES, true, true)

        menu.add(0, MENU_PARKING, 50, R.string.show_parking).apply {
            isCheckable = true
            isChecked = showParking
        }

        menu.add(0, MENU_CACHE, 100, R.string.map_cache_size)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val sources = MapSources.available()

        if (item.groupId == MENU_SOURCES && item.itemId in sources.indices) {
            applySource(sources[item.itemId])
            return true
        }

        if (item.itemId == MENU_PARKING) {
            showParking = !showParking
            Settings.setShowParking(this, showParking)
            item.isChecked = showParking

            // Zoomed out, turning them on shows nothing yet; say why. One
            // crag's map says so only once its parking has been looked up.
            val tooFarOut = single == null && binding.map.zoomLevelDouble < PARKING_ZOOM
            if (showParking && tooFarOut) note(getString(R.string.parking_zoom_in))

            buildParking(force = true) {
                if (showParking && single != null && parking.isEmpty()) {
                    note(getString(R.string.parking_none))
                }
            }
            return true
        }

        if (item.itemId == MENU_CACHE) {
            showCache()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * What the tile cache means for going out with no signal. A size was the
     * old answer, and a size says nothing about whether the crag on screen
     * will draw — so the view in hand is checked zoom by zoom, alongside how
     * full the store is and what each map type holds.
     *
     * Zoomed in past the deepest level a source has, the map is enlarging
     * that level's tiles, so that level is the one checked.
     */
    private fun showCache() {
        val source = binding.map.tileProvider.tileSource
        val box = binding.map.boundingBox
        val deepest = source.maximumZoomLevel
        val actual = binding.map.zoomLevelDouble.toInt()
        val zoom = actual.coerceIn(source.minimumZoomLevel, deepest)
        val zooms = (zoom - 1).coerceAtLeast(source.minimumZoomLevel)..(zoom + 2).coerceAtMost(deepest)
        val sourceLabel = MapSources.label(this, MapSources.chosen(this))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_cache_title)
            .setMessage(R.string.map_cache_checking)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.map_cache_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .show()

        Thread {
            val megabytes = MapSources.cachedMegabytes(this)
            val capMegabytes = App.CACHE_MAX_BYTES / (1024 * 1024)
            val bySource = MapSources.savedTiles()
            val cover = MapSources.cover(source, box, zooms)

            val lines = mutableListOf<String>()

            lines += getString(
                R.string.map_cache_used, megabytes, capMegabytes,
                (megabytes * 100 / capMegabytes.coerceAtLeast(1)).toInt(),
            )
            lines += ""
            lines += getString(R.string.map_cache_by_type)
            for ((id, count) in bySource) {
                lines += getString(
                    R.string.map_cache_type_line, MapSources.label(this, id),
                    String.format(java.util.Locale.UK, "%,d", count),
                )
            }

            lines += ""
            lines += getString(R.string.map_cache_view, sourceLabel)
            if (cover.isEmpty()) {
                lines += getString(R.string.map_cache_view_wide)
            } else {
                for (level in cover) {
                    lines += getString(
                        if (level.zoom == zoom) R.string.map_cache_zoom_now else R.string.map_cache_zoom,
                        level.zoom, level.saved * 100 / level.total, level.saved, level.total,
                    )
                }
            }
            if (actual > deepest) lines += getString(R.string.map_cache_overzoom, deepest)

            lines += ""
            lines += getString(R.string.map_cache_how)

            runOnUiThread {
                if (!isDestroyed && dialog.isShowing) dialog.setMessage(lines.joinToString("\n"))
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val centre = binding.map.mapCenter
        outState.putDouble(STATE_LATITUDE, centre.latitude)
        outState.putDouble(STATE_LONGITUDE, centre.longitude)
        outState.putDouble(STATE_ZOOM, binding.map.zoomLevelDouble)
        outState.putFloat(STATE_ORIENTATION, binding.map.mapOrientation)

        if (walkPoints.isNotEmpty()) {
            val flat = DoubleArray(walkPoints.size * 2)
            walkPoints.forEachIndexed { i, point ->
                flat[i * 2] = point.latitude
                flat[i * 2 + 1] = point.longitude
            }
            outState.putDoubleArray(STATE_WALK, flat)
            outState.putBoolean(STATE_WALK_PATHS, walkOnPaths)
        }
    }

    /** Back where the reader was, which also means the opening fit is skipped. */
    private fun restore(state: Bundle) {
        if (!state.containsKey(STATE_ZOOM)) return

        framed = true
        binding.map.controller.setZoom(state.getDouble(STATE_ZOOM))
        binding.map.controller.setCenter(
            GeoPoint(state.getDouble(STATE_LATITUDE), state.getDouble(STATE_LONGITUDE))
        )
        binding.map.mapOrientation = state.getFloat(STATE_ORIENTATION)

        val flat = state.getDoubleArray(STATE_WALK) ?: return
        val points = (0 until flat.size / 2).map { GeoPoint(flat[it * 2], flat[it * 2 + 1]) }
        if (points.size >= 2) drawLine(points, state.getBoolean(STATE_WALK_PATHS))
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        settle.removeCallbacks(rebuildWhenStill)
        settle.removeCallbacks(snapWhenStill)
        binding.map.onPause()
        super.onPause()
    }

    companion object {
        /** Set to a crag's name to map that crag's buttresses instead. */
        const val EXTRA_AREA = "area"

        /** A crag's id, preferred over [EXTRA_AREA]: names are not unique. */
        const val EXTRA_CRAG_ID = "crag_id"

        /** Zoom at which buttresses are far enough apart to be worth drawing. */
        private const val BUTTRESS_ZOOM = 15.0

        /** As far in as the map will go, whatever the source can supply. */
        private const val MAP_MAX_ZOOM = 21.0

        /** Where a chosen pin, a found place and the reader's own fix are shown. */
        private const val PIN_ZOOM = 17.0
        private const val SEARCH_ZOOM = 15.0
        private const val FIX_ZOOM = 16.0

        private const val MENU_SOURCES = 1
        private const val MENU_CACHE = 900
        private const val MENU_PARKING = 901

        /**
         * Zoom at which the library map starts drawing car parks. At 12 a
         * whole valley's worth crowded the crags; by 14 a park sits near
         * enough to its crag to read as that crag's.
         */
        private const val PARKING_ZOOM = 14.0

        /** Least margin, in degrees, around a box asked of the index. */
        private const val MIN_MARGIN = 0.02

        /** Asked for once a crag's own parking is loaded: always covered. */
        private val WORLD = Area(90.0, 180.0, -90.0, -180.0)

        /** Closer than this to its crag's pin, a buttress is not worth a walk from it. */
        private const val SAME_SPOT_METRES = 50.0

        /** How long the map has to sit still before the pins are rebuilt. */
        private const val SETTLE_MS = 140L

        /** Long enough that a pinch is over, short enough not to be noticed. */
        private const val SNAP_WAIT_MS = 220L

        /** The ease onto a whole zoom level. Slow enough to read as movement. */
        private const val SNAP_MS = 260L

        /** How long typing has to stop before anything is looked up. */
        private const val SUGGEST_MS = 300L

        /** How long a note stays up. */
        private const val NOTE_MS = 6000L

        private const val CRAG_HITS = 6
        private const val PLACE_HITS = 3

        /** Precision is asked for once, then left alone. */
        private const val KEY_ASKED_PRECISE = "asked_precise"

        private const val STATE_LATITUDE = "latitude"
        private const val STATE_LONGITUDE = "longitude"
        private const val STATE_ZOOM = "zoom"
        private const val STATE_ORIENTATION = "orientation"
        private const val STATE_WALK = "walk"
        private const val STATE_WALK_PATHS = "walk_paths"
    }
}
