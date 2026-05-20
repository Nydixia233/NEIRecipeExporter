package io.gtnh.neidump.command;

import io.gtnh.neidump.export.RecipeJsonWriter;
import io.gtnh.neidump.extract.NEIRecipeExtractor;
import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.IconExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CommandDumpNEIRecipes extends CommandBase {

    @Override
    public String getCommandName() {
        return "dumpnei";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dumpnei [filename.json] [--strict] [--mod <modid>] [--type <type>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String fileName = "all_recipe_runtime.json";
        boolean strict = false;
        String filterMod = null;
        String filterType = null;

        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                String token = args[i];
                if (token == null || token.trim().isEmpty()) continue;
                String clean = token.trim();

                if ("--strict".equalsIgnoreCase(clean)) {
                    strict = true;
                } else if ("--mod".equalsIgnoreCase(clean) && i + 1 < args.length) {
                    filterMod = args[++i].trim().toLowerCase();
                } else if ("--type".equalsIgnoreCase(clean) && i + 1 < args.length) {
                    filterType = args[++i].trim().toLowerCase();
                } else if (!clean.startsWith("--")) {
                    fileName = clean;
                    if (!fileName.endsWith(".json")) fileName = fileName + ".json";
                }
            }
        }

        sender.addChatMessage(new ChatComponentText("[NEI Export] Starting recipe extraction..."
                + (filterMod != null ? " mod=" + filterMod : "")
                + (filterType != null ? " type=" + filterType : "")));

        try {
            File baseDir = Minecraft.getMinecraft().mcDataDir;
            File output = new File(new File(baseDir, "export"), fileName);
            String reportName = fileName.replace(".json", ".report.json");
            File report = new File(new File(baseDir, "export"), reportName);

            NEIRecipeExtractor extractor = new NEIRecipeExtractor();
            NEIRecipeExtractor.ExtractionResult result = extractor.extractAll();

            // Apply filters
            List<ExportRecipe> filtered = result.recipes;
            if (filterMod != null || filterType != null) {
                sender.addChatMessage(new ChatComponentText("[NEI Export] Filtering..."));
                filtered = new ArrayList<ExportRecipe>();
                for (ExportRecipe r : result.recipes) {
                    boolean keep = true;
                    if (filterType != null && !r.getType().toLowerCase().contains(filterType))
                        keep = false;
                    if (keep && filterMod != null) {
                        boolean hasMod = false;
                        for (java.util.Map.Entry<String, java.util.Map<String, String>> e
                                : r.getInput().entrySet()) {
                            String id = e.getValue().get("id");
                            if (id != null && id.toLowerCase().startsWith(filterMod + ":")) {
                                hasMod = true; break;
                            }
                        }
                        if (!hasMod) {
                            for (java.util.Map.Entry<String, java.util.Map<String, String>> e
                                    : r.getOutput().entrySet()) {
                                String id = e.getValue().get("id");
                                if (id != null && id.toLowerCase().startsWith(filterMod + ":")) {
                                    hasMod = true; break;
                                }
                            }
                        }
                        keep = hasMod;
                    }
                    if (keep) filtered.add(r);
                }
                sender.addChatMessage(new ChatComponentText("[NEI Export] After filter: "
                        + result.recipes.size() + " → " + filtered.size()));
            }

            RecipeJsonWriter writer = new RecipeJsonWriter();
            writer.write(output, filtered, result.handlersSeen, result.handlersFailed, !strict);
            writer.writeReport(report, result.handlersSeen, result.handlersFailed,
                    result.discoveredHandlers, result.failedHandlerReasons,
                    filtered.size(), result.typeCounts, result.modCounts);

            sender.addChatMessage(new ChatComponentText(
                    "[NEI Export] Done. recipes=" + filtered.size()
                            + ", handlers=" + result.handlersSeen
                            + ", failed=" + result.handlersFailed
                            + ", strict=" + strict
                            + ", file=" + output.getAbsolutePath()
                            + ", report=" + report.getAbsolutePath()
            ));
        } catch (Exception e) {
            sender.addChatMessage(new ChatComponentText("[NEI Export] Failed: " + e.getMessage()));
        }
    }
}
