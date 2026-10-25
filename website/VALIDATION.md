# Website and Android UI validation

## Minimal theme revision

- Shared visual direction: restrained monochrome light theme; black/green dark theme; rounded controls; thin dividers; decorative ASCII network diagrams and slow halftone dots.
- Android: theme switch in the persistent header and Settings, sentence-case action labels, Home / Requests / Map / More navigation, prominent Request help and Offer help actions. Dot animation stops in battery-saver mode and when Android animators are disabled.
- Website: system-theme default, persisted explicit theme choice, motion control and reduced-motion support. Single inline HTML with a separately downloadable APK.

## Browser verification

- Chromium: no horizontal page overflow at 320, 390, 768, 1024 and 1440 pixels.
- Axe WCAG A/AA automated checks: zero violations in both light and dark themes. Automated checks are not a substitute for a complete assistive-technology audit.
- All thirteen phone mockup dialogs open and close; five scene controls respond; simulator, download verification and theme persistence work.
- Forced WebGL unavailability displays the SVG fallback.
- Reduced motion disables automatic decorative animation.
- Download exercised through a real browser, then checked against the advertised byte count and SHA-256.
- No browser JavaScript errors in the tested flows.

## Android verification and remaining release work

The redesigned debug APK was built and installed in a fresh Android 14 emulator. Onboarding and theme switching were exercised. Emulator testing does not establish BLE range, relay reliability, energy use, or safety in a flood.

The earlier core modules passed 26 JVM tests. The wider inherited app suite had 23 failures among 693 tests, with three skipped; these include database-opening errors and chat/media delivery assertions. The full lint run reported 313 errors and 386 warnings, largely inherited translation, permission, quantity and chat code findings. Those results remain unresolved; this UI revision must not be presented as a clean production release. No errors were reported in the new CASCADE UI paths by that prior lint run, but it predates the latest restyling.

Physical-device Mesh Lab validation, release signing and complete field evaluation remain required. See `../docs/CASCADE-VALIDATION.md` for the safety and protocol checklist. No public deployment has been made.
