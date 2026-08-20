package dr.ukccrags

import android.content.Context
import java.io.File
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * What the map can be drawn from.
 *
 * OpenStreetMap's own tiles, or aerial imagery. All of it is online: nobody
 * gives away a downloadable aerial basemap, and OSM's terms forbid pulling
 * their tiles down in bulk. What makes the map work at a crag is the cache —
 * tiles already drawn are kept for a year (see [App]), so anywhere looked at
 * beforehand still draws with no signal.
 *
 * Both sources are keyless, so there is nothing to configure and no secret to
 * keep out of the repository. Esri is the sharp one and what the map opens on;
 * Sentinel-2 is coarse but the only layer here under a licence that plainly
 * permits this use, which makes it the fallback if Esri ever stops answering.
 */
object MapSources {

    const val OSM = "osm"
    const val SENTINEL = "sentinel"
    const val ESRI = "esri"

    private const val KEY_SOURCE = "source"

    /** Imagery is somebody else's bandwidth: same courtesy as the OSM tiles. */
    /** Deepest zoom Esri holds real imagery for over open country. */
    private const val ESRI_DEEPEST = 19

    private val gentle = TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("maps", Context.MODE_PRIVATE)

    fun chosen(context: Context): String =
        prefs(context).getString(KEY_SOURCE, OSM) ?: OSM

    fun choose(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SOURCE, id).apply()
    }

    /** In the order they are offered. */
    fun available(): List<String> = listOf(OSM, ESRI, SENTINEL)

    /**
     * Megabytes of tiles already cached. A fair answer to "will this crag draw
     * with no signal", since nothing is ever fetched ahead of being looked at.
     */
    fun cachedMegabytes(context: Context): Long {
        val cache = File(context.filesDir, "osm/tiles")
        if (!cache.exists()) return 0

        return cache.walkBottomUp().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }

    fun label(context: Context, id: String): String = when (id) {
        ESRI -> context.getString(R.string.map_esri)
        SENTINEL -> context.getString(R.string.map_sentinel)
        else -> context.getString(R.string.map_online)
    }

    /** Shown on the map itself: every one of these requires crediting. */
    fun attribution(context: Context, id: String): String = context.getString(
        when (id) {
            ESRI -> R.string.credit_esri
            SENTINEL -> R.string.credit_sentinel
            else -> R.string.credit_osm
        }
    )

    fun tileSource(id: String): OnlineTileSourceBase = when (id) {
        ESRI -> esri()
        SENTINEL -> sentinel()
        else -> TileSourceFactory.MAPNIK
    }

    /**
     * Sentinel-2 cloudless, from EOX. Free for non-commercial use with the
     * credit below, and the only aerial layer here that needs no account at
     * all. It is 10m to a pixel, so it shows the shape of a hillside and the
     * line of a wall, and stops well short of showing a boulder — hence the
     * zoom ceiling rather than letting it blur.
     */
    private fun sentinel(): OnlineTileSourceBase = object : XYTileSource(
        "Sentinel2",
        0,
        14,
        256,
        ".jpg",
        arrayOf("https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/"),
        "Sentinel-2 cloudless by EOX",
        gentle,
    ) {
        // WMTS asks for row before column, the other way round from the XYZ
        // pattern osmdroid builds by default.
        override fun getTileURLString(index: Long): String = baseUrl +
            MapTileIndex.getZoom(index) + "/" +
            MapTileIndex.getY(index) + "/" +
            MapTileIndex.getX(index) + mImageFilenameEnding
    }

    /**
     * Esri World Imagery. The sharpest of the lot, roughly 30cm a pixel over
     * Britain, and it answers without an account.
     *
     * Asked for a zoom deeper than they hold, Esri does not fail — they return a
     * grey tile reading "Map data not yet available", which is a perfectly good
     * image as far as osmdroid is concerned, so it gets drawn and cached like
     * any other. That is why the ceiling here is a real number rather than
     * optimism: past it, osmdroid enlarges the deepest genuine tile instead.
     * Over Dartmoor the imagery runs out after 19; cities hold more, but a crag
     * is never in a city.
     *
     * Used here on the owner's decision, for one person's private use and never
     * anything commercial. Worth knowing what that rests on: Esri license this
     * service for use with an ArcGIS account, restrict storing tiles beyond
     * transient use — which the year-long tile cache plainly does — and can
     * withdraw anonymous access whenever they choose. If it stops answering one
     * day, that is why, and the other sources still work.
     *
     * The credit below is the standard mosaic line. Esri's own clients pull
     * attribution live per view, since the imagery under any given crag may come
     * from Maxar, Airbus or a government survey.
     */
    private fun esri(): OnlineTileSourceBase = object : XYTileSource(
        "EsriWorldImagery",
        0,
        ESRI_DEEPEST,
        256,
        "",
        arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Esri, Maxar, Earthstar Geographics",
        gentle,
    ) {
        // Row before column, as ArcGIS serves it.
        override fun getTileURLString(index: Long): String = baseUrl +
            MapTileIndex.getZoom(index) + "/" +
            MapTileIndex.getY(index) + "/" +
            MapTileIndex.getX(index)
    }
}
