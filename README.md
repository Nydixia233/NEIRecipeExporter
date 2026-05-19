# NEI Recipe Exporter (GTNH 1.7.10)

MVP mod for exporting all visible NEI recipes into JSON compatible with the existing GTNH data file schema.

## Current status

- Forge 1.7.10 mod skeleton created.
- Client command `/dumpnei` implemented.
- Reflection-based NEI handler discovery implemented.
- JSON exporter outputs `type/name/input/output` records.

## Command

- `/dumpnei`
- `/dumpnei <filename.json>`
- `/dumpnei <filename.json> --strict`

Output directory is `.minecraft/export/`.
The command writes two files:
- `<filename>.json`: recipe array in all_recipe-compatible shape.
- `<filename>.report.json`: handler coverage and failure details.

`--strict` disables extra fields so each row keeps only `type`, `name`, `input`, `output`.

## Build notes

This mod uses **RetroFuturaGradle (RFG)** — the modern GTNH-recommended replacement for ForgeGradle 1.2.

### Build it

A portable JDK21 and Gradle 8.10 are bundled under `tools/`. From the mod root:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-it.ps1
```

The artifact lands at `build/libs/neirecipeexporter-0.1.0.jar`. Drop that into `<minecraft>/mods/` (or your GTNH instance's mods folder), launch the client, then run `/dumpnei` in-game.

### Notes

- NEI / CodeChickenLib / CodeChickenCore / GregTech are accessed purely via reflection at runtime, so no compile-time dependencies on those mods are required.
- To regenerate handler type overrides from your latest `dumps/recipehandler.csv`, run `tools/generate-handler-overrides.ps1`.

## Iteration improvements

- Batch handler mapping now loads from `src/main/resources/handler-type-overrides.properties`.
- Fallback extraction now probes more method/field names and traverses nested object graphs with cycle guard.
- Deep traversal is bounded (`MAX_GRAPH_DEPTH=4`, `MAX_STACKS_PER_SIDE=64`) to avoid runaway recursion.

## Known limitations (MVP)

- Uses reflection over NEI internals, so some handlers may be skipped.
- GregTech-like fields (`eut`, `duration`) are best-effort hints only.
- Designed for client-side command execution.
