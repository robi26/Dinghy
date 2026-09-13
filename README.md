# Sidecar

A Syncthing client for Android with a **browsable index and on-demand downloads**:
the phone holds the complete file tree without holding the file data, and you pull
individual files when you want them.

This is the model [Synctrain](https://github.com/pixelspark/sushitrain) provides on
iOS. No maintained Android app offers it — Syncthing-Fork and BasicSync run the
normal daemon (everything you subscribe to lands on disk), and Syncthing Lite, which
did work this way, has been unmaintained since 2019.

## How it works

The sync engine is **SushitrainCore** — Synctrain's Go engine, used unmodified. It is
platform-neutral Go (no cgo, no build tags, pure-Go SQLite), so `gomobile` binds it to
Android exactly as Synctrain binds it to iOS. Selective sync is `.stignore` rewriting;
on-demand reads are served by a localhost HTTP server backed by a block puller that
fetches from peers on demand, which is what makes HTTP Range requests over
not-yet-downloaded files work.

Sidecar supplies the Android half: a Compose UI and a `DocumentsProvider` so synced
folders appear in the system file picker, with files materialized on first read.

```
Kotlin/Compose UI ─┐
DocumentsProvider ─┼─► SyncEngine ──► sidecar-core.aar (gomobile)
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

## Licensing

Sidecar is **GPL-3.0**. SushitrainCore is **MPL-2.0** and stays under its own license;
its headers carry the standard MPL notice, not the "Incompatible With Secondary
Licenses" one, so the combination is permitted.

The Synctrain name and logo are **not** covered by that license — they are reserved by
its author. Sidecar is an independent app and uses neither.

## Credits

- [Synctrain / sushitrain](https://github.com/pixelspark/sushitrain) by Tommy van der
  Vorst — the sync engine and the interaction model.
- [BasicSync](https://github.com/chenxiaolong/BasicSync) by chenxiaolong — the
  reference for running Syncthing via gomobile on modern Android.
- [Syncthing](https://syncthing.net/).
