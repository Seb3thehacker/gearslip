<img src="docs/icon/gearslip-icon.png" alt="Gearslip icon" width="96" height="96">

# Gearslip

Bring any app to your car screen.

## What this is

Gearslip is a phone-side Android Auto client. It speaks the Android Auto protocol directly
to a car head unit over USB and acts as a **Car App Library host**: apps that ship an
Android Auto car UI (navigation and media apps) hand Gearslip a template, and Gearslip
renders it natively on the car screen, full quality, no phone-screen mirroring. A separate
screen-sharing mode is included for apps that don't ship a car UI at all.

Built by reverse-engineering the protocol for interoperability - getting a non-Google client
talking to a car head unit that only expects Google's own app.

## Status

Real progress, with one hard limit that's worth knowing before you build on this:

- **Templated app hosting works.** All 16 template types the Car App Library defines are
  rendered, including a keyboard Gearslip draws itself for search and sign-in. Verified
  against several real navigation and media apps.
- **Media playback works.** Car-enabled media apps are browsable and controllable from the
  car UI, with lyrics and playback state, over the same host connection.
- **The connection to a real head unit needs a certificate Google controls, and Gearslip
  doesn't ship one.** A head unit only accepts a phone identity that chains to Google's
  Automotive Link certificate authority - something only Google issues. Without one, Gearslip
  still runs and renders everything correctly against a debug harness, but a real car will
  reject the connection outright. See [SPIKE_FINDINGS.md](SPIKE_FINDINGS.md) for what was
  tested, including one publicly-known leaked certificate that works on some older head
  units and is rejected by newer firmware and by Google's own reference tooling.
- **No adb, root, or Shizuku is required to run it.** Every feature is gated on an ordinary
  Android permission or an on-device consent dialog - nothing needs privileged access, on
  the phone or in the car.

## Building

```
./gradlew :gearslip:assembleDebug
```

The APK lands at `build/Gearslip-debug.apk`.

## Further reading

- [SPIKE_FINDINGS.md](SPIKE_FINDINGS.md) - the certificate investigation in full: what was
  tried, what a real head unit does and doesn't accept, and why.
- [CUSTOM_HEADUNIT_SCOPING.md](CUSTOM_HEADUNIT_SCOPING.md) - the original scoping of what a
  custom head unit client would need.

## License

AGPL-3.0 - see [LICENSE](LICENSE). A separate commercial license is available for private
use outside the AGPL's terms; see [NOTICE.md](NOTICE.md).
