package io.gtnh.neidump.command;

import io.gtnh.neidump.export.ExportPipeline;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/**
 * {@code /dumpnei [filename.json] [--strict] [--mod <id>] [--type <t>] [--no-icons] [--async]}
 *
 * <p>Thin parser — all real work lives in {@link ExportPipeline}.
 */
public class CommandDumpNEIRecipes extends CommandBase {

    @Override
    public String getCommandName() {
        return "dumpnei";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dumpnei [filename.json] [--strict] [--mod <modid>] [--type <type>] [--no-icons] [--async]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        ExportPipeline.Config cfg = parseArgs(args);

        try {
            ExportPipeline.Result r = new ExportPipeline().run(cfg, sender);

            String iconStr = (r.iconsExported < 0)
                    ? "queued"
                    : String.valueOf(r.iconsExported);

            sender.addChatMessage(new ChatComponentText(
                    "[NEI Export] Done."
                            + " recipes=" + r.recipes
                            + ", handlers=" + r.handlersSeen
                            + ", failed=" + r.handlersFailed
                            + ", icons=" + iconStr
                            + ", strict=" + cfg.strict
                            + ", time=" + (r.elapsedMs / 1000) + "s"
            ));
            sender.addChatMessage(new ChatComponentText(
                    "[NEI Export]   json=" + r.jsonFile.getAbsolutePath()));
            sender.addChatMessage(new ChatComponentText(
                    "[NEI Export]   report=" + r.reportFile.getAbsolutePath()));
            if (cfg.exportIcons) {
                sender.addChatMessage(new ChatComponentText(
                        "[NEI Export]   icons=" + r.iconDir.getAbsolutePath()));
            }
        } catch (Exception e) {
            sender.addChatMessage(new ChatComponentText("[NEI Export] Failed: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    private static ExportPipeline.Config parseArgs(String[] args) {
        ExportPipeline.Config cfg = new ExportPipeline.Config();
        if (args == null) return cfg;

        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.trim().isEmpty()) continue;
            String clean = token.trim();

            if ("--strict".equalsIgnoreCase(clean)) {
                cfg.strict = true;
            } else if ("--no-icons".equalsIgnoreCase(clean)) {
                cfg.exportIcons = false;
            } else if ("--async".equalsIgnoreCase(clean)) {
                cfg.async = true;
            } else if ("--mod".equalsIgnoreCase(clean) && i + 1 < args.length) {
                cfg.filterMod = args[++i].trim().toLowerCase();
            } else if ("--type".equalsIgnoreCase(clean) && i + 1 < args.length) {
                cfg.filterType = args[++i].trim().toLowerCase();
            } else if (!clean.startsWith("--")) {
                cfg.fileName = clean.endsWith(".json") ? clean : clean + ".json";
            }
        }
        return cfg;
    }
}
