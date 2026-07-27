## Important upgrade notice

This is the first production-signed Pocket Financer release. The earlier
`v0.1.0` preview APK used a temporary debug signing certificate, so Android
cannot install `v1.0.0` over it.

If you installed `v0.1.0`, uninstall it before installing this release.
Uninstalling permanently removes Pocket Financer's local encrypted database,
downloaded model, and settings. Android backup cannot restore them because the
app intentionally disables backup. Preserve anything you need before
uninstalling.

This one-time transition does not apply to later stable releases. Starting with
`v1.0.0`, published APKs use the same protected production signing identity and
increasing Android version codes so they can update in place.

## Device compatibility

The downloadable APK supports 64-bit ARM Android devices and x86_64 emulators
running Android 8.0 or newer. It does not support 32-bit-only devices.
