# GG Clean

Clean, modern, maintainable rewrite foundation for a GameGuardian-style memory editor.

This is **not** a drop-in replacement yet. It is a proper Android project skeleton + architecture that maps to the reverse-engineered 101.4 codebase so we can rebuild feature-by-feature without drowning in obfuscated smali/Java.

## Project structure

```
GG-Clean/
├── app/          # Application, MainActivity, MainService, manifest
├── core/         # Memory engine, process management, root, search session
├── lua/          # Lua host + GG script API compatibility layer
├── ui/           # Floating panels, search UI, widgets
└── docs/         # Mapping from original classes → new modules
```

## SDK targets

- minSdk 26
- targetSdk / compileSdk 35
- Java/Kotlin 17
- Material 3

## Original code reference

The jadx decompile of the 101.4 APK lives at:

`/home/workdir/artifacts/gg_jadx/sources/`

See `docs/ORIGINAL_CLASS_MAP.md` for the detailed mapping of the most important original classes.

## Current status

- [x] Clean multi-module Gradle project
- [x] Modern SDK levels + foreground service setup
- [x] Core interfaces defined (Root, Process, Memory, Search, Lua, Float)
- [x] Concrete RootManager implementation (DefaultRootManager)
- [x] ProcessManager (list / attach / info)
- [x] MemoryEngine (read / write / maps / basic search)
- [x] Minimal floating UI (bubble + process list + test read)
- [x] Search session (first scan + refine) + UI tab
- [x] Value type picker + edit selected result
- [x] Lua host + essential GG API (searchNumber, refineNumber, getResults, editAll, …)
- [x] Settings system (type, max results, su path, system processes, timeout, opacity)

## Recommended next steps

1. Implement `RootManager` (port the su path detection + command execution)
2. Implement basic process listing
3. Implement `MemoryEngine.read` / `write` (hardest real work)
4. Build a minimal modern floating UI that can show process list + basic read/write
5. Only then attack search and Lua

## Important reality notes

- The original memory engine is buried inside huge obfuscated classes. We reimplement cleanly rather than try to “clean” 6k-line obfuscated files.
- Detection resistance is a continuous arms race and is **not** the first priority.
- Keeping high compatibility with existing GG Lua scripts is a deliberate goal of the `:lua` module.
