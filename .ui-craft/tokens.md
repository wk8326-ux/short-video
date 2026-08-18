# Existing token decisions

- Theme: intentional dark only; canvas `#090a0b`, raised surface `#111214`.
- Accent: restrained red `#e7464f`; semantic colors are reserved for health states.
- Type: Android/system sans-serif, 12/14/16/20px working scale, normal letter spacing.
- Spacing: 4/8/12/16/24/32/48/64px scale; safe-area insets on fixed controls.
- Radius: 4px controls, 6px panels, circular icon buttons.
- Motion: 120-250ms state transitions; no decorative motion; reduced-motion respected.
- Touch: interactive targets are at least 44px; player controls remain thumb reachable.
- Elevation: low-alpha white rings and restrained dark overlays rather than decorative shadows.
- Overlay controls: transparent 44-48px hit areas with white glyphs; no default circular fill or frame.
- Progress: 2px white track, 9px circular thumb, buffered segment at 38% white, and a restrained playing pulse.
- Top tabs: three equal 72px modules, 24px active underline, 200ms same-layer movement; screen exit completes in 150ms.
