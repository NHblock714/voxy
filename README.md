# voxy — NeoForge 1.21.1 fork

Unofficial NeoForge 1.21.1 fork of [Voxy](https://github.com/MCRcortex/voxy) by MCRcortex,
continuing the [neo-voxy](https://github.com/JohnSnow14284/neo-voxy) port lineage.
Maintained by NHblock714.

## Changes over neo-voxy

Rendering
- Translucent water: straight-alpha bake (no alpha accumulation), SRC_ALPHA composite, ordered fluid meshing (walls first, surfaces last)
- Sea surface renders at the true sea height at every lod level instead of rounding up per ring
- Cross plants (grass, flowers, saplings) render as two crossed mid planes instead of a four-sided box
- Per-face-tile mip generation with an exact per-pixel tint mask; lightmap sampling parity with 0.2.14
- Underwater the LOD blit is skipped so the water fog occludes properly

Lighting
- Above-surface air carries sky light at all lod levels: light probe for sections without a DataLayer,
  sky-lit empty sections, self/neighbor light max for fluid faces, max-based sky mip
- Nearest-rounding terrain mips (no systematic +1 surface bias at lod rings)

Storage / lifecycle
- Re-ingest walks the whole mip chain, so stale or corrupt higher levels heal on revisit
- Instance shutdown flushes pending section saves before closing the storages
- Block states from removed mods fall back to air instead of crashing world join
- World engines close when leaving a world (worlds are deletable again)
- Dynamically registered mixins are dist-gated (dedicated servers boot clean)

Integrations
- sable: contraption LOD rendering out to a configurable percentage of voxy's render distance
- EclipticSeasons: seasonal snow LOD (code adapted from the VoxyCompat addon by TeamTea, BSD-3-Clause)
- VSS (voxy server side): terrain streaming compatibility; `/voxy debug probe` for storage inspection
- In-game "Integrations" options page (Sodium 0.8 video settings)

## Building

```
gradlew build
python tools/trim_jar.py
```

`trim_jar.py` produces the `-slim` jar: it keeps only the win64/linux64 rocksdb natives and strips
the lwjgl extension `module-info` so dedicated servers can boot (JPMS).

Compile-only jars for sable / EclipticSeasons belong in `libs/aero-spike/` and are not part of
this repository.

## License

Upstream Voxy is "All rights reserved — do not redistribute" (see LICENSE.md). This repository
exists as a GitHub fork for development and review; no built jars are distributed here.
All original Voxy credit belongs to [MCRcortex](https://github.com/MCRcortex).
