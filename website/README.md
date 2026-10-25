# CASCADE MESH website

Open `index.html` for the self-contained website. CSS, JavaScript, fonts, QR generation, mockups and SVG fallback illustrations are inline. Three.js 0.160.1 UMD loads from cdnjs when a 3D section becomes visible. The page remains readable without WebGL or CDN access.

For a local preview with APK download verification:

```sh
node serve.cjs
```

Open the loopback URL printed by the server. Keep `downloads/cascade-mesh-0.1.0-debug.apk` next to the page in its downloads folder. To share the QR code with another device, serve this folder on an address that device can reach; the local preview QR is not a public download address.

The theme button selects light monochrome or black/green. The initial choice follows the system, and an explicit selection is saved locally. The Motion button and reduced-motion system preference stop decorative animations. Background halftone dots and ASCII diagrams are decorative and hidden from assistive technology.

The thirteen phone frames are illustrative mockups, not live Android screenshots. The browser simulator is deterministic synthetic visualization, not a cryptographic or field-safety benchmark. The adjacent APK is a development build.

## Dependencies

- Three.js 0.160.1, MIT, loaded from cdnjs.
- qrcode-generator 1.4.4, MIT, inline QR encoder by Kazuhiko Arase.
- Space Grotesk and JetBrains Mono, SIL Open Font License; license texts are in the Android project's `docs/` directory.

### QR encoder MIT license

Copyright (c) 2009 Kazuhiko Arase

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
