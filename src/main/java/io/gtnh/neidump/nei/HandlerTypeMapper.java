package io.gtnh.neidump.nei;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class HandlerTypeMapper {
    private final Map<String, String> exact = new HashMap<String, String>();

    public HandlerTypeMapper() {
        exact.put("codechicken.nei.recipe.shapedrecipehandler", "minecraft:crafting_shaped");
        exact.put("codechicken.nei.recipe.shapelessrecipehandler", "minecraft:crafting_shapeless");
        exact.put("codechicken.nei.recipe.furnacerecipehandler", "minecraft:furnace");
        exact.put("codechicken.nei.recipe.brewingrecipehandler", "minecraft:brewing");
        exact.put("codechicken.nei.recipe.fuelrecipehandler", "minecraft:fuel");

        exact.put("appeng.integration.modules.neihelpers.neiaeshapedrecipehandler", "minecraft:crafting_shaped");
        exact.put("appeng.integration.modules.neihelpers.neiaeshapelessrecipehandler", "minecraft:crafting_shapeless");
        exact.put("ic2.neiintegration.core.recipehandler.advrecipehandler", "minecraft:crafting_shaped");
        exact.put("ic2.neiintegration.core.recipehandler.advshapelessrecipehandler", "minecraft:crafting_shapeless");

        loadOverrides();
    }

    public String map(String handlerClassName, String simpleName) {
        String normalized = normalize(handlerClassName);
        String mapped = exact.get(normalized);
        if (mapped != null) {
            return mapped;
        }

        // GregTech machine handlers (must come BEFORE generic "recipe" rule)
        if (containsAny(normalized, "gregtech.nei", "gtneiore", "gtnhintergalactic.nei", "gtplusplus.nei", "bartworks.neihandler", "gtnewhorizons.aspectrecipeindex")) {
            return "gregtech:machine";
        }
        if (normalized.startsWith("gregtech.") || normalized.contains(".gregtech.")) {
            return "gregtech:machine";
        }

        if (containsAny(normalized, "shapedrecipe", "extremeshaped", "shapedarcane")) {
            return "minecraft:crafting_shaped";
        }
        if (containsAny(normalized, "shapelessrecipe", "extremeshapeless", "shapelessarcane")) {
            return "minecraft:crafting_shapeless";
        }
        if (containsAny(normalized, "furnace", "alloysmelter", "macerator", "compressor", "extractor", "sagmill", "lumbermill", "metalformer")) {
            return "modded:machine_processing";
        }
        if (containsAny(normalized, "inscriber", "assembler", "fabricator", "carpenter", "centrifuge", "squeezer", "fermenter", "still", "vat", "caster", "melting", "alloying", "altar", "alchemy", "infusion", "imbuing", "dryingrack", "spinningwheel")) {
            return "modded:machine_recipe";
        }
        if (containsAny(normalized, "bee", "tree", "flower", "mutation", "breeding", "crop")) {
            return "modded:breeding_or_mutation";
        }
        if (containsAny(normalized, "oredictionary", "fluidregistry")) {
            return "minecraft:registry_lookup";
        }
        if (containsAny(normalized, "mob", "villagertrade")) {
            return "modded:info_or_stat";
        }

        if (containsAny(normalized, "fuel")) {
            return "minecraft:fuel";
        }
        if (containsAny(normalized, "brewing")) {
            return "minecraft:brewing";
        }

        if (containsAny(normalized, "recipe", "handler")) {
            return "minecraft:crafting";
        }

        String fallback = simpleName == null ? "recipe" : simpleName;
        if (fallback.isEmpty()) {
            fallback = "recipe";
        }
        return "nei:" + fallback.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private void loadOverrides() {
        InputStream stream = null;
        try {
            stream = HandlerTypeMapper.class.getResourceAsStream("/handler-type-overrides.properties");
            if (stream == null) {
                return;
            }
            Properties props = new Properties();
            props.load(stream);
            for (String key : props.stringPropertyNames()) {
                String normalizedKey = normalize(key);
                String value = props.getProperty(key);
                if (normalizedKey.isEmpty() || value == null || value.trim().isEmpty()) {
                    continue;
                }
                exact.put(normalizedKey, value.trim());
            }
        } catch (Exception ignored) {
            // Fall back to hard-coded heuristics when resource loading fails.
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
