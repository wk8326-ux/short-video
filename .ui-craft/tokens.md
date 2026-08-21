# A / Restrained liquid glass

- Theme: intentional dark only; canvas `#090a0b`, soft canvas `#0e0f11`, raised surface `#15171a`, strong surface `#1b1d21`.
- Accent: restrained red `#e7464f`; semantic colors are reserved for health states.
- Type: Android/system sans-serif, 12/14/16/20px working scale, normal letter spacing.
- Spacing: 4/8/12/16/24/32/48/64px scale; safe-area insets on fixed controls.
- Radius: 6px controls, 8px glass panels and source cards; circular icon hit areas stay visually transparent.
- Glass: `rgba(12,13,15,.56)` fill, 18% white outline, 16px reference blur; only navigation, control clusters, dialogs and the mini player use glass.
- Motion: 120ms feedback, 220ms state/surface changes, 320ms low-frequency entrances; no decorative motion.
- Touch: Android interactive targets are at least 48dp; player controls remain thumb reachable.
- Elevation: low-alpha white rings and top highlights replace dark shadows.
- Overlay controls: transparent 44-48px hit areas with white glyphs; no default circular fill or frame.
- Progress: 2px white track, 9px circular thumb, buffered segment at 38% white, and a restrained playing pulse.
- Top tabs: four equal modules, 24px active underline, 200ms same-layer movement; screen exit completes in 150ms.
