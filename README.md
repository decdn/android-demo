# deCDN Android demo

[![CI](https://img.shields.io/github/actions/workflow/status/decdn/android-demo/ci.yml?branch=main&label=CI&logo=github)](https://github.com/decdn/android-demo/actions/workflows/ci.yml?query=branch%3Amain)
[![decdn-client](https://img.shields.io/crates/v/decdn-client?logo=rust&label=decdn-client)](https://crates.io/crates/decdn-client)
[![License: MIT OR Apache-2.0](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue)](#license)

A one-screen Android app that downloads files from [deCDN](https://github.com/decdn/decdn)
on a phone. It runs the [deCDN client SDK](https://docs.rs/decdn-client) (`decdn-client`)
natively: it finds the nodes that hold a blob, streams it from all of them at once, verifies
every byte against its BLAKE3 hash as it arrives, and pays per megabyte in USDC from a payment
pool. A sponsor funds that pool, so the phone holds no USDC and no wallet.

Enter a content hash and tap **Download**. The first time, the app opens a one-step check in the
browser; come back to the app and the download starts. The verified file lands in
`Download/decdn/`. Tick **Whole bundle** and the hash names a bundle manifest instead: every file
it lists lands under a folder of its own.

On a Pixel 8a over home Wi-Fi, a 4.2 GiB video bundle downloads at about 8 MiB/s, and an
interrupted pull resumes from the bytes already on disk without paying for them twice.

## How it works

```text
┌──────────── Android app ────────────┐
│ MainActivity (Java)                 │      ┌────────────┐
│   hash, namespace ──────────────────┼─────▶│ onramp     │  up.decdn.org: a capability for
│        │ JNI             ▲ progress │      │            │  the app's key, after a browser check
│        ▼                 │          │      └────────────┘
│ libdecdn_android.so (Rust)          │      ┌────────────┐
│   decdn-client BundlePuller ────────┼─────▶│ deCDN nodes│  iroh QUIC, paid per MB
│   throwaway key + capability        │      └────────────┘
│        │                            │      ┌────────────┐
│        └────────────────────────────┼─────▶│ Arbitrum   │  pool + registry reads
└─────────────────────────────────────┘      │ Sepolia    │
                                             └────────────┘
```

- [`rust/src/lib.rs`](rust/src/lib.rs) is the whole native side. It drives
  `decdn_client::bundle::BundlePuller` with `Funding::Delegated`, so discovery, payment, resume
  and verification all come from the SDK. A single file is a one-entry manifest built in code.
  A `BundleObserver` feeds verified-byte counts to the UI.
- [`MainActivity.java`](app/src/main/java/dev/decdn/demo/MainActivity.java) is the screen. It
  gets an allowance through [`Onramp.java`](app/src/main/java/dev/decdn/demo/Onramp.java),
  calls `Native.pull` on a worker thread, polls `Native.progress` every 500 ms, and copies the
  finished files into `Download/decdn/` through `MediaStore`.
- `Native.init` hands the app's `Context` to two crates that read Android system state over
  JNI: iroh's DNS resolver (`ndk-context`) and `rustls-platform-verifier`, which checks TLS
  certificates against the Android trust store. The verifier's Android half comes from
  upstream's Maven archive as `org.rustls:rustls-platform-verifier:0.2.0`.

## Requirements

- macOS or Linux
- [Android Studio](https://developer.android.com/studio), with **NDK (Side by side)** and
  **Android SDK Platform-Tools** from SDK Manager > SDK Tools
- [Rust](https://rustup.rs/). `rust/rust-toolchain.toml` pins 1.99 and the
  `aarch64-linux-android` target, so rustup installs both on first build.
- [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk`
- A 64-bit ARM phone on Android 11 or newer, with
  [USB debugging](https://developer.android.com/studio/debug/dev-options) on

## Build and install

Plug in the phone, then:

```bash
./build.sh
```

That builds the native library with `cargo ndk`, builds the debug APK with Gradle, installs it,
and launches it. `./build.sh apk` builds without installing. The script finds the SDK under
`~/Library/Android/sdk` or `~/Android/Sdk` and the newest NDK inside it; set `ANDROID_HOME` or
`ANDROID_NDK_HOME` to override.

## The sponsored allowance

The app pays as a delegate of a [sponsord](https://github.com/decdn/sponsord) gateway, the same
way its `decdn.sh` installer does:

1. On first launch the app makes a random throwaway key and keeps it in its private storage. The
   key holds no funds.
2. On **Download** with no allowance, the app opens the onramp's gate page,
   `https://up.decdn.org/fund?client=<address>`, in the browser. Passing the check there has
   the sponsor sign a capability for the app's key: the right to spend up to a cap from the
   sponsor's pool until an expiry.
3. Meanwhile the app polls `GET /v1/capability?client=<address>` every 2 s. The gate page says
   to return to your terminal; switch back to the app instead. The capability is waiting, and the
   download starts.

The allowance line on the screen shows the key and how long its capability lasts. A capability's
terms are fixed once issued, so when one runs out or comes within about an hour of expiry, the
app starts over with a fresh key and sends you through the check again. An interrupted download
resumes under the new key without paying twice for the bytes it has.

## Use it

| Field | What to enter |
|---|---|
| Content hash | The blob's BLAKE3 hash, hex, with or without `b3:` |
| Namespace | The publisher's namespace id. Empty sends none. |
| Save as | The file name, or the folder name for a bundle |
| Whole bundle | Tick when the hash is a bundle manifest |

The status line shows the phase (finding nodes, downloading, verifying), verified bytes against
the total, and the current rate. **Cancel** stops the pull where it is; **Download** again
resumes it.

To try it, pull the [Sintel](https://durian.blender.org/) 4K bundle (namespace `1`, tick
**Whole bundle**, about 4.2 GiB):

```text
b3:87539fba0ee64b9f7814d50af89988b0e10f624340dd6e8477a0984c27eaab42
```

## Scope

This is a demo of the SDK, not a product. It targets the Arbitrum Sepolia testnet, whose
contract addresses are built into `rust/src/lib.rs`, and builds for `arm64-v8a` only. Android
stops a sleeping app's network, so the screen stays on while a pull runs; a production app runs
the pull in a foreground service instead. Finished files are copied into `Download/`, so a pull
needs twice its size free.

## License

Dual-licensed under either [MIT](LICENSE-MIT) or [Apache-2.0](LICENSE-APACHE), at your
option.
