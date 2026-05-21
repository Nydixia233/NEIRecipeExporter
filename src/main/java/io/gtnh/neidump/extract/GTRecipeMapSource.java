package io.gtnh.neidump.extract;

import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.ReflectionUtils;
import net.minecraft.item.ItemStack;

import java.util.*;

/**
 * Strategy 1: Extract recipes directly from GregTech {@code RecipeMap.ALL_RECIPE_MAPS}.
 */
public class GTRecipeMapSource implements IRecipeSource {

    private static final String CLS_RECIPE_MAP = "gregtech.api.recipe.RecipeMap";
    private static final String CLS_GT_RECIPE  = "gregtech.api.util.GTRecipe";

    @Override
    public String getName() { return "GregTech"; }

    @Override
    public List<ExportRecipe> extract(NEIRecipeExtractor context) {
        List<ExportRecipe> out = new ArrayList<>();

        Object allMapsObj = ReflectionUtils.readStaticField(CLS_RECIPE_MAP, "ALL_RECIPE_MAPS");
        if (allMapsObj == null) {
            System.err.println("[NEIExport] RecipeMap.ALL_RECIPE_MAPS could not be read");
            return out;
        }
        if (!(allMapsObj instanceof Map)) {
            System.err.println("[NEIExport] ALL_RECIPE_MAPS is not a Map, got: " + allMapsObj.getClass().getName());
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> allMaps = (Map<String, Object>) allMapsObj;
        System.out.println("[NEIExport] Found " + allMaps.size() + " GregTech recipe maps");
        int totalRaw = 0, totalAccepted = 0;

        for (Map.Entry<String, Object> entry : allMaps.entrySet()) {
            String mapName = entry.getKey();
            Object recipeMap = entry.getValue();

            Object recipesObj = ReflectionUtils.invokeNoArg(recipeMap, "getAllRecipes");
            if (!(recipesObj instanceof Collection)) {
                System.err.println("[NEIExport] map " + mapName + " getAllRecipes returned: " + recipesObj);
                continue;
            }
            @SuppressWarnings("unchecked")
            Collection<Object> recipes = (Collection<Object>) recipesObj;
            int mapAccepted = 0;
            for (Object rawRecipe : recipes) {
                totalRaw++;
                try {
                    ExportRecipe exp = convertGTRecipe(rawRecipe, mapName, null, context);
                    if (exp != null) { out.add(exp); mapAccepted++; totalAccepted++; }
                } catch (Exception ignored) { /* per-recipe safety */ }
            }
            if (!recipes.isEmpty()) {
                System.out.println("[NEIExport] map=" + mapName + " raw=" + recipes.size() + " accepted=" + mapAccepted);
            }
        }
        System.out.println("[NEIExport] GregTech total: raw=" + totalRaw + " accepted=" + totalAccepted);
        return out;
    }

    /** Convert a GTRecipe to ExportRecipe (reusable by NEIHandlerSource for cached GT recipes). */
    public static ExportRecipe convertGTRecipe(Object rawRecipe, String mapName,
                                                 String machineDisplayName, NEIRecipeExtractor context) {
        Object enabled = ReflectionUtils.readField(rawRecipe, "mEnabled");
        if (enabled instanceof Boolean && !((Boolean) enabled)) return null;
        Object hidden = ReflectionUtils.readField(rawRecipe, "mHidden");
        if (hidden instanceof Boolean && ((Boolean) hidden)) return null;

        int idx = context.incrIndex(mapName);
        String type = deriveTypeFromMapName(mapName);
        ExportRecipe recipe = new ExportRecipe(type, mapName + "/" + idx);

        if (machineDisplayName != null && !machineDisplayName.isEmpty()) {
            recipe.putExtra("machine", machineDisplayName);
            recipe.setName(machineDisplayName);
        }

        // Item inputs
        Object inputsObj = ReflectionUtils.readField(rawRecipe, "mInputs");
        int slot = 1;
        if (inputsObj instanceof ItemStack[]) {
            for (ItemStack stack : (ItemStack[]) inputsObj) {
                if (stack != null && stack.getItem() != null) {
                    recipe.putInputItem(slot, stack, context.stackToKey(stack));
                    slot++;
                }
            }
        }

        // Item outputs
        Object outputsObj = ReflectionUtils.readField(rawRecipe, "mOutputs");
        int[] outputChances = readIntArrayField(rawRecipe, "mOutputChances");
        slot = 1;
        String firstOutputDisplay = null;
        if (outputsObj instanceof ItemStack[]) {
            ItemStack[] outStacks = (ItemStack[]) outputsObj;
            for (int i = 0; i < outStacks.length; i++) {
                ItemStack stack = outStacks[i];
                if (stack != null && stack.getItem() != null) {
                    String id = context.stackToKey(stack);
                    recipe.putOutputItem(slot, stack, id);
                    int chance = (outputChances != null && i < outputChances.length) ? outputChances[i] : 10000;
                    if (chance < 10000) setItemChance(recipe.getOutput(), slot, chance);
                    if (slot == 1) firstOutputDisplay = context.stripFormat(stack.getDisplayName());
                    slot++;
                }
            }
        }

        // Fluid inputs — slot derived from current input map size (mirrors fluid
        // outputs below). Don't reuse the `slot` counter above: it was clobbered
        // by the item-output loop and would overwrite item-input entries.
        Object fluidIns = ReflectionUtils.readField(rawRecipe, "mFluidInputs");
        if (fluidIns instanceof Object[]) {
            int inSlot = recipe.getInput().size() + 1;
            for (Object fs : (Object[]) fluidIns) {
                if (fs == null) continue;
                String id = fluidName(fs);
                String display = fluidDisplayName(fs);
                Object amt = ReflectionUtils.readField(fs, "amount");
                int amount = (amt instanceof Number) ? ((Number) amt).intValue() : 0;
                if (id != null) {
                    recipe.putFluidInput(inSlot, id, display != null ? display : id, amount);
                    inSlot++;
                }
            }
        }

        // Fluid outputs
        Object fluidOuts = ReflectionUtils.readField(rawRecipe, "mFluidOutputs");
        int[] fluidOutChances = readIntArrayField(rawRecipe, "mFluidOutputChances");
        if (fluidOuts instanceof Object[]) {
            Object[] fsa = (Object[]) fluidOuts;
            int outSlot = recipe.getOutput().size() + 1;
            for (int i = 0; i < fsa.length; i++) {
                Object fs = fsa[i];
                if (fs == null) continue;
                String id = fluidName(fs);
                String display = fluidDisplayName(fs);
                Object amt = ReflectionUtils.readField(fs, "amount");
                int amount = (amt instanceof Number) ? ((Number) amt).intValue() : 0;
                if (id != null) {
                    recipe.putFluidOutput(outSlot, id, display != null ? display : id, amount);
                    int chance = (fluidOutChances != null && i < fluidOutChances.length) ? fluidOutChances[i] : 10000;
                    if (chance < 10000) setItemChance(recipe.getOutput(), outSlot, chance);
                    outSlot++;
                }
            }
        }

        // Name
        if (machineDisplayName != null && !machineDisplayName.isEmpty()) {
            if (firstOutputDisplay != null && !firstOutputDisplay.isEmpty())
                recipe.setName(machineDisplayName + " → " + firstOutputDisplay);
        } else if (firstOutputDisplay != null && !firstOutputDisplay.isEmpty()) {
            recipe.setName(firstOutputDisplay);
        }

        // EU/t and duration
        Object eut = ReflectionUtils.readField(rawRecipe, "mEUt");
        if (eut instanceof Number) recipe.putExtra("eut", ((Number) eut).intValue());
        Object dur = ReflectionUtils.readField(rawRecipe, "mDuration");
        if (dur instanceof Number) recipe.putExtra("duration", ((Number) dur).intValue());

        // Extra GT fields
        Object special = ReflectionUtils.readField(rawRecipe, "mSpecialValue");
        if (special instanceof Number) recipe.putExtra("special_value", ((Number) special).intValue());
        Object fake = ReflectionUtils.readField(rawRecipe, "mFakeRecipe");
        if (fake instanceof Boolean) recipe.putExtra("fake_recipe", fake);
        Object buffered = ReflectionUtils.readField(rawRecipe, "mCanBeBuffered");
        if (buffered instanceof Boolean) recipe.putExtra("can_be_buffered", buffered);
        Object emptyOut = ReflectionUtils.readField(rawRecipe, "mNeedsEmptyOutput");
        if (emptyOut instanceof Boolean) recipe.putExtra("needs_empty_output", emptyOut);
        Object cleanroom = ReflectionUtils.readField(rawRecipe, "mRequiresCleanroom");
        if (cleanroom instanceof Boolean) recipe.putExtra("requires_cleanroom", cleanroom);
        Object coins = ReflectionUtils.readField(rawRecipe, "mCoins");
        if (coins instanceof Number) recipe.putExtra("coins", ((Number) coins).intValue());
        Object specialItems = ReflectionUtils.readField(rawRecipe, "mSpecialItems");
        if (specialItems != null) recipe.putExtra("special_items", specialItems.toString());

        // Derive voltage tier from EU/t
        if (eut instanceof Number) {
            int eu = ((Number) eut).intValue();
            recipe.putExtra("voltage_tier", voltageTier(eu));
        }

        return (!recipe.getInput().isEmpty() || !recipe.getOutput().isEmpty()) ? recipe : null;
    }

    // ---- helpers ----

    static String fluidName(Object fluidStack) {
        Object fluid = ReflectionUtils.invokeNoArg(fluidStack, "getFluid");
        if (fluid == null) return null;
        Object nm = ReflectionUtils.invokeNoArg(fluid, "getName");
        return nm != null ? nm.toString() : null;
    }

    static String fluidDisplayName(Object fluidStack) {
        Object fluid = ReflectionUtils.invokeNoArg(fluidStack, "getFluid");
        if (fluid == null) return null;
        Object nm = ReflectionUtils.invokeNoArg(fluid, "getLocalizedName");
        if (nm != null) {
            String s = nm.toString();
            if (s != null && !s.isEmpty()) return s.replaceAll("\\u00a7.", "");
        }
        return null;
    }

    static int[] readIntArrayField(Object target, String fieldName) {
        Object obj = ReflectionUtils.readField(target, fieldName);
        if (obj instanceof int[]) return (int[]) obj;
        return null;
    }

    static void setItemChance(Map<String, Map<String, String>> map, int slot, int chance) {
        Map<String, String> entry = map.get(String.valueOf(slot));
        if (entry != null) {
            double pct = chance / 100.0;
            entry.put("chance", String.format("%.1f%%", pct));
        }
    }

    /** Map GregTech EU/t to voltage tier name. */
    static String voltageTier(int eut) {
        if (eut <= 0) return "N/A";
        if (eut <= 8)   return "ULV";
        if (eut <= 32)  return "LV";
        if (eut <= 128) return "MV";
        if (eut <= 512) return "HV";
        if (eut <= 2048) return "EV";
        if (eut <= 8192) return "IV";
        if (eut <= 32768) return "LuV";
        if (eut <= 131072) return "ZPM";
        if (eut <= 524288) return "UV";
        if (eut <= 2097152) return "UHV";
        if (eut <= 8388608) return "UEV";
        if (eut <= 33554432) return "UIV";
        if (eut <= 134217728) return "UMV";
        if (eut <= 536870912) return "UXV";
        return "MAX+";
    }

    /** Map a GT RecipeMap name to a recipe type. */
    static String deriveTypeFromMapName(String mapName) {
        if (mapName == null) return "gregtech:machine";
        String lower = mapName.toLowerCase(java.util.Locale.ROOT);

        // Core GregTech / GT++ / GalacticGreg maps
        if (lower.startsWith("gt.") || lower.startsWith("gtpp.")
                || lower.startsWith("gg.") || lower.startsWith("gtnh")
                || lower.startsWith("bartworks") || lower.startsWith("bw.")
                || lower.startsWith("kubatech") || lower.startsWith("gtnhlanth")
                || lower.startsWith("miscutils") || lower.contains("gregtech"))
            return "gregtech:machine";

        // CropsNH
        if (lower.contains("cropsnh") || lower.contains("crop"))
            return "modded:breeding_or_mutation";

        // Spinning wheel, drying rack, etc.
        if (lower.contains("spinning") || lower.contains("drying"))
            return "modded:machine_recipe";

        // Generic machine recipe for unknown maps
        if (lower.contains("recipe") || lower.contains("recipes"))
            return "modded:machine_recipe";

        return "gregtech:machine";
    }
}
