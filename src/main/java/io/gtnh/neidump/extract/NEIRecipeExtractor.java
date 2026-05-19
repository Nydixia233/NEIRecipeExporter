package io.gtnh.neidump.extract;

import cpw.mods.fml.common.registry.GameRegistry;
import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.ReflectionUtils;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extracts recipes from GTNH using two strategies:
 * <ol>
 *   <li>Direct GregTech {@code RecipeMap.ALL_RECIPE_MAPS} access —
 *       reads every registered machine recipe directly from GT's own storage.</li>
 *   <li>NEI handler extraction — discovers registered
 *       {@code IRecipeHandler} instances and triggers recipe loading by calling
 *       {@code getRecipeHandler / getUsageHandler} with the handler's own ID,
 *       then reads the resulting {@code arecipes} list.</li>
 * </ol>
 */
public class NEIRecipeExtractor {

    private static final String[][] HANDLER_REGISTRIES = {
        {"codechicken.nei.recipe.GuiCraftingRecipe", "craftinghandlers"},
        {"codechicken.nei.recipe.GuiCraftingRecipe", "serialCraftingHandlers"},
        {"codechicken.nei.recipe.GuiUsageRecipe",    "usagehandlers"},
        {"codechicken.nei.recipe.GuiUsageRecipe",    "serialUsageHandlers"},
    };

    // IRecipeHandler / TemplateRecipeHandler method names
    private static final String METH_GET_HANDLER_ID    = "getHandlerId";
    private static final String METH_GET_RECIPE_HANDLER = "getRecipeHandler";
    private static final String METH_GET_USAGE_HANDLER  = "getUsageHandler";
    private static final String METH_GET_RECIPE_NAME    = "getRecipeName";
    private static final String METH_GET_OVERLAY_ID     = "getOverlayIdentifier";

    // GregTech classes
    private static final String CLS_RECIPE_MAP  = "gregtech.api.recipe.RecipeMap";
    private static final String CLS_GT_RECIPE   = "gregtech.api.util.GTRecipe";

    // Vanilla / Forge crafting
    private static final String CLS_CRAFTING_MANAGER = "net.minecraft.item.crafting.CraftingManager";
    private static final String CLS_SHAPED_RECIPES   = "net.minecraft.item.crafting.ShapedRecipes";
    private static final String CLS_SHAPELESS_RECIPES = "net.minecraft.item.crafting.ShapelessRecipes";
    private static final String CLS_SHAPED_ORE_RECIPE   = "net.minecraftforge.oredict.ShapedOreRecipe";
    private static final String CLS_SHAPELESS_ORE_RECIPE = "net.minecraftforge.oredict.ShapelessOreRecipe";

    public static final class ExtractionResult {
        public final List<ExportRecipe> recipes;
        public final int handlersSeen;
        public final int handlersFailed;
        public final Map<String, String> failedHandlerReasons;
        public final List<String> discoveredHandlers;

        public ExtractionResult(List<ExportRecipe> recipes,
                                int handlersSeen,
                                int handlersFailed,
                                Map<String, String> failedHandlerReasons,
                                List<String> discoveredHandlers) {
            this.recipes = recipes;
            this.handlersSeen = handlersSeen;
            this.handlersFailed = handlersFailed;
            this.failedHandlerReasons = failedHandlerReasons;
            this.discoveredHandlers = discoveredHandlers;
        }
    }

    private final HandlerTypeMapper typeMapper = new HandlerTypeMapper();
    /** Stable per-mapName sequential IDs, reset each extractAll() call. */
    private final Map<String, Integer> recipeIndexByMap = new LinkedHashMap<String, Integer>();

    // ========================================================================
    //  Public entry point
    // ========================================================================

    public ExtractionResult extractAll() {
        recipeIndexByMap.clear();
        List<ExportRecipe> exported = new ArrayList<ExportRecipe>();
        Set<String> seenFingerprints = new LinkedHashSet<String>();
        Map<String, String> failedReasons = new LinkedHashMap<String, String>();
        List<String> discovered = new ArrayList<String>();
        int failed = 0;

        // ---- Strategy 1: GregTech recipe maps (bulk of machine recipes) --
        try {
            int before = exported.size();
            addDeduped(exported, seenFingerprints, extractGregTechRecipes());
            System.out.println("[NEIExport] GregTech strategy added "
                    + (exported.size() - before) + " recipes");
        } catch (Throwable e) {
            System.err.println("[NEIExport] GregTech extraction failed: " + e);
            e.printStackTrace();
        }

        // ---- Strategy 1.5: Vanilla crafting manager (CraftingManager) ----
        try {
            int before = exported.size();
            addDeduped(exported, seenFingerprints, extractVanillaCraftingRecipes());
            System.out.println("[NEIExport] CraftingManager strategy added "
                    + (exported.size() - before) + " recipes");
        } catch (Throwable e) {
            System.err.println("[NEIExport] CraftingManager extraction failed: " + e);
            e.printStackTrace();
        }

        // ---- Strategy 2: NEI handlers (with on-demand loading) -----------
        Set<Object> handlers = discoverHandlers();
        for (Object handler : handlers) {
            String cls = handler.getClass().getName();
            discovered.add(cls);
            try {
                List<ExportRecipe> recs = extractFromHandler(handler);
                addDeduped(exported, seenFingerprints, recs);
            } catch (Exception e) {
                failed++;
                failedReasons.put(cls, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() != null ? e.getMessage() : ""));
            }
        }

