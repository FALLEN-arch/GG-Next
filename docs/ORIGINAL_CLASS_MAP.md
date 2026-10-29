# Original Class → Clean Module Mapping

This document maps the most important classes from the jadx decompile of GameGuardian 101.4 to the new project structure.

## Core Controllers

| Original | Size (lines) | New location / notes |
|----------|--------------|----------------------|
| `android.ext.MainService` | 4356 | `app/.../service/MainService.kt` – keep thin |
| `android.ext.r` | 1486 | Activity / UI base logic → split into `:ui` |
| `android.ext.b1` | 4566 | Process / app / protection logic → `:core:process` + related |
| `android.ext.n` | 6762 | Largest class – almost certainly core memory/search engine. Primary target for `:core:memory` |
| `android.ext.f0` | 3659 | Another major engine component |
| `android.ext.m` | 2172 | Supporting engine / UI glue |

## Utilities & Config

| Original | Size | New location |
|----------|------|--------------|
| `android.ext.Tools` | 3553 | Split: UI helpers → `:ui`, process/maps → `:core` |
| `android.ext.Config` + `ConfigListAdapter` | 2856 + 1782 | New settings system in `:app` or `:core` |

## Floating UI

| Original | New location |
|----------|--------------|
| `android.ext.FloatPanel` | `ui/.../float/FloatPanel.kt` |
| `android.ext.ShowApp` | Related overlay helpers in `:ui` |

## Search UI

| Original | New location |
|----------|--------------|
| `android.ext.Searcher` | `ui/.../search/` |

## Memory UI widgets

| Original | New location |
|----------|--------------|
| `MemoryRange`, `RegionList`, `RegionListFiltered` | `ui/.../widgets/` or `:core` models |

## Scripting

| Original | Size | New location |
|----------|------|--------------|
| `android.ext.Script` | 5915 | `:lua` module – reimplement GG API surface cleanly |

## Root / Process execution

| Original | New location |
|----------|--------------|
| `android.ext.e2` | `core/.../root/RootManager` |
| `android.lang.ProcessBuilder` + `UNIXProcess` | Custom process handling inside `:core` (note: original has a native `loaded()` method) |

## Other notable

- `HexConverter`, `OffsetCalculator`, `InOut` → utility helpers in `:core` or `:ui`
- Full `luaj.*` package → will be replaced or wrapped by a modern Lua embedding in `:lua`

## Priority order for reimplementation

1. RootManager + basic process listing
2. MemoryEngine (read/write first, then search)
3. FloatPanel (modern overlay)
4. SearchSession + UI
5. LuaHost + GG API compatibility layer
6. Full settings + polish
