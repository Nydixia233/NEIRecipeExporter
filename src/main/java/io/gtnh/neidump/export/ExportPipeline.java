package io.gtnh.neidump.export;

import io.gtnh.neidump.extract.NEIRecipeExtractor;
import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.IconExporter;
import io.gtnh.neidump.util.ProgressCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end coordinator: extract → JSON → icons.
 *
 * <p>Caller (the {@code /dumpnei} command) builds a {@link Config}, calls
 * {@link #run(Config, ICommandSender)}, and uses the returned {@link Result} to
 * format a final chat message.
 *
 * <p>Two icon modes:
 * <ul>
 *   <li><b>Blocking (default)</b> — pipeline schedules icon export on the render
 *       thread and {@code await()}s on a {@link CountDownLatch}. Returns once
 *       icons are on disk. {@link Result#iconsExported} is populated.</li>
 *   <li><b>Async ({@code cfg.async = true})</b> — icon job is queued, the
 *       method returns immediately. A follow-up chat line is sent when icons
 *       finish. {@link Result#iconsExported} stays at {@code -1}.</li>
 * </ul>
 */
public class ExportPipeline {

    public static final class Config {
        public String fileName = "all_recipe_runtime.json";
        public boolean strict = false;
        public boolean exportIcons = true;
        public boolean async = false;
        public String filterMod;
        public String filterType;
    }

    public static final class Result {
        public int recipes;
        public int handlersSeen;
        public int handlersFailed;
        /** -1 means "still running" (async mode). */
        public int iconsExported = -1;
        public File jsonFile;
        public File reportFile;
        public File iconDir;
        public long elapsedMs;
    }

    public Result run(Config cfg, ICommandSender sender) throws Exception {
        long t0 = System.currentTimeMillis();
        Result result = new Result();

        // ---- 1. Resolve output paths ----
        File baseDir = Minecraft.getMinecraft().mcDataDir;
        File exportDir = new File(baseDir, "export");
        result.jsonFile = new File(exportDir, cfg.fileName);
        String reportName = cfg.fileName.endsWith(".json")
                ? cfg.fileName.substring(0, cfg.fileName.length() - 5) + ".report.json"
                : cfg.fileName + ".report.json";
        result.reportFile = new File(exportDir, reportName);
        result.iconDir = new File(exportDir, "icons");

        // ---- 2. Extract ----
        chat(sender, "[NEI Export] Extracting recipes"
                + (cfg.filterMod != null ? " mod=" + cfg.filterMod : "")
                + (cfg.filterType != null ? " type=" + cfg.filterType : "")
                + "...");
        NEIRecipeExtractor extractor = new NEIRecipeExtractor();
        NEIRecipeExtractor.ExtractionResult ext = extractor.extractAll();

        // ---- 3. Filter ----
        List<ExportRecipe> filtered = applyFilters(ext.recipes, cfg.filterMod, cfg.filterType);
        if (filtered.size() != ext.recipes.size()) {
            chat(sender, "[NEI Export] Filtered " + ext.recipes.size() + " → " + filtered.size());
        }

        result.recipes = filtered.size();
        result.handlersSeen = ext.handlersSeen;
        result.handlersFailed = ext.handlersFailed;

        // ---- 4. Write JSON + report ----
        RecipeJsonWriter writer = new RecipeJsonWriter();
        writer.write(result.jsonFile, filtered, ext.handlersSeen, ext.handlersFailed, !cfg.strict);
        writer.writeReport(result.reportFile, ext.handlersSeen, ext.handlersFailed,
                ext.discoveredHandlers, ext.failedHandlerReasons,
                filtered.size(), ext.typeCounts, ext.modCounts);
        chat(sender, "[NEI Export] JSON written: " + filtered.size() + " recipes");

        // ---- 5. Icons ----
        if (cfg.exportIcons) {
            if (cfg.async) {
                scheduleIconJobAsync(filtered, result.iconDir, sender);
                chat(sender, "[NEI Export] Icons queued (async). Will notify on completion.");
            } else {
                int icons = runIconJobBlocking(filtered, result.iconDir, sender);
                result.iconsExported = icons;
            }
        } else {
            result.iconsExported = 0;
        }

        result.elapsedMs = System.currentTimeMillis() - t0;
        return result;
    }

    // ========================================================================
    //  Filtering
    // ========================================================================

    private static List<ExportRecipe> applyFilters(List<ExportRecipe> input,
                                                   String filterMod, String filterType) {
        if (filterMod == null && filterType == null) return input;
        List<ExportRecipe> out = new ArrayList<>(input.size());
        String mod = filterMod != null ? filterMod.toLowerCase() : null;
        String type = filterType != null ? filterType.toLowerCase() : null;
        for (ExportRecipe r : input) {
            if (type != null && !r.getType().toLowerCase().contains(type)) continue;
            if (mod != null && !hasModInRecipe(r, mod)) continue;
            out.add(r);
        }
        return out;
    }

    private static boolean hasModInRecipe(ExportRecipe r, String modLower) {
        for (Map.Entry<String, Map<String, String>> e : r.getInput().entrySet()) {
            String id = e.getValue().get("id");
            if (id != null && id.toLowerCase().startsWith(modLower + ":")) return true;
        }
        for (Map.Entry<String, Map<String, String>> e : r.getOutput().entrySet()) {
            String id = e.getValue().get("id");
            if (id != null && id.toLowerCase().startsWith(modLower + ":")) return true;
        }
        return false;
    }

    // ========================================================================
    //  Icon scheduling
    // ========================================================================

    /**
     * Schedule the icon job on the client render thread and block until it finishes.
     * On the integrated server tick this stalls the world for ~2 minutes — that's
     * acceptable for a developer export tool.
     */
    private int runIconJobBlocking(final List<ExportRecipe> recipes, final File iconDir,
                                   final ICommandSender sender) throws InterruptedException {
        chat(sender, "[NEI Export] Rendering icons (blocking, ~2min)...");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger written = new AtomicInteger(-1);
        final ProgressCallback cb = new ProgressCallback() {
            @Override public void onProgress(String stage, int done, int total) {
                // console-only; chat would spam
            }
            @Override public void onStageDone(String stage, int done, int total) {
                System.out.println("[NEIExport] " + stage + " stage done: " + done + "/" + total);
            }
        };
        Minecraft.getMinecraft().func_152344_a(new Runnable() {
            public void run() {
                try {
                    written.set(IconExporter.exportIcons(recipes, iconDir, cb));
                } catch (Throwable t) {
                    System.err.println("[NEIExport] Icon job crashed: " + t);
                    t.printStackTrace();
                    written.set(0);
                } finally {
                    latch.countDown();
                }
            }
        });
        // 10-minute hard cap so a stuck render thread never deadlocks the command.
        if (!latch.await(10, TimeUnit.MINUTES)) {
            chat(sender, "[NEI Export] Icon job timed out after 10 minutes.");
            return 0;
        }
        return written.get();
    }

    /**
     * Fire-and-forget: queue the icon job on the render thread, return immediately,
     * and emit a final chat message when it finishes.
     */
    private void scheduleIconJobAsync(final List<ExportRecipe> recipes, final File iconDir,
                                      final ICommandSender sender) {
        final ProgressCallback cb = new ProgressCallback() {
            @Override public void onProgress(String stage, int done, int total) {
                if (done == total / 2) {
                    chat(sender, "[NEI Export] " + stage + ": " + done + "/" + total);
                }
            }
            @Override public void onStageDone(String stage, int done, int total) {
                chat(sender, "[NEI Export] " + stage + " done: " + done + "/" + total);
            }
        };
        Minecraft.getMinecraft().func_152344_a(new Runnable() {
            public void run() {
                try {
                    int icons = IconExporter.exportIcons(recipes, iconDir, cb);
                    chat(sender, "[NEI Export] Icons done: " + icons + " written to " + iconDir);
                } catch (Throwable t) {
                    System.err.println("[NEIExport] Icon job crashed: " + t);
                    t.printStackTrace();
                    chat(sender, "[NEI Export] Icon job failed: " + t);
                }
            }
        });
    }

    // ========================================================================
    //  Chat helper
    // ========================================================================

    private static void chat(ICommandSender sender, String msg) {
        if (sender != null) {
            sender.addChatMessage(new ChatComponentText(msg));
        }
        System.out.println(msg);
    }
}
