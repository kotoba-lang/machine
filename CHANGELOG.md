# Changelog

## 0.2.0 — 2026-08-03

First contact with a real device (an Apple M1 Max, via the new
`kotoba-lang/machine-probe`) broke two assumptions this contract shipped with
that morning.

- **`:ways` is now optional.** macOS exposes cache sizes, line width, page
  size and cluster topology through `sysctl` and does not expose associativity
  at all. A contract demanding a fact the platform will not give leaves a probe
  choosing between fabricating a plausible 8 and refusing to describe the
  machine. Both are worse than recording that it is unknown. A declared but
  nonsensical `:ways` is still an error, and the geometry check still runs when
  it is present.
- **Heterogeneous CPUs are modelled.** `:cpu :clusters` carries per-cluster
  cores and caches, because P+E is the normal case now (Apple silicon, Intel
  hybrid, ARM DynamIQ) and one flat `:cache` cannot describe an 8-core cluster
  with a 12 MiB L2 shared by four alongside a 2-core cluster with 4 MiB shared
  by two.
  - Capacity questions (`cache-at`, `private-cache-bytes`) **throw** on a
    heterogeneous machine rather than silently answering for one half.
    `for-cluster` returns an ordinary flat descriptor every existing planner
    takes unchanged — naming the cluster is the whole cost.
  - `line-bytes` still answers, because a maximum does have a single answer.
  - Declaring both a flat `:cache` and `:clusters` is refused: a planner would
    read the flat one and mis-plan for the other half.

21 tests, 67 assertions.


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
