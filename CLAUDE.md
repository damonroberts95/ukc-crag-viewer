# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

UKC Crag Viewer — an Android app (`dr.ukccrags`) that imports UKClimbing crag
data through an in-app WebView and reads it offline at the crag. Personal use,
single Gradle module under `android/`. Nothing else in the tree is tracked: the
earlier one-off scrape scripts (`ukc_to_excel.py`, `harvest.js`,
`import_harvest.py`), every `*.xlsx`/JSON export and `release.jks` stay local
and gitignored.

Read `android/NOTES.md` before touching the import path. It records the UKC
page shapes, the endpoint table and the things that cost time to learn
(topo coordinate rotation, signed expiring photo URLs, comment-wrapped
`More...`, brace matching over the payload). `README.md` covers the release
and signing story.

## Build and run

There is **no Gradle wrapper**. Use the cached 8.11.1 distribution:

```sh
cd android
G=$(ls -d ~/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle)
"$G" :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, Android SDK via untracked `local.properties`. `assembleRelease` works
locally too — with no keystore present it falls back to the debug key.

There is no test suite and no lint gate. A clean `assembleDebug` is the only
automated check; anything user-visible needs a look on the phone.

## The phone

- Wireless adb (`<phone-ip>:5555`).
- **Check `adb shell dumpsys window | grep mCurrentFocus` before touching the
  device.** If the app is not focused, the phone is in use — leave it alone.
- Activities are not exported, so `am start` on them fails silently. Drive the
  UI by hand.

## Shape of the code

`android/app/src/main/`

- `assets/extract.js` — everything scraped, injected into the WebView. UKC
  ships climbs as JSON in an inline script, not in the HTML, so this parses the
  payload rather than the DOM.
- `java/dr/ukccrags/`
  - `BrowseActivity` — the WebView: import, sync, log flow, geolocation.
  - `CragListActivity` → `CragActivity` → `TopoActivity`. The crag list's
    search box is the whole library search — crag names and climb names in one
    box. `SearchActivity` shows one ticklist, the wishlist or the to-log list;
    `ListsActivity` lists them; `MapActivity` is the osmdroid map. Screens
    open crags by id (`crag_id`), never by name — names are not unique.
  - `ClimbDialog` — the one climb dialog, shared by the crag and topo screens.
  - `AutoSync` — weekly logbook read in an unattached WebView, run on opening
    the app only.
  - `CragDb` — the library in SQLite (crags, buttresses, climbs, parking, an
    FTS4 table of climb names), WAL on. Every table is derived: the JSON files
    under `files/crags/` are the record, a full crag is read from its file, and
    an upgrade rebuilds the tables from them. Opened off the main thread from
    `App.onCreate`, since that is where an upgrade runs.
  - `ImportQueue` / `QueueDrain` — a search queues crag URLs to
    `files/queue.json`; batches of 40 are read in a hidden 1px WebView that
    `App` moves into whichever screen is in front, so it keeps reading on any
    screen while the app is open. Crags are struck off one at a time as they
    land; a batch that fails entirely (no signal) changes nothing. Refresh-all
    uses the same queue.
  - `MapSources` — OSM, Esri or Sentinel-2 tiles, all online and all keyless.
    OSM forbids bulk tile download, so there is no "save this area"; tiles
    already seen are kept in a 600MB cache, refreshed monthly with signal;
    expired tiles still draw offline.
  - `RotateGesture` — two-finger rotation, gated so zoom always wins.
  - `Pins.kt` — pin colour by dominant climb type, shared by map and legend.
  - `Data.kt` — `Climb`/`Buttress`/`Topo`/`Crag` and `CragStore`. One JSON file
    per crag under `files/crags/`; ticklists in `files/ticklists.json`; ticks,
    attempts, wishlist and to-log in preferences **keyed by climb URL** so they
    survive a re-import, each behind one locked process-wide store.
  - `TopoView` — draws lines over the photo, pinch zoom, grade labels.
  - `PhotoFetch` / `PhotoCache` / `PhotosActivity` — crag and climb photos,
    saved per crag only when asked, viewed offline.
  - `SettingsActivity` / `Settings` / `Guide` — choices, cache clearing, the
    first-run guide.
  - `TopoCache` — downloads topo pixels in Kotlin on a 4-thread pool with the
    session cookies. Must not block: all page script runs on one thread.
  - `Session` — whether UKC knows who we are, learned from rendered pages.
  - `PageScript` — loads `extract.js` with a per-WebView token baked into its
    closure. Every bridge method that writes or fetches checks it, so a frame
    that is not our script cannot reach the library. Off-UKC navigations in
    `BrowseActivity` open in the real browser.
  - `Updates` — self-update from this repo's GitHub releases.
  - `Walk`, `Nearby`, `Maps`, `PinOverlay`, `Units`, `Insets` — support.

## Conventions

- Views and XML layouts with `viewBinding`, no Compose.
- No coroutines. Background work is a plain thread or `TopoCache`'s pool,
  hopping back with `runOnUiThread`. Keep to that.
- Dependencies are deliberately few (AndroidX, Material, osmdroid for keyless
  offline-caching tiles). Do not add one without asking.
- KDoc says **why**, not what — see `App.kt` and `Session.kt`. Match that.
- Strings live in `res/values/strings.xml`. Dark mode via `values-night/`.
- Version and signing come from the environment in `app/build.gradle.kts`; CI
  stamps `versionName` from the git tag. Do not hardcode either.

## Boundaries

- **Never write to anyone's logbook.** Logging an ascent opens UKC's own climb
  page, finds `#addToLogbookButton` and stops at it. The final press is the
  user's, always.
- Scrape at the existing gentle rate — 6 workers, 250ms between pages, global
  backoff doubling to 8s on a throttle. Do not raise it.
- Never commit scraped data, `*.jks`/`*.keystore`, or `local.properties`.
- Commits use `damonroberts95@users.noreply.github.com`. No other address
  appears in the tree or history; keep it that way.
- Releasing (`git tag`, `git push origin <tag>`) publishes an APK to the public
  repo. Only on an explicit ask.

## Known open ends

Listed under **Still open** in `android/NOTES.md`: UKC's "near me" button is
flaky after the geolocation fixes; ticklist sync costs one request per list;
the logbook CSV has no ids so a renamed climb will not tick; grade sort and
`climb_id` need a refresh on crags imported before those existed.