        return new ExtractionResult(exported, handlers.size(), failed,
                failedReasons, discovered);
    }

    // ========================================================================
    //  Strategy 1 — GregTech RecipeMap.ALL_RECIPE_MAPS
    // ========================================================================

    @SuppressWarnings("unchecked")
    private List<ExportRecipe> extractGregTechRecipes() {
        List<ExportRecipe> out = new ArrayList<ExportRecipe>();

        // RecipeMap.ALL_RECIPE_MAPS is a Map<String, RecipeMap<?>>
        Object allMapsObj = ReflectionUtils.readStaticField(CLS_RECIPE_MAP, "ALL_RECIPE_MAPS");
        if (allMapsObj == null) {
            System.err.println("[NEIExport] RecipeMap.ALL_RECIPE_MAPS could not be read (class not found or field inaccessible)");
            return out;
        }
        if (!(allMapsObj instanceof Map)) {
            System.err.println("[NEIExport] ALL_RECIPE_MAPS is not a Map, got: " + allMapsObj.getClass().getName());
            return out;
        }
        Map<String, Object> allMaps = (Map<String, Object>) allMapsObj;
        System.out.println("[NEIExport] Found " + allMaps.size() + " GregTech recipe maps");
        int totalRaw = 0;
        int totalAccepted = 0;

        for (Map.Entry<String, Object> entry : allMaps.entrySet()) {
            String mapName = entry.getKey();
            Object recipeMap = entry.getValue();

            // RecipeMap.getAllRecipes() → Collection<GTRecipe>
            Object recipesObj = ReflectionUtils.invokeNoArg(recipeMap, "getAllRecipes");
            if (!(recipesObj instanceof Collection)) {
                System.err.println("[NEIExport] map " + mapName
                        + " getAllRecipes returned: " + recipesObj);
                continue;
            }

            Collection<Object> recipes = (Collection<Object>) recipesObj;
            int mapAccepted = 0;
            for (Object rawRecipe : recipes) {
                totalRaw++;
                try {
                    ExportRecipe exp = convertGTRecipe(rawRecipe, mapName, null);
                    if (exp != null) {
                        out.add(exp);
                        mapAccepted++;
                        totalAccepted++;
                    }
                } catch (Exception e) {
                    // Single bad recipe (e.g. Forestry ItemHoneycomb bug) must not
                    // crash the entire RecipeMap extraction.
                }
            }
            if (recipes.size() > 0) {
                System.out.println("[NEIExport] map=" + mapName
                        + " raw=" + recipes.size() + " accepted=" + mapAccepted);
            }
        }

        System.out.println("[NEIExport] GregTech total: raw=" + totalRaw
                + " accepted=" + totalAccepted);
        return out;
    }

    /**
     * @param rawRecipe         a {@code gregtech.api.util.GTRecipe} object
     * @param mapName           internal RecipeMap key (e.g. "gt.recipe.assembler")
     * @param machineDisplayName human-readable machine name (e.g. "组装机"), may be null
     */
    private ExportRecipe convertGTRecipe(Object rawRecipe, String mapName, String machineDisplayName) {
        // Check mEnabled / mHidden
        Object enabled = ReflectionUtils.readField(rawRecipe, "mEnabled");
        if (enabled instanceof Boolean && !((Boolean) enabled)) return null;
        Object hidden = ReflectionUtils.readField(rawRecipe, "mHidden");
        if (hidden instanceof Boolean && ((Boolean) hidden)) return null;

        int idx = incrIndex(mapName);
        String recipeId = mapName + "/" + idx;
        ExportRecipe recipe = new ExportRecipe("gregtech:machine", recipeId);

        // ---- Machine name ----------------------------------------------
        if (machineDisplayName != null && !machineDisplayName.isEmpty()) {
            recipe.putExtra("machine", machineDisplayName);
            recipe.setName(machineDisplayName);  // will be overridden below if output exists
        }

        // ---- Item inputs (with display names) --------------------------
        Object inputsObj = ReflectionUtils.readField(rawRecipe, "mInputs");
        int slot = 1;
        if (inputsObj instanceof ItemStack[]) {
            for (ItemStack stack : (ItemStack[]) inputsObj) {
                if (stack != null && stack.getItem() != null) {
                    recipe.putInputItem(slot, stack, stackToKey(stack));
                    slot++;
                }
            }
        }

        // ---- Item outputs (with display names + chance) ----------------
        Object outputsObj = ReflectionUtils.readField(rawRecipe, "mOutputs");
        int[] outputChances = readIntArrayField(rawRecipe, "mOutputChances");
        slot = 1;
        String firstOutputDisplay = null;
        if (outputsObj instanceof ItemStack[]) {
            ItemStack[] outStacks = (ItemStack[]) outputsObj;
            for (int i = 0; i < outStacks.length; i++) {
                ItemStack stack = outStacks[i];
                if (stack != null && stack.getItem() != null) {
                    String id = stackToKey(stack);
                    recipe.putOutputItem(slot, stack, id);
                    // probability: 10000 = 100%, 5000 = 50%, etc.
                    int chance = (outputChances != null && i < outputChances.length)
                            ? outputChances[i] : 10000;
                    if (chance < 10000) {
                        setItemChance(recipe.getOutput(), slot, chance);
                    }
                    if (slot == 1) {
                        firstOutputDisplay = stripFormat(stack.getDisplayName());
                    }
                    slot++;
                }
            }
        }

        // Recipe name: machine → output, or just machine / output
        if (machineDisplayName != null && !machineDisplayName.isEmpty()) {
            if (firstOutputDisplay != null && !firstOutputDisplay.isEmpty()) {
                recipe.setName(machineDisplayName + " → " + firstOutputDisplay);
            }
        } else if (firstOutputDisplay != null && !firstOutputDisplay.isEmpty()) {
            recipe.setName(firstOutputDisplay);
        }

        // ---- Fluid inputs (as proper input entries) --------------------
        Object fluidIns = ReflectionUtils.readField(rawRecipe, "mFluidInputs");
        if (fluidIns instanceof Object[]) {
            for (Object fs : (Object[]) fluidIns) {
                if (fs == null) continue;
                String id = fluidName(fs);
                String display = fluidDisplayName(fs);
                Object amt = ReflectionUtils.readField(fs, "amount");
                int amount = (amt instanceof Number) ? ((Number) amt).intValue() : 0;
                if (id != null) {
                    recipe.putFluidInput(slot, id,
                            display != null ? display : id, amount);
                    slot++;
                }
            }
        }

        // ---- Fluid outputs (as proper output entries + chance) ---------
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
                    recipe.putFluidOutput(outSlot, id,
                            display != null ? display : id, amount);
                    int chance = (fluidOutChances != null && i < fluidOutChances.length)
                            ? fluidOutChances[i] : 10000;
                    if (chance < 10000) {
                        setItemChance(recipe.getOutput(), outSlot, chance);
                    }
                    outSlot++;
                }
            }
        }

        // ---- EU/t and duration ------------------------------------------
        Object eut = ReflectionUtils.readField(rawRecipe, "mEUt");
        if (eut instanceof Number) recipe.putExtra("eut", ((Number) eut).intValue());
        Object dur = ReflectionUtils.readField(rawRecipe, "mDuration");
        if (dur instanceof Number) recipe.putExtra("duration", ((Number) dur).intValue());

        // ---- Additional GT recipe fields --------------------------------
        Object amps = ReflectionUtils.readField(rawRecipe, "mAmperage");
        if (amps instanceof Number) recipe.putExtra("amps", ((Number) amps).intValue());
        Object voltage = ReflectionUtils.readField(rawRecipe, "mVoltage");
        if (voltage instanceof Number) recipe.putExtra("voltage", ((Number) voltage).intValue());
        Object special = ReflectionUtils.readField(rawRecipe, "mSpecialValue");
        if (special instanceof Number) recipe.putExtra("special_value", ((Number) special).intValue());
        Object buffered = ReflectionUtils.readField(rawRecipe, "mCanBeBuffered");
        if (buffered instanceof Boolean) recipe.putExtra("can_be_buffered", buffered);
        Object emptyOut = ReflectionUtils.readField(rawRecipe, "mNeedsEmptyOutput");
        if (emptyOut instanceof Boolean) recipe.putExtra("needs_empty_output", emptyOut);
        Object cleanroom = ReflectionUtils.readField(rawRecipe, "mRequiresCleanroom");
        if (cleanroom instanceof Boolean) recipe.putExtra("requires_cleanroom", cleanroom);
        // GT++ and some addons use mCoins for coin-consuming recipes
        Object coins = ReflectionUtils.readField(rawRecipe, "mCoins");
        if (coins instanceof Number) recipe.putExtra("coins", ((Number) coins).intValue());

        if (!recipe.getInput().isEmpty() || !recipe.getOutput().isEmpty()) {
            return recipe;
        }
        return null;
    }

    // ========================================================================
    //  Strategy 1.5 — Vanilla / Forge CraftingManager recipes
    // ========================================================================

    @SuppressWarnings("unchecked")
    private List<ExportRecipe> extractVanillaCraftingRecipes() {
        List<ExportRecipe> out = new ArrayList<ExportRecipe>();

        // In Forge 1.7.10 the mod classloader cannot resolve Minecraft classes
        // via Class.forName().  Use ItemStack's classloader (LaunchClassLoader)
        // to reach CraftingManager.
        Object craftMgrInstance = null;
        try {
            ClassLoader launchCl = ItemStack.class.getClassLoader();
            Class<?> cmClass = Class.forName(CLS_CRAFTING_MANAGER, false, launchCl);

            // Try getInstance() first, then fall back to scanning static fields.
            // GTNH may remap field names (SRG) so we can't hardcode "instance".
            try {
                java.lang.reflect.Method m = cmClass.getDeclaredMethod("getInstance");
                m.setAccessible(true);
                craftMgrInstance = m.invoke(null);
            } catch (NoSuchMethodException e) {
                for (java.lang.reflect.Field f : cmClass.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && f.getType().equals(cmClass)) {
                        f.setAccessible(true);
                        craftMgrInstance = f.get(null);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] CraftingManager class access failed: " + e);
        }
        if (craftMgrInstance == null) {
            System.err.println("[NEIExport] CraftingManager: could not obtain instance");
            return out;
        }

        Object recipesObj = null;
        try {
            recipesObj = ReflectionUtils.invokeNoArg(craftMgrInstance, "getRecipeList");
        } catch (Exception ignored) { }
        if (!(recipesObj instanceof List)) {
            // Scan all declared fields for any List — runtime may use SRG names
            for (java.lang.reflect.Field f : craftMgrInstance.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(craftMgrInstance);
                        if (val instanceof List) {
                            recipesObj = val;
                            System.out.println("[NEIExport] Found recipes via field: " + f.getName());
                            break;
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        if (!(recipesObj instanceof List)) {
            System.err.println("[NEIExport] CraftingManager recipes not a List: " + recipesObj);
            return out;
        }

        List<Object> recipes = (List<Object>) recipesObj;
        System.out.println("[NEIExport] CraftingManager has " + recipes.size() + " raw recipes");
        int accepted = 0;

        for (Object irecipe : recipes) {
            if (irecipe == null) continue;
            try {
                ExportRecipe exp = convertIRecipe(irecipe);
                if (exp != null) {
                    out.add(exp);
                    accepted++;
                }
            } catch (Exception e) {
                if (accepted < 1) {
                    System.err.println("[NEIExport] First IRecipe conversion failed: "
                            + irecipe.getClass().getName() + " - " + e);
                }
            }
        }

        System.out.println("[NEIExport] CraftingManager: raw=" + recipes.size()
                + " accepted=" + accepted);
        return out;
    }

    private ExportRecipe convertIRecipe(Object irecipe) {
        // --- output: try common method names (MCP + SRG) ---
        Object outputStack = null;
        for (String methodName : new String[]{"getRecipeOutput", "func_77571_b",
                "getCraftingResult", "getOutput"}) {
            outputStack = ReflectionUtils.invokeNoArg(irecipe, methodName);
            if (outputStack instanceof ItemStack && ((ItemStack) outputStack).getItem() != null) break;
            outputStack = null;
        }
        if (outputStack == null) return null;
        ItemStack outStack = (ItemStack) outputStack;
        if (outStack.getItem() == null) return null;

        String className = irecipe.getClass().getName();
        String type;
        if (className.contains("Shapeless") || className.contains("shapeless")) {
            type = "minecraft:crafting_shapeless";
        } else if (className.contains("Shaped") || className.contains("shaped")) {
            type = "minecraft:crafting_shaped";
        } else {
            type = "minecraft:crafting";
        }

        String displayName = stripFormat(outStack.getDisplayName());
        String recipeId = "crafting:" + stackToKey(outStack) + "@" + outStack.getItemDamage();
        ExportRecipe recipe = new ExportRecipe(type, displayName != null ? displayName : recipeId);
        recipe.putExtra("recipe_class", className);

        // --- inputs: try MCP names first, then SRG, then scan all fields ---
        Object rawInputs = null;
        for (String fieldName : new String[]{"recipeItems", "input", "inputs", "items",
                "field_77575_c", "field_77579_b"}) {
            rawInputs = ReflectionUtils.readField(irecipe, fieldName);
            if (rawInputs != null) break;
        }
        // Fallback: scan all declared fields for an array or List of ItemStack
        if (rawInputs == null) {
            for (java.lang.reflect.Field f : irecipe.getClass().getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isArray() && ft.getComponentType() != null
                        && ItemStack.class.isAssignableFrom(ft.getComponentType())) {
                    rawInputs = ReflectionUtils.readField(irecipe, f.getName());
                    break;
                }
                if (List.class.isAssignableFrom(ft)) {
                    rawInputs = ReflectionUtils.readField(irecipe, f.getName());
                    break;
                }
                if (ft.isArray() && ft.getComponentType() == Object.class) {
                    rawInputs = ReflectionUtils.readField(irecipe, f.getName());
                    break;
                }
            }
        }

        int slot = 1;
        if (rawInputs instanceof ItemStack[]) {
            for (ItemStack stack : (ItemStack[]) rawInputs) {
                if (stack != null && stack.getItem() != null) {
                    recipe.putInputItem(slot, stack, stackToKey(stack));
                    slot++;
                }
            }
        } else if (rawInputs instanceof List) {
            for (Object elem : (List<Object>) rawInputs) {
                if (elem instanceof ItemStack) {
                    ItemStack stack = (ItemStack) elem;
                    if (stack.getItem() != null) {
                        recipe.putInputItem(slot, stack, stackToKey(stack));
                        slot++;
                    }
                } else if (elem instanceof List) {
                    // OreDict entry: take first substitute
                    List<?> subList = (List<?>) elem;
                    if (!subList.isEmpty() && subList.get(0) instanceof ItemStack) {
                        ItemStack stack = (ItemStack) subList.get(0);
                        if (stack.getItem() != null) {
                            recipe.putInputItem(slot, stack, stackToKey(stack));
                            slot++;
                        }
                    }
                } else if (elem != null) {
                    // Try to unwrap a single ItemStack
                    ItemStack stack = tryExtractStack(elem);
                    if (stack != null && stack.getItem() != null) {
                        recipe.putInputItem(slot, stack, stackToKey(stack));
                        slot++;
                    }
                }
            }
        } else if (rawInputs instanceof Object[]) {
            for (Object elem : (Object[]) rawInputs) {
                if (elem instanceof ItemStack) {
                    ItemStack stack = (ItemStack) elem;
                    if (stack.getItem() != null) {
                        recipe.putInputItem(slot, stack, stackToKey(stack));
                        slot++;
                    }
                } else if (elem instanceof List) {
                    List<?> subList = (List<?>) elem;
                    if (!subList.isEmpty() && subList.get(0) instanceof ItemStack) {
                        ItemStack stack = (ItemStack) subList.get(0);
                        if (stack.getItem() != null) {
                            recipe.putInputItem(slot, stack, stackToKey(stack));
                            slot++;
                        }
                    }
                } else if (elem != null) {
                    ItemStack stack = tryExtractStack(elem);
                    if (stack != null && stack.getItem() != null) {
                        recipe.putInputItem(slot, stack, stackToKey(stack));
                        slot++;
                    }
                }
            }
        }

        // --- output ---
        recipe.putOutputItem(1, outStack, stackToKey(outStack));

        return recipe;
    }

    /** Try to extract an ItemStack from an arbitrary object via common field names. */
    private static ItemStack tryExtractStack(Object obj) {
        if (obj instanceof ItemStack) return (ItemStack) obj;
        for (String fn : new String[]{"item", "stack", "itemStack", "theItem"}) {
            Object val = ReflectionUtils.readField(obj, fn);
            if (val instanceof ItemStack) return (ItemStack) val;
        }
        return null;
    }

    // ========================================================================
    //  Strategy 2 — NEI handler extraction (on-demand loading)
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Set<Object> discoverHandlers() {
        Set<Object> handlers = new LinkedHashSet<Object>();
        for (String[] entry : HANDLER_REGISTRIES) {
            Object fieldVal = ReflectionUtils.readStaticField(entry[0], entry[1]);
            if (fieldVal instanceof Collection) {
                for (Object h : (Collection<Object>) fieldVal) {
                    if (h != null && hasRecipeInterface(h)) {
                        handlers.add(h);
                    }
                }
            }
        }
        return handlers;
    }

    private boolean hasRecipeInterface(Object obj) {
        for (Class<?> iface : getAllInterfaces(obj.getClass())) {
            if (iface.getName().equals("codechicken.nei.recipe.IRecipeHandler")) {
                return true;
            }
        }
        return false;
    }

    private Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> set = new LinkedHashSet<Class<?>>();
        while (clazz != null) {
            for (Class<?> iface : clazz.getInterfaces()) {
                set.add(iface);
                set.addAll(getAllInterfaces(iface));
            }
            clazz = clazz.getSuperclass();
        }
        return set;
    }

    @SuppressWarnings("unchecked")
    private List<ExportRecipe> extractFromHandler(Object handler) {
        List<ExportRecipe> out = new ArrayList<ExportRecipe>();

        // ---- Strategy A: handler.getCache() -----------------------------
        try {
            Object cache = ReflectionUtils.invokeNoArg(handler, "getCache");
            if (cache instanceof List && !((List<?>) cache).isEmpty()) {
                out.addAll(extractFromCachedRecipeList(handler, (List<Object>) cache));
                attachCatalysts(handler, out);
                return out;
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] Strategy A failed for "
                    + handler.getClass().getName() + ": " + e);
        }

        // ---- Strategy B: trigger loadCraftingRecipes(id, new Object[0]) -
        try {
            String handlerId = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_HANDLER_ID));
            if (handlerId == null) {
                handlerId = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_OVERLAY_ID));
            }
            if (handlerId == null) handlerId = handler.getClass().getName();

            Object[] emptyArr = new Object[0];
            ReflectionUtils.invokeStringObjectArray(
                    handler, "loadCraftingRecipes", handlerId, emptyArr);

            Object arecipes = ReflectionUtils.readField(handler, "arecipes");
            if (arecipes instanceof List && !((List<?>) arecipes).isEmpty()) {
                out.addAll(extractFromCachedRecipeList(handler, (List<Object>) arecipes));
                attachCatalysts(handler, out);
                return out;
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] Strategy B failed for "
                    + handler.getClass().getName() + ": " + e);
        }

        // ---- Strategy C: getRecipeHandler returns new instance ----------
        try {
            String handlerId2 = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_HANDLER_ID));
            if (handlerId2 == null) {
                handlerId2 = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_OVERLAY_ID));
            }
            if (handlerId2 == null) handlerId2 = handler.getClass().getName();

            Object[] emptyArr = new Object[0];
            Object loaded = ReflectionUtils.invokeStringObjectArray(
                    handler, METH_GET_RECIPE_HANDLER, handlerId2, emptyArr);
            if (loaded == null) {
                loaded = ReflectionUtils.invokeStringObjectArray(
                        handler, METH_GET_USAGE_HANDLER, handlerId2, emptyArr);
            }
            if (loaded != null) {
                Object loadedRecipes = ReflectionUtils.readField(loaded, "arecipes");
                if (loadedRecipes instanceof List && !((List<?>) loadedRecipes).isEmpty()) {
                    out.addAll(extractFromCachedRecipeList(loaded, (List<Object>) loadedRecipes));
                    attachCatalysts(handler, out);
                    return out;
                }
                Object loadedCache = ReflectionUtils.invokeNoArg(loaded, "getCache");
                if (loadedCache instanceof List && !((List<?>) loadedCache).isEmpty()) {
                    out.addAll(extractFromCachedRecipeList(loaded, (List<Object>) loadedCache));
                    attachCatalysts(handler, out);
                    return out;
                }
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] Strategy C failed for "
                    + handler.getClass().getName() + ": " + e);
        }

        // ---- Strategy D: scan for any non-empty recipe-like List field --
        try {
            for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("recipe") || fn.contains("cached") || fn.contains("list")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(handler);
                        if (val instanceof List && !((List<?>) val).isEmpty()) {
                            out.addAll(extractFromCachedRecipeList(handler, (List<Object>) val));
                            attachCatalysts(handler, out);
                            return out;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] Strategy D failed for "
                    + handler.getClass().getName() + ": " + e);
        }

        return out;
    }

    /** Attach catalysts (machine block IDs) to all recipes from a handler. */
    private void attachCatalysts(Object handler, List<ExportRecipe> recipes) {
        if (recipes.isEmpty()) return;
        List<String> catalystIds = getCatalystIds(handler);
        if (catalystIds.isEmpty()) return;
        for (ExportRecipe r : recipes) {
            r.putExtra("catalysts", catalystIds);
        }
    }

    /** Read catalyst items from NEI via reflection and convert to "modid:name@meta" strings. */
    @SuppressWarnings("unchecked")
    private List<String> getCatalystIds(Object handler) {
        List<String> out = new ArrayList<String>();
        try {
            Class<?> irecipeHandlerClass = Class.forName("codechicken.nei.recipe.IRecipeHandler");

            // RecipeCatalysts.getRecipeID(handler) → String
            String recipeId = strVal(ReflectionUtils.invokeStaticMethod(
                    "codechicken.nei.recipe.RecipeCatalysts", "getRecipeID",
                    new Class<?>[]{irecipeHandlerClass},
                    new Object[]{handler}));
            String ovId = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_OVERLAY_ID));
            System.out.println("[NEIExport] catalyst: recipeId=" + recipeId + " ovId=" + ovId);

            // RecipeCatalysts.getRecipeCatalysts(String) → List<PositionedStack>
            Object catList = ReflectionUtils.invokeStaticMethod(
                    "codechicken.nei.recipe.RecipeCatalysts", "getRecipeCatalysts",
                    new Class<?>[]{String.class},
                    new Object[]{recipeId});

            if (catList == null) {
                // try with handler object
                catList = ReflectionUtils.invokeStaticMethod(
                        "codechicken.nei.recipe.RecipeCatalysts", "getRecipeCatalysts",
                        new Class<?>[]{irecipeHandlerClass},
                        new Object[]{handler});
            }

            if (catList instanceof List) {
                List<?> catalysts = (List<?>) catList;
                System.out.println("[NEIExport] catalyst count=" + catalysts.size());
                for (Object ps : catalysts) {
                    if (ps == null) continue;
                    ItemStack stack = extractSingleStackFromPositioned(ps);
                    if (stack != null && stack.getItem() != null) {
                        out.add(stackToKey(stack) + "@" + stack.getItemDamage());
                    }
                }
            } else {
                System.out.println("[NEIExport] catalyst list is null or not a List");
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] Catalyst lookup failed for "
                    + handler.getClass().getName() + ": " + e);
            e.printStackTrace();
        }
        return out;
    }

    // ========================================================================
    //  CachedRecipe extraction
    // ========================================================================

    @SuppressWarnings("unchecked")
    private List<ExportRecipe> extractFromCachedRecipeList(
            Object handler, List<Object> cachedRecipes) {
        List<ExportRecipe> out = new ArrayList<ExportRecipe>();
        String handlerType = handlerType(handler);
        String handlerClassName = handler.getClass().getName();
        String handlerRecipeName = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_RECIPE_NAME));
        int idx = 0;

        for (Object cached : cachedRecipes) {
            if (cached == null) { idx++; continue; }
            try {
                // ---- Specialised path: GTNEIDefaultHandler$CachedDefaultRecipe
                // has a mRecipe field referencing the source GTRecipe with all
                // fluids / EU/t / duration data. Convert it directly.
                Object gtRecipe = ReflectionUtils.readField(cached, "mRecipe");
                if (gtRecipe != null && CLS_GT_RECIPE.equals(gtRecipe.getClass().getName())) {
                    ExportRecipe r = convertGTRecipe(gtRecipe,
                            handlerClassName, handlerRecipeName);
                    if (r != null) {
                        r.setType(handlerType);
                        r.putExtra("handler_class", handlerClassName);
                        out.add(r);
                    }
                    idx++;
                    continue;
                }

                ExportRecipe recipe = new ExportRecipe(handlerType,
                        handlerClassName + "/" + incrIndex(handlerClassName));

                // ---- Inputs via getIngredients() --------------------------
                List<ItemStack> ingList = new ArrayList<ItemStack>();
                Object ingRaw = ReflectionUtils.invokeNoArg(cached, "getIngredients");
                if (ingRaw instanceof List) {
                    for (Object ps : (List<Object>) ingRaw) {
                        ItemStack s = extractSingleStackFromPositioned(ps);
                        if (s != null) ingList.add(s);
                    }
                }
                int slot = 1;
                for (ItemStack stack : ingList) {
                    recipe.putInputItem(slot, stack, stackToKey(stack));
                    slot++;
                }

                // ---- Outputs: getResult() + getOtherStacks() --------------
                List<ItemStack> outList = new ArrayList<ItemStack>();
                Object resRaw = ReflectionUtils.invokeNoArg(cached, "getResult");
                ItemStack result = extractSingleStackFromPositioned(resRaw);
                if (result != null) outList.add(result);
                Object otherRaw = ReflectionUtils.invokeNoArg(cached, "getOtherStacks");
                if (otherRaw instanceof List) {
                    for (Object ps : (List<Object>) otherRaw) {
                        ItemStack s = extractSingleStackFromPositioned(ps);
                        if (s != null) outList.add(s);
                    }
                }
                slot = 1;
                for (ItemStack stack : outList) {
                    recipe.putOutputItem(slot, stack, stackToKey(stack));
                    slot++;
                }

                String name = handlerRecipeName;
                if (name == null || name.isEmpty()) {
                    if (!outList.isEmpty() && outList.get(0) != null) {
                        name = stripFormat(outList.get(0).getDisplayName());
                    }
                    if (name == null || name.isEmpty()) {
                        name = handlerClassName + "/" + idx;
                    }
                }
                recipe.setName(name);
                recipe.putExtra("handler_class", handlerClassName);
                idx++;

                if (!recipe.getInput().isEmpty() || !recipe.getOutput().isEmpty()) {
                    out.add(recipe);
                }
            } catch (Exception e) {
                // Single bad recipe (e.g. Forestry null species) must not crash
                // the entire handler extraction.
                idx++;
            }
        }
        return out;
    }

    private ItemStack extractSingleStackFromPositioned(Object positioned) {
        if (positioned == null) return null;
        Object item = ReflectionUtils.readField(positioned, "item");
        if (item instanceof ItemStack) return ((ItemStack) item).copy();
        Object itemsObj = ReflectionUtils.readField(positioned, "items");
        if (itemsObj instanceof ItemStack[]) {
            for (ItemStack s : (ItemStack[]) itemsObj) {
                if (s != null) return s.copy();
            }
        }
        return null;
    }

    // ========================================================================
    //  Utilities
    // ========================================================================

    /** Deduplicate by input/output fingerprint. Keep first occurrence but merge
     *  catalysts / machine / handler_class from later duplicates. */
    private static void addDeduped(List<ExportRecipe> dest, Set<String> seen,
                                    List<ExportRecipe> candidates) {
        // Map fingerprint to recipe in dest list for fast merge
        java.util.LinkedHashMap<String, ExportRecipe> seenMap =
                new java.util.LinkedHashMap<String, ExportRecipe>();
        // Rebuild seenMap from dest
        for (int i = 0; i < dest.size(); i++) {
            ExportRecipe r = dest.get(i);
            String fp = recipeFingerprint(r);
            seenMap.put(fp, r);
        }
        for (ExportRecipe r : candidates) {
            String fp = recipeFingerprint(r);
            ExportRecipe existing = seenMap.get(fp);
            if (existing != null) {
                // Merge extra fields from duplicate into existing
                mergeFields(existing, r);
            } else {
                seenMap.put(fp, r);
                dest.add(r);
                seen.add(fp);
            }
        }
    }

    /** Copy catalysts / machine / handler_class from source to target if target lacks them. */
    private static void mergeFields(ExportRecipe target, ExportRecipe source) {
        if ((!target.getExtra().containsKey("catalysts") || target.getExtra().get("catalysts") == null)
                && source.getExtra().get("catalysts") != null) {
            target.putExtra("catalysts", source.getExtra().get("catalysts"));
        }
        if ((!target.getExtra().containsKey("machine") || target.getExtra().get("machine") == null)
                && source.getExtra().get("machine") != null) {
            target.putExtra("machine", source.getExtra().get("machine"));
            // Also improve the name if it was just a recipe ID
            String srcMachine = source.getExtra().get("machine").toString();
            if (srcMachine != null && !srcMachine.isEmpty()) {
                String curName = target.getName();
                if (curName != null && curName.contains("#") && !curName.contains("→")) {
                    target.setName(srcMachine + " → " + curName.substring(curName.indexOf("#") + 1));
                }
            }
        }
        if ((!target.getExtra().containsKey("handler_class") || target.getExtra().get("handler_class") == null)
                && source.getExtra().get("handler_class") != null) {
            target.putExtra("handler_class", source.getExtra().get("handler_class"));
        }
    }

    /** Stable fingerprint: type + sorted input ids + sorted output ids. */
    private static String recipeFingerprint(ExportRecipe r) {
        StringBuilder sb = new StringBuilder(r.getType());
        TreeSet<String> ins = new TreeSet<String>();
        for (Map.Entry<String, Map<String, String>> e : r.getInput().entrySet()) {
            Map<String, String> v = e.getValue();
            ins.add(v.get("id") + "@" + v.get("count") + "@" + v.get("meta"));
        }
        for (String s : ins) { sb.append('|').append(s); }
        sb.append("->");
        TreeSet<String> outs = new TreeSet<String>();
        for (Map.Entry<String, Map<String, String>> e : r.getOutput().entrySet()) {
            Map<String, String> v = e.getValue();
            outs.add(v.get("id") + "@" + v.get("count") + "@" + v.get("meta"));
        }
        for (String s : outs) { sb.append('|').append(s); }
        return sb.toString();
    }

    private String handlerType(Object handler) {
        String simpleName = handler.getClass().getSimpleName();
        return typeMapper.map(handler.getClass().getName(), simpleName);
    }

    /** Return a stable 1-based index for recipes within a given map/type. */
    private int incrIndex(String key) {
        Integer cur = recipeIndexByMap.get(key);
        int next = (cur == null) ? 1 : cur.intValue() + 1;
        recipeIndexByMap.put(key, next);
        return next;
    }

    private String stackToKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "minecraft:air";
        Item item = stack.getItem();
        GameRegistry.UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(item);
        if (id == null) {
            String unl = item.getUnlocalizedName();
            if (unl == null) return "unknown:unknown";
            return "unknown:" + unl.replace("item.", "").replace("tile.", "");
        }
        return id.modId + ":" + id.name;
    }

    private static String strVal(Object o) {
        return o != null ? o.toString() : null;
    }

    /** Read fluid name from a FluidStack via getFluid().getName(). */
    private static String fluidName(Object fluidStack) {
        Object fluid = ReflectionUtils.invokeNoArg(fluidStack, "getFluid");
        if (fluid == null) return null;
        Object nm = ReflectionUtils.invokeNoArg(fluid, "getName");
        return nm != null ? nm.toString() : null;
    }

    /** Read localized display name from a FluidStack (e.g. "熔融红石合金"). */
    private static String fluidDisplayName(Object fluidStack) {
        Object fluid = ReflectionUtils.invokeNoArg(fluidStack, "getFluid");
        if (fluid == null) return null;
        Object nm = ReflectionUtils.invokeNoArg(fluid, "getLocalizedName");
        if (nm != null) {
            String s = nm.toString();
            if (s != null && !s.isEmpty()) return s.replaceAll("\\u00a7.", "");
        }
        return null;
    }

    private static int[] readIntArrayField(Object target, String fieldName) {
        Object obj = ReflectionUtils.readField(target, fieldName);
        if (obj instanceof int[]) return (int[]) obj;
        return null;
    }

    /** Write probability (0-10000 = 0%-100%) to the slot entry. Only called if < 10000. */
    private static void setItemChance(Map<String, Map<String, String>> map, int slot, int chance) {
        Map<String, String> entry = map.get(String.valueOf(slot));
        if (entry != null) {
            double pct = chance / 100.0;
            entry.put("chance", String.format("%.1f%%", pct));
        }
    }

    private static String stripFormat(String s) {
        return s != null ? s.replaceAll("\\u00a7.", "") : null;
    }
}
