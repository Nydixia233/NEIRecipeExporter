package io.gtnh.neidump.command;

import io.gtnh.neidump.export.RecipeJsonWriter;
import io.gtnh.neidump.extract.NEIRecipeExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.io.File;

public class CommandDumpNEIRecipes extends CommandBase {

    @Override
    public String getCommandName() {
        return "dumpnei";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dumpnei [filename.json] [--strict]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String fileName = "all_recipe_runtime.json";
        boolean strict = false;
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                String token = args[i];
                if (token == null || token.trim().isEmpty()) {
                    continue;
                }
                String clean = token.trim();
                if ("--strict".equalsIgnoreCase(clean)) {
                    strict = true;
                    continue;
                }
                fileName = clean;
                if (!fileName.endsWith(".json")) {
                    fileName = fileName + ".json";
                }
            }
        }

        sender.addChatMessage(new ChatComponentText("[NEI Export] Starting recipe extraction..."));

        try {
            File baseDir = Minecraft.getMinecraft().mcDataDir;
            File output = new File(new File(baseDir, "export"), fileName);
            String reportName = fileName.replace(".json", ".report.json");
            File report = new File(new File(baseDir, "export"), reportName);

            NEIRecipeExtractor extractor = new NEIRecipeExtractor();
            NEIRecipeExtractor.ExtractionResult result = extractor.extractAll();

            RecipeJsonWriter writer = new RecipeJsonWriter();
                writer.write(output, result.recipes, result.handlersSeen, result.handlersFailed, !strict);
            writer.writeReport(
                    report,
                    result.handlersSeen,
                    result.handlersFailed,
                    result.discoveredHandlers,
                    result.failedHandlerReasons,
                    result.recipes.size()
            );

            sender.addChatMessage(new ChatComponentText(
                    "[NEI Export] Done. recipes=" + result.recipes.size()
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
