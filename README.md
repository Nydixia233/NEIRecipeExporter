# NEI Recipe Exporter (GTNH 1.7.10)

导出 GTNH 所有 NEI 配方到 JSON 的客户端模组，含图标渲染。

## 功能

- `/dumpnei` 客户端命令一键导出
- **226k+ 配方流式 JSON 写入**，不会 OOM
- **129 种 handler 类型覆盖**：GregTech、Forestry、IC2、EnderIO、Botania、Avaritia、Tinkers、AE2、BuildCraft、Thaumcraft 等主流 mod
- **图标 PNG 渲染**：自动把每个唯一物品/流体渲染成 32×32 PNG（GL framebuffer + RenderItem）
- 输出报告：handler 覆盖率、按 mod/type 统计、失败详情
- 支持 mod / type 过滤

## 命令

```
/dumpnei [filename.json] [--strict] [--mod <id>] [--type <t>] [--no-icons] [--async]
```

| 选项 | 说明 |
|---|---|
| `<filename.json>` | 自定义文件名，默认 `all_recipe_runtime.json` |
| `--strict` | 仅输出 type/name/input/output，去掉 catalysts/eut 等 extra |
| `--mod <id>` | 只导出输入或输出含该 mod 的配方 |
| `--type <t>` | 只导出 type 含 substring 的配方（如 `gregtech`） |
| `--no-icons` | 跳过图标渲染 |
| `--async` | 图标渲染在后台跑，命令立即返回，完成时聊天通知 |

输出在 `.minecraft/export/`：
- `<filename>.json` — 配方数组
- `<filename>.report.json` — handler 覆盖率和失败详情
- `icons/items/*.png`、`icons/fluids/*.png` — 32×32 PNG 图标

## 安装

把 `NEIRecipeExporter-<version>.jar` 丢进 GTNH 实例的 `mods/` 即可。需要 NEI 与 GregTech（运行时反射访问，无编译期依赖）。

## 构建

使用 RetroFuturaGradle (RFG)，需要 JDK 21 和 Gradle 8.10：

```bash
./gradlew build
```

产物：`build/libs/NEIRecipeExporter-<version>.jar`（reobf 后的 SRG 映射版本，不是 `-dev` 后缀那个）。

### 本地自动部署（可选）

新建 `deploy.gradle`（已 gitignore），列出你的 MC 实例 mods 目录：

```groovy
project.ext.DEPLOY_MODS_DIRS = [
        'C:/Users/<you>/AppData/Roaming/.minecraft/mods',
]
```

之后每次 `./gradlew build` 会自动把 reobf jar 复制到列出的目录并清理旧版本。

## 已知限制

- NEI handler 通过反射访问，少数自定义 handler 可能被跳过（fallback 策略已尽力）
- TConstruct 工具、损坏的原版工具、TESR 等动态渲染物品没有静态 atlas sprite，PNG 会是 32×32 透明（83 字节）
- 仅支持客户端命令执行（玩家手动触发，不在 server-side 跑）

---

# NEI Recipe Exporter (GTNH 1.7.10) — English

Client-side mod that exports every visible NEI recipe in a GTNH instance to JSON, plus icon PNGs.

## Features

- `/dumpnei` one-shot client command
- **Streaming JSON writer** — no OOM at 226k+ recipes
- **129 handler types covered** — GregTech, Forestry, IC2, EnderIO, Botania, Avaritia, Tinkers, AE2, BuildCraft, Thaumcraft, etc.
- **Icon PNG rendering** — every unique item/fluid baked to a 32×32 PNG via GL framebuffer + RenderItem
- Coverage report with handler diagnostics + per-mod / per-type counts
- Optional mod / type filtering

## Command

```
/dumpnei [filename.json] [--strict] [--mod <id>] [--type <t>] [--no-icons] [--async]
```

Output lives under `.minecraft/export/`:
- `<filename>.json` — recipe array
- `<filename>.report.json` — coverage + failures
- `icons/items/*.png`, `icons/fluids/*.png` — 32×32 PNG icons

## Install

Drop `NEIRecipeExporter-<version>.jar` into your GTNH instance's `mods/`. NEI and GregTech are accessed via reflection at runtime (no compile-time dep).

## Build

```bash
./gradlew build
```

Artifact: `build/libs/NEIRecipeExporter-<version>.jar` (reobf, SRG-mapped — NOT the `-dev` jar).

### Optional local auto-deploy

Drop a gitignored `deploy.gradle` next to `build.gradle`:

```groovy
project.ext.DEPLOY_MODS_DIRS = [
        'C:/Users/<you>/AppData/Roaming/.minecraft/mods',
]
```

Subsequent builds will copy the reobf jar into each listed `mods/` directory and prune older versions.

## Known Limitations

- Reflection-based NEI access — a few custom handlers may be skipped despite multi-strategy fallbacks
- Dynamic-render items (TConstruct tools, damaged vanilla tools, TESR-rendered blocks) lack static atlas sprites; their PNGs come out as 32×32 transparent (83 bytes)
- Client-side command only (no server-side dump path)
