package io.gtnh.neidump.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import io.gtnh.neidump.model.ExportRecipe;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Streaming JSON writer for recipe exports.
 *
 * <p>Uses {@link JsonWriter} to serialise one {@link ExportRecipe} at a time so
 * memory stays constant regardless of recipe count (GTNH ships ~226K recipes,
 * which OOMed the previous all-at-once approach).
 *
 * <p>Output shape (top-level array, one object per recipe):
 * <pre>
 * [
 *   {
 *     "type": "...",
 *     "name": "...",
 *     "input":  { "1": {"id":"...", "count":"...", "meta":"...", ...}, ... },
 *     "output": { ... },
 *     // extra keys (when {@code includeExtra} is true) inlined here
 *     "catalysts": [...],
 *     "voltage_tier": "..."
 *   },
 *   ...
 * ]
 * </pre>
 */
public class RecipeJsonWriter {
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public File write(File outputFile,
                      List<ExportRecipe> recipes,
                      int handlersSeen,
                      int handlersFailed,
                      boolean includeExtra) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        BufferedWriter buf = null;
        JsonWriter jw = null;
        try {
            buf = new BufferedWriter(new FileWriter(outputFile));
            jw = new JsonWriter(buf);
            jw.setIndent("  ");
            jw.beginArray();
            for (ExportRecipe recipe : recipes) {
                jw.beginObject();
                jw.name("type").value(recipe.getType());
                jw.name("name").value(recipe.getName());

                jw.name("input");
                gson.toJson(recipe.getInput(), Map.class, jw);

                jw.name("output");
                gson.toJson(recipe.getOutput(), Map.class, jw);

                if (includeExtra && !recipe.getExtra().isEmpty()) {
                    for (Map.Entry<String, Object> entry : recipe.getExtra().entrySet()) {
                        jw.name(entry.getKey());
                        gson.toJson(entry.getValue(), Object.class, jw);
                    }
                }
                jw.endObject();
            }
            jw.endArray();
            jw.flush();
        } finally {
            if (jw != null) {
                try { jw.close(); } catch (IOException ignored) {}
            } else if (buf != null) {
                try { buf.close(); } catch (IOException ignored) {}
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

        BufferedWriter buf = null;
        JsonWriter jw = null;
        try {
            buf = new BufferedWriter(new FileWriter(reportFile));
            jw = new JsonWriter(buf);
            jw.setIndent("  ");
            jw.beginObject();
            jw.name("handlers_seen").value(handlersSeen);
            jw.name("handlers_failed").value(handlersFailed);
            jw.name("recipe_count").value(recipeCount);

            jw.name("discovered_handlers");
            gson.toJson(discoveredHandlers != null ? discoveredHandlers
                    : java.util.Collections.emptyList(), List.class, jw);

            jw.name("failed_handler_reasons");
            gson.toJson(failedHandlerReasons != null ? failedHandlerReasons
                    : java.util.Collections.emptyMap(), Map.class, jw);

            jw.name("type_counts");
            gson.toJson(typeCounts != null ? typeCounts
                    : java.util.Collections.emptyMap(), Map.class, jw);

            jw.name("mod_counts");
            gson.toJson(modCounts != null ? modCounts
                    : java.util.Collections.emptyMap(), Map.class, jw);

            jw.endObject();
            jw.flush();
        } finally {
            if (jw != null) {
                try { jw.close(); } catch (IOException ignored) {}
            } else if (buf != null) {
                try { buf.close(); } catch (IOException ignored) {}
            }
        }
        return reportFile;
    }
}
