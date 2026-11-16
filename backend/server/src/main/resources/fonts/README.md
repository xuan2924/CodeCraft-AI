# Report PDF font

`DroidSansFallbackFull.ttf` is the static TrueType CJK fallback font from the Android Open Source Project, revision `d405a43`:

- Source: https://android.googlesource.com/platform/frameworks/base/+/d405a43/data/fonts/DroidSansFallbackFull.ttf
- Original license/notice: https://android.googlesource.com/platform/frameworks/base/+/d405a43/data/fonts/NOTICE
- License: Apache-2.0; original `NOTICE` text is included alongside the font (only its final empty line is removed for repository whitespace checks).
- Font SHA-256: `8a2eb30f184a596b468057c163dfb119f6deb3d72811546e6093e1808d3dc707`
- Upstream NOTICE SHA-256 (before final-empty-line normalization): `38751245389e1e23f73e6f5384b5cbe7fa972cc4410c5adc9c04b082a0b9561a`

The renderer embeds a subset. It does not read host fonts, fetch URLs, execute HTML, or redistribute Windows system fonts. Basic Latin uses PDF's standard Helvetica; unavailable Unicode glyphs are explicitly represented by their `U+...` code points rather than silently omitted. The report export is a text/evidence-summary document, not a source-code archive.
