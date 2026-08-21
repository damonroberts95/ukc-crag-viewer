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
    box. `SearchActivity` shows one ticklist; `ListsActivity` lists them;
    `MapActivity` is the osmdroid map.
  - `AutoSync` — weekly logbook read in an unattached WebView, run on opening
    the app only.
  - `CragDb` — the library in SQLite (crags, buttresses, climbs). Lists, maps
    and search read columns; only opening a crag parses one. The JSON files
    under `files/crags/` remain the scraped record and seed the tables.
  - `ImportQueue` / `QueueDrain` — a search queues crag URLs to
    `files/queue.json`; batches of 40 are read in an unattached WebView while
    the app is open, resumable across restarts. Refresh-all uses the same queue.
  - `MapSources` — OSM, Esri or Sentinel-2 tiles, all online and all keyless.
    OSM forbids bulk tile download, so there is no "save this area"; tiles
    already seen are kept a year in a 600MB cache.
  - `RotateGesture` — two-finger rotation, gated so zoom always wins.
  - `Pins.kt` — pin colour by dominant climb type, shared by map and legend.
  - `Data.kt` — `Climb`/`Buttress`/`Topo`/`Crag` and `CragStore`. One JSON file
    per crag under `files/crags/`; ticklists in `files/ticklists.json`; ticks,
    attempts and wishlist in preferences **keyed by climb URL** so they survive
    a re-import.
  - `TopoView` — draws lines over the photo, pinch zoom, grade labels.
  - `TopoCache` — downloads topo pixels in Kotlin on a 4-thread pool with the
    session cookies. Must not block: all page script runs on one thread.
  - `Session` — whether UKC knows who we are, learned from rendered pages.
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
