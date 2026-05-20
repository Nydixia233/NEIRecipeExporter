package io.gtnh.neidump.extract;

import io.gtnh.neidump.model.ExportRecipe;

import java.util.*;

/**
 * Deduplication and field merging for recipes from multiple sources.
 */
public final class MergeDedup {

    private MergeDedup() {}

    /**
     * Add candidates to dest, deduplicating by input/output fingerprint.
     * If a duplicate is found, merge catalysts/machine/handler_class from the
     * duplicate into the existing recipe.
     */
    public static void addDeduped(List<ExportRecipe> dest, Set<String> seen,
                                   List<ExportRecipe> candidates) {
        // Build lookup from existing dest for O(1) merge on duplicates
        Map<String, ExportRecipe> seenMap = new LinkedHashMap<>();
        for (int i = 0; i < dest.size(); i++) {
            ExportRecipe r = dest.get(i);
            seenMap.put(recipeFingerprint(r), r);
        }
        for (ExportRecipe r : candidates) {
            String fp = recipeFingerprint(r);
            if (seen.add(fp)) {
                seenMap.put(fp, r);
                dest.add(r);
            } else {
                // Merge fields from duplicate into existing
                ExportRecipe existing = seenMap.get(fp);
                if (existing != null) mergeFields(existing, r);
            }
        }
    }

    /** Copy catalysts / machine / handler_class / better type from source to target. */
    private static void mergeFields(ExportRecipe target, ExportRecipe source) {
        Map<String, Object> targetExtra = target.getExtra();
        Map<String, Object> sourceExtra = source.getExtra();

        // Upgrade generic gregtech:machine to more specific handler type
        if ("gregtech:machine".equals(target.getType())
                && !"gregtech:machine".equals(source.getType())) {
            target.setType(source.getType());
        }

        if (sourceExtra.get("catalysts") != null
                && (targetExtra.get("catalysts") == null
                    || ((List<?>) targetExtra.get("catalysts")).isEmpty())) {
            target.putExtra("catalysts", sourceExtra.get("catalysts"));
        }
        if (sourceExtra.get("machine") != null
                && (targetExtra.get("machine") == null
                    || targetExtra.get("machine").toString().isEmpty())) {
            target.putExtra("machine", sourceExtra.get("machine"));
        }
        if (sourceExtra.get("handler_class") != null
                && (targetExtra.get("handler_class") == null
                    || targetExtra.get("handler_class").toString().isEmpty())) {
            target.putExtra("handler_class", sourceExtra.get("handler_class"));
        }
    }

    /** Stable fingerprint: type + sorted input (id@count@meta) + sorted output. */
    public static String recipeFingerprint(ExportRecipe r) {
        StringBuilder sb = new StringBuilder(r.getType());

        TreeSet<String> ins = new TreeSet<>();
        for (Map.Entry<String, Map<String, String>> e : r.getInput().entrySet()) {
            Map<String, String> v = e.getValue();
            ins.add(v.get("id") + "@" + v.get("count") + "@" + v.get("meta"));
        }
        for (String s : ins) sb.append('|').append(s);
        sb.append("->");

        TreeSet<String> outs = new TreeSet<>();
        for (Map.Entry<String, Map<String, String>> e : r.getOutput().entrySet()) {
            Map<String, String> v = e.getValue();
            outs.add(v.get("id") + "@" + v.get("count") + "@" + v.get("meta"));
        }
        for (String s : outs) sb.append('|').append(s);
        return sb.toString();
    }
}
