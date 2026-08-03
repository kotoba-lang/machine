# Changelog

## 0.1.0 — 2026-08-03

Initial extraction. T5 (mechanism) had no contract: the numbers that describe
hardware — cache line width, page size, private cache capacity, NUMA distance,
SIMD width, GPU subgroup, device queue depth — lived nowhere, so every planner
that needed them would have hardcoded its own.

- `machine.core` — closed, validated descriptor (`:kotoba.machine/v1`).
- Provenance (`:measured` / `:vendor-declared` / `:assumed`) is part of the
  descriptor, and `measured` refuses an empty source.
- Absent facts answer `nil`; `require-fact` throws with the path it wanted.
- Geometry checks that catch transcription errors, not just type errors
  (cache capacity must equal ways × line × sets and must increase by level;
  NUMA self-distance must be minimal; huge pages must exceed the base page).
- `fingerprint`: deterministic 31-bit, JVM/JS-exact, explicitly not
  cryptographic — it exists for staleness detection in `perfgate`.
- Profiles: `portable-64` (conservative floor, `:assumed`) and `unknown`
  (valid but factless). No product-specific profile is shipped, because
  hardcoding numbers nobody here measured is the fossil this repo prevents.
