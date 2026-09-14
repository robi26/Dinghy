# Dinghy

A Syncthing client for Android with a **browsable index and on-demand downloads**:
the phone holds the complete file tree without holding the file data, and you pull
individual files when you want them.

The same model exists on iOS, built on the engine this app also uses. No
maintained Android app offers it — Syncthing-Fork and BasicSync run the
normal daemon (everything you subscribe to lands on disk), and Syncthing Lite, which
did work this way, has been unmaintained since 2019.

## How it works

The sync engine is **SushitrainCore**, the Go engine from
[pixelspark/sushitrain](https://github.com/pixelspark/sushitrain), used unmodified. It is
platform-neutral Go (no cgo, no build tags, pure-Go SQLite), so `gomobile` binds it to
Android exactly as the upstream project binds it to iOS. Selective sync is `.stignore` rewriting;
on-demand reads are served by a localhost HTTP server backed by a block puller that
fetches from peers on demand, which is what makes HTTP Range requests over
not-yet-downloaded files work.

Dinghy supplies the Android half: a Compose UI and a `DocumentsProvider` so synced
folders appear in the system file picker, with files materialized on first read.

```
Kotlin/Compose UI ─┐
DocumentsProvider ─┼─► SyncEngine ──► dinghy-core.aar (gomobile)
Foreground service ┘                      │
                              ┌───────────┴───────────┐
                         Syncthing node        StreamingServer
                      (index + selective       localhost HTTP,
                       .stignore pulling)       Range requests
```

## Building

Everything installs project-locally under `toolchains/`; nothing is installed
system-wide and no `sudo` is required.

```bash
git clone --recursive <this repo>
./gradlew :app:assembleDebug            # all ABIs
./gradlew :app:assembleDebug -Pabi=x86_64   # one ABI, much faster
```

Requirements: a JDK, plus the Go toolchain, Android SDK and NDK. The Gradle build
drives `gomobile` itself (`gomobileTools` → `gomobileBind` → `preBuild`).

### Build notes

- The sushitrain module's vanity import path (`t-shaped.nl/sushitrain/v2`) does not
  serve `go-get` metadata, so it cannot be fetched by path. It is pinned as a git
  submodule under `external/sushitrain` and wired up with a `replace` directive.
- `gomobile` locates `gobind` only on `$PATH`, so both are built into `toolchains/bin`.
- The link step needs `-ldflags=-checklinkname=0`: Syncthing reaches Android's network
  interfaces via `github.com/wlynxg/anet`, which `//go:linkname`s into `net.zoneCache`.
  Go ≥1.23 rejects that unless the check is disabled, and anet v0.0.5 (latest) still
  needs it.
- `splits.abi` is silently a no-op in AGP 9.4 — no warning, and `output-metadata.json`
  still reports a single `SINGLE` element. Per-ABI APKs are produced by building once
  per ABI with `-Pabi=<abi>`, which also narrows the gomobile bind and so cuts build
  time. An all-ABI APK is ~83 MiB; a single-ABI debug APK is ~36 MiB, ~27 MiB release.
- R8 needs a keep rule for classes implementing the binding's callback interfaces:
  Go calls them through JNI, so R8 sees no reference. See `app/proguard-rules.pro`.
- Syncthing validates the version string it is linked with and calls `log.Fatalf`
  on a mismatch, so a bad value does not report a wrong version — the app exits on
  launch with no crash and no Java stack trace. `git describe --tags` degrades to a
  bare commit hash whenever no tag is reachable, which is what a shallow submodule
  clone gives you, so CI checks out with `fetch-depth: 0` and the build falls back
  to `unknown-dev` (the one value Syncthing exempts) for anything that fails its
  regexp.

## Installing

APKs are published on the [releases page](../../releases), one per ABI —
`arm64-v8a` for essentially every current phone, `x86_64` for emulators. There
is no universal APK: the compiled Go engine is ~25 MiB per ABI. 32-bit ARM
(`armeabi-v7a`) was dropped after 0.1.1; those devices predate the arm64
requirement Android has placed on new hardware since 2021.

To get updates automatically, add the repository to
[Obtainium](https://github.com/ImranR98/Obtainium). Each release also carries a
`latest-release.json` listing every APK with its SHA-256, and a `SHA256SUMS`
file.

### Verifying a download

```bash
sha256sum -c SHA256SUMS --ignore-missing
apksigner verify --print-certs dinghy-<version>-<abi>.apk
```

The signing certificate's SHA-256 digest should match the one published with the
first release. A different certificate means a different build — do not install
it over an existing one, and Android will refuse anyway.

## Releasing

Releases are built by CI on a `v*` tag: each ABI is built and signed separately,
the signature is verified in the job, and the APKs are attached to the GitHub
release along with the Obtainium manifest and checksums. The tag names the
release — CI exports `VERSION_NAME` from it (minus the `v`), which sets the
`versionName` the app reports, the APK filenames and the version in the
Obtainium manifest, so those three cannot drift apart. Running the workflow
manually (`workflow_dispatch`) builds and verifies the signed APKs but publishes
nothing, since there is no tag to publish to.

Signing credentials come from repository secrets:
`DINGHY_KEYSTORE_BASE64`, `DINGHY_KEYSTORE_PASSWORD`, `DINGHY_KEY_ALIAS` and
`DINGHY_KEY_PASSWORD`. Locally, put the same values in `keystore.properties`
(gitignored); without it a release build still runs, just unsigned, which is
enough to check that R8 is happy.

```bash
./gradlew :app:dist -Pabi=arm64-v8a                    # signed APK into dist/
./gradlew :app:dist -Pabi=arm64-v8a -PversionName=0.2.0  # as CI builds a tag
```

**Version codes.** `splits.abi` does nothing on AGP 9, so per-ABI APKs come from
separate builds and each needs its own increasing version code. The code is
`baseVersionCode * 10 + ordinal`, with ordinals fixed per ABI (arm64-v8a 3,
x86_64 4) — so `baseVersionCode = 5` publishes as 53 and 54. Retired ABIs keep
their ordinal rather than freeing it: armeabi-v7a held 1 and shipped as 11
and 21, so 1 can never mean anything else.
The ordinals must never be reordered once released. Unlike the version name,
`baseVersionCode` is not derived from the tag: bump it in `app/build.gradle.kts`
before tagging, or Android and Obtainium will not see the release as an update.

**Reproducible builds** are not claimed yet. The Go toolchain and gomobile make
bit-identical output harder than for a pure-Kotlin app, and that has not been
verified, so F-Droid's build pipeline is out of scope for now; the IzzyOnDroid
repository accepts upstream-signed APKs and is the more realistic next step.

## Licensing

Dinghy is **GPL-3.0**. SushitrainCore is **MPL-2.0** and stays under its own license;
its headers carry the standard MPL notice, not the "Incompatible With Secondary
Licenses" one, so the combination is permitted.

The upstream project's own app name and logo are **not** covered by that license —
they are reserved by its author. Dinghy is an independent app and uses neither.

## Credits

- [pixelspark/sushitrain](https://github.com/pixelspark/sushitrain) by Tommy van der
  Vorst — SushitrainCore, the sync engine, and the interaction model it pioneered.
- [BasicSync](https://github.com/chenxiaolong/BasicSync) by chenxiaolong — the
  reference for running Syncthing via gomobile on modern Android.
- [Syncthing](https://syncthing.net/).
