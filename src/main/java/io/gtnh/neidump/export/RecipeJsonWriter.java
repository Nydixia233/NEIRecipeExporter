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
                            Map<String, Integer> typeCounts,
                            Map<String, Integer> modCounts) throws IOException {
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

        report.put("type_counts", typeCounts != null ? typeCounts : new LinkedHashMap<>());
        report.put("mod_counts", modCounts != null ? modCounts : new LinkedHashMap<>());

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
