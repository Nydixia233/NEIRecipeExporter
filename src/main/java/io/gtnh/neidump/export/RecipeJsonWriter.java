package io.gtnh.neidump.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.gtnh.neidump.model.ExportRecipe;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.List;
import java.util.Map;

public class RecipeJsonWriter {
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public File write(File outputFile,
                      List<ExportRecipe> recipes,
                      int handlersSeen,
                      int handlersFailed,
                      boolean includeExtra) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (ExportRecipe recipe : recipes) {
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("type", recipe.getType());
            row.put("name", recipe.getName());
            row.put("input", recipe.getInput());
            row.put("output", recipe.getOutput());
            if (includeExtra && !recipe.getExtra().isEmpty()) {
                for (Map.Entry<String, Object> entry : recipe.getExtra().entrySet()) {
                    row.put(entry.getKey(), entry.getValue());
                }
            }
            records.add(row);
        }

        FileWriter writer = null;
        try {
            writer = new FileWriter(outputFile);
            gson.toJson(records, writer);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        return outputFile;
    }

    public File writeReport(File reportFile,
                            int handlersSeen,
                            int handlersFailed,
                            List<String> discoveredHandlers,
                            Map<String, String> failedHandlerReasons,
                            int recipeCount,
                            List<ExportRecipe> recipes) throws IOException {
        File parent = reportFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        java.util.LinkedHashMap<String, Object> report = new java.util.LinkedHashMap<String, Object>();
        report.put("handlers_seen", handlersSeen);
        report.put("handlers_failed", handlersFailed);
        report.put("recipe_count", recipeCount);
        report.put("discovered_handlers", discoveredHandlers);
        report.put("failed_handler_reasons", failedHandlerReasons);

        // Type counts
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, Integer> modCounts = new LinkedHashMap<>();
        for (ExportRecipe r : recipes) {
            String t = r.getType();
            typeCounts.put(t, typeCounts.getOrDefault(t, 0) + 1);
            // Count mods from input/output items
            for (Map.Entry<String, Map<String, String>> e : r.getInput().entrySet()) {
                String id = e.getValue().get("id");
                if (id != null && id.contains(":")) {
                    String mod = id.substring(0, id.indexOf(':'));
                    modCounts.put(mod, modCounts.getOrDefault(mod, 0) + 1);
                }
            }
            for (Map.Entry<String, Map<String, String>> e : r.getOutput().entrySet()) {
                String id = e.getValue().get("id");
                if (id != null && id.contains(":")) {
                    String mod = id.substring(0, id.indexOf(':'));
                    modCounts.put(mod, modCounts.getOrDefault(mod, 0) + 1);
                }
            }
        }
        report.put("type_counts", typeCounts);
        report.put("mod_counts", modCounts);

        FileWriter writer = null;
        try {
            writer = new FileWriter(reportFile);
            gson.toJson(report, writer);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
        return reportFile;
    }
}
