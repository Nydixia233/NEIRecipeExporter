# NEI Recipe Exporter (GTNH 1.7.10)

导出 GTNH 所有 NEI 配方到 JSON 的客户端模组。

## 当前状态

- Forge 1.7.10 模组骨架已完成
- 客户端命令 `/dumpnei` 已实现
- 基于反射的 NEI handler 发现 + `CraftingManager` 原版配方提取
- JSON 导出格式兼容 GTNH `all_recipe` schema

## 命令

- `/dumpnei` — 导出到 `all_recipe_runtime.json`
- `/dumpnei <文件名.json>` — 指定文件名
- `/dumpnei <文件名.json> --strict` — 仅输出 type/name/input/output

输出目录为 `.minecraft/export/`，同时生成两份文件：
- `<文件名>.json`：配方数组
- `<文件名>.report.json`：handler 覆盖率和失败详情

## 构建

本模组使用 RetroFuturaGradle (RFG)，JDK 21 和 Gradle 8.10 已内置在 `tools/`。

```powershell
powershell -ExecutionPolicy Bypass -File .\build-it.ps1
```

产物路径：`build/libs/NEIRecipeExporter-0.1.0.jar`，放入 GTNH 实例的 `mods/` 即可。

## 已知限制

- 通过反射访问 NEI 内部结构，部分 handler 可能被跳过
- GregTech 字段（`eut`、`duration` 等）为尽力提取
- 仅支持客户端命令执行

---

A client-side mod that exports all visible NEI recipes from GTNH to JSON.

## Current Status

- Forge 1.7.10 mod skeleton
- Client command `/dumpnei` implemented
- Reflection-based NEI handler discovery + `CraftingManager` vanilla recipe extraction
- JSON export compatible with GTNH `all_recipe` schema

## Command

- `/dumpnei` — exports to `all_recipe_runtime.json`
- `/dumpnei <filename.json>` — custom filename
- `/dumpnei <filename.json> --strict` — only type/name/input/output

Output directory is `.minecraft/export/`. Two files are written:
- `<filename>.json` — recipe array
- `<filename>.report.json` — handler coverage and failure details

## Build

This mod uses RetroFuturaGradle (RFG) with JDK 21 and Gradle 8.10 bundled under `tools/`.

```powershell
powershell -ExecutionPolicy Bypass -File .\build-it.ps1
```

Artifact: `build/libs/NEIRecipeExporter-0.1.0.jar`. Drop into your GTNH instance's `mods/` folder.

## Known Limitations

- Uses reflection over NEI internals, some handlers may be skipped
- GregTech fields (`eut`, `duration`, etc.) are best-effort
- Client-side command execution only
