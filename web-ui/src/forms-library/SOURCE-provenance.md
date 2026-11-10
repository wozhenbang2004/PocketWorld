# Source provenance

BoardUI components include adaptations of the MIT-licensed free source at
https://github.com/BoardUI/boardui/tree/ec5c5eb3e29c2441be63b9422cd54f411ef23466.
Copyright Mertcan Dundar Esmergul / BoardUI. Original notices are retained.

Some components are independent implementations or adaptations. No license for
reference screenshots, videos, brand artwork, or non-free source is implied.
See the retained upstream licenses and the project third-party notices.

2026-11-10: the retained `CheckboxGlyph` tick and indeterminate paths now use
the product-mapped `--color-control-indicator-background` instead of fixed white.
Geometry, check drawing, state handling and the original source remain unchanged.

2026-11-10: ordinary action glyphs in the production BoardUI adaptation now use
Solar Icons 2.3.5 through `src/icons.tsx`. The original BoardUI components,
interaction state, motion, focus and touch targets remain. Chevron helpers retain
their public API and RTL/sort direction; the close button adapts the optical size
to Solar's own inset. Retained upstream files are unchanged. See
`design/icons/sources.json` and `THIRD-PARTY-NOTICES.md` for the exact artwork and
adapter license distinction.
