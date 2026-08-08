# UKC Crag Viewer

An Android app (`dr.ukccrags`) that imports UKClimbing crag data through an
in-app WebView and then reads it **offline, at the crag** — climbs, grades,
stars, descriptions, crag notes, buttress pins and topo photos with the route
lines drawn over them.

Written for personal use. It scrapes [UKClimbing](https://www.ukclimbing.com)
pages that the signed-in user can already see, at a deliberately gentle request
rate, so that the data is available with no phone signal. It is not affiliated
with or endorsed by UKClimbing, and it never writes to anyone's logbook without
being asked: logging an ascent opens UKC's own climb page and stops at the
button, leaving the final press to the user.

## What's here

`android/` is the whole of it — a single-module Gradle project. Nothing else is
tracked: the earlier one-off scrape scripts, the working notes and every data
export stay local.

## Building

There is **no Gradle wrapper** in this project. Use the cached 8.11.1
distribution:

```sh
cd android
G=$(ls -d ~/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle)
"$G" :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` works locally too. With no keystore present it falls back to
the debug signing key, so a local release build needs no secrets — see below
for how CI signs a real one.

Requires JDK 17 and the Android SDK (`local.properties` points at it and is not
tracked).

## Updating the app on a phone

The app is not on Google Play, so it updates itself from this repository's
GitHub releases. **Check for updates** in the crag list's overflow menu reads
`releases/latest`, compares the tag against the installed `versionName`, and if
there is a newer one downloads the attached APK and hands it to the package
installer. Android will ask, once, to allow this app to install apps.

## Releasing

Releases are built and signed by `.github/workflows/release.yml`, which triggers
on any `v*` tag and can also be run manually.

### Secrets to add first

`Settings → Secrets and variables → Actions → New repository secret`, on this
repository. All four are required; the workflow fails fast if the keystore is
missing.

| Secret | |
| --- | --- |
| `KEYSTORE_BASE64` | The release keystore, base64-encoded (see below) |
| `KEYSTORE_PASSWORD` | Password for the keystore itself |
| `KEY_ALIAS` | Alias of the signing key inside the keystore |
| `KEY_PASSWORD` | Password for that key |

To create a keystore and encode it — keep the `.jks` somewhere that survives
this laptop, and **never commit it**. `*.jks` and `*.keystore` are gitignored
for that reason. Android will not install an update signed by a different key,
so losing the keystore means no future build can ever update an installed copy:

```sh
keytool -genkeypair -v -keystore release.jks -alias ukc \
  -keyalg RSA -keysize 4096 -validity 10000

base64 -i release.jks | pbcopy   # paste as KEYSTORE_BASE64
```

### Cutting a release

The tag is the version. CI stamps `versionName` from it, so the tag and the
build always agree — which is what stops the in-app updater offering a build
the user already has.

```sh
git tag v1.1
git push origin v1.1
```

The workflow builds `:app:assembleRelease`, signs it from the secrets, and
attaches `ukc-crag-viewer-1.1.apk` to a GitHub release for the tag. The
updater picks up the first `.apk` asset on the latest release, so that is the
whole handshake. Running the workflow manually instead builds and signs the
same way but uploads the APK as a workflow artifact, leaving releases alone.

## Scraped data is not in this repository

Earlier runs produced `*.xlsx` exports and JSON dumps of UKClimbing's content.
That is their data, gathered for one person's use, and republishing it is not
ours to do, so `.gitignore` excludes all of it.

## Licence

MIT — see [LICENSE](LICENSE). This covers the app's own source. It says nothing
about the climbing data, which remains UKClimbing's.
