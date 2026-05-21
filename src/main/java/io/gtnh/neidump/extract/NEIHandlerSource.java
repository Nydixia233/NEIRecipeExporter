package io.gtnh.neidump.extract;

import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.ReflectionUtils;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.*;

/**
 * Strategy 2: Extract recipes from NEI handlers via reflection.
 * Discovers all registered IRecipeHandler instances, triggers on-demand recipe
 * loading, and converts cached/positioned item stacks.
 */
public class NEIHandlerSource implements IRecipeSource {

    private static final String[][] HANDLER_REGISTRIES = {
        {"codechicken.nei.recipe.GuiCraftingRecipe", "craftinghandlers"},
        {"codechicken.nei.recipe.GuiCraftingRecipe", "serialCraftingHandlers"},
        {"codechicken.nei.recipe.GuiUsageRecipe",    "usagehandlers"},
        {"codechicken.nei.recipe.GuiUsageRecipe",    "serialUsageHandlers"},
    };

    private static final String METH_GET_HANDLER_ID    = "getHandlerId";
    private static final String METH_GET_RECIPE_HANDLER = "getRecipeHandler";
    private static final String METH_GET_USAGE_HANDLER  = "getUsageHandler";
    private static final String METH_GET_RECIPE_NAME    = "getRecipeName";
    private static final String METH_GET_OVERLAY_ID     = "getOverlayIdentifier";
    private static final String CLS_GT_RECIPE = "gregtech.api.util.GTRecipe";

    /** Cached handler discovery so repeat calls are free within a single extraction. */
    private static Set<Object> cachedHandlers;
    /** Per-handler crashes within {@link #extractFromHandler} during the most recent run. */
    private static int failedHandlerCount;

    @Override
    public String getName() { return "NEI Handlers"; }

    @Override
    public List<ExportRecipe> extract(NEIRecipeExtractor context) {
        List<ExportRecipe> out = new ArrayList<>();
        Set<Object> handlers = discoverHandlers();
        for (Object handler : handlers) {
            try {
                out.addAll(extractFromHandler(handler, context));
            } catch (Exception e) {
                failedHandlerCount++;
                /* per-handler safety */
            }
        }
        return out;
    }

    /** Reset cached handler set + failure counter. Call before a fresh extraction. */
    public static void resetHandlerCache() {
        cachedHandlers = null;
        failedHandlerCount = 0;
    }

    /** How many handlers crashed during the most recent {@link #extract} call. */
    public static int getFailedHandlerCount() {
        return failedHandlerCount;
    }

    /** Discover all registered IRecipeHandler instances (cached per run). */
    @SuppressWarnings("unchecked")
    public static Set<Object> discoverHandlers() {
        if (cachedHandlers != null) return cachedHandlers;
        Set<Object> handlers = new LinkedHashSet<>();
        for (String[] entry : HANDLER_REGISTRIES) {
            Object fieldVal = ReflectionUtils.readStaticField(entry[0], entry[1]);
            if (fieldVal instanceof Collection) {
                for (Object h : (Collection<Object>) fieldVal) {
                    if (h != null && hasRecipeInterface(h)) handlers.add(h);
                }
            }
        }
        cachedHandlers = handlers;
        return handlers;
    }

    private static boolean hasRecipeInterface(Object obj) {
        for (Class<?> iface : getAllInterfaces(obj.getClass())) {
            if (iface.getName().equals("codechicken.nei.recipe.IRecipeHandler")) return true;
        }
        return false;
    }

    private static Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> set = new LinkedHashSet<>();
        while (clazz != null) {
            for (Class<?> iface : clazz.getInterfaces()) {
                set.add(iface);
                set.addAll(getAllInterfaces(iface));
            }
            clazz = clazz.getSuperclass();
        }
        return set;
    }

    // ---- handler extraction ----

    @SuppressWarnings("unchecked")
    private List<ExportRecipe> extractFromHandler(Object handler, NEIRecipeExtractor context) {
        List<ExportRecipe> out = new ArrayList<>();

        // Strategy A: getCache()
        try {
            Object cache = ReflectionUtils.invokeNoArg(handler, "getCache");
            if (cache instanceof List && !((List<?>) cache).isEmpty()) {
                out.addAll(extractFromCachedRecipeList(handler, (List<Object>) cache, context));
                attachCatalysts(handler, out, context);
                return out;
            }
        } catch (Exception e) { System.err.println("[NEIExport] Strategy A failed for " + handler.getClass().getName() + ": " + e); }

        // Strategy B: loadCraftingRecipes
        try {
            String handlerId = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_HANDLER_ID));
            if (handlerId == null) handlerId = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_OVERLAY_ID));
            if (handlerId == null) handlerId = handler.getClass().getName();
            Object[] emptyArr = new Object[0];
            ReflectionUtils.invokeStringObjectArray(handler, "loadCraftingRecipes", handlerId, emptyArr);
            Object arecipes = ReflectionUtils.readField(handler, "arecipes");
            if (arecipes instanceof List && !((List<?>) arecipes).isEmpty()) {
                out.addAll(extractFromCachedRecipeList(handler, (List<Object>) arecipes, context));
                attachCatalysts(handler, out, context);
                return out;
            }
        } catch (Exception e) { System.err.println("[NEIExport] Strategy B failed for " + handler.getClass().getName() + ": " + e); }

        // Strategy C: getRecipeHandler / getUsageHandler
        try {
            String handlerId2 = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_HANDLER_ID));
            if (handlerId2 == null) handlerId2 = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_OVERLAY_ID));
            if (handlerId2 == null) handlerId2 = handler.getClass().getName();
            Object[] emptyArr = new Object[0];
            Object loaded = ReflectionUtils.invokeStringObjectArray(handler, METH_GET_RECIPE_HANDLER, handlerId2, emptyArr);
            if (loaded == null)
                loaded = ReflectionUtils.invokeStringObjectArray(handler, METH_GET_USAGE_HANDLER, handlerId2, emptyArr);
            if (loaded != null) {
                Object loadedRecipes = ReflectionUtils.readField(loaded, "arecipes");
                if (loadedRecipes instanceof List && !((List<?>) loadedRecipes).isEmpty()) {
                    out.addAll(extractFromCachedRecipeList(loaded, (List<Object>) loadedRecipes, context));
                    attachCatalysts(handler, out, context);
                    return out;
                }
                Object loadedCache = ReflectionUtils.invokeNoArg(loaded, "getCache");
                if (loadedCache instanceof List && !((List<?>) loadedCache).isEmpty()) {
                    out.addAll(extractFromCachedRecipeList(loaded, (List<Object>) loadedCache, context));
                    attachCatalysts(handler, out, context);
                    return out;
                }
            }
        } catch (Exception e) { System.err.println("[NEIExport] Strategy C failed for " + handler.getClass().getName() + ": " + e); }

        // Strategy D: scan recipe-like fields
        try {
            for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
                String fn = f.getName().toLowerCase();
                if (fn.contains("recipe") || fn.contains("cached") || fn.contains("list")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(handler);
                        if (val instanceof List && !((List<?>) val).isEmpty()) {
                            out.addAll(extractFromCachedRecipeList(handler, (List<Object>) val, context));
                            attachCatalysts(handler, out, context);
                            return out;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { System.err.println("[NEIExport] Strategy D failed for " + handler.getClass().getName() + ": " + e); }

        return out;
    }

    // ---- cached recipe extraction ----

    @SuppressWarnings("unchecked")
    private List<ExportRecipe> extractFromCachedRecipeList(
            Object handler, List<Object> cachedRecipes, NEIRecipeExtractor context) {
        List<ExportRecipe> out = new ArrayList<>();
        String handlerType = context.handlerType(handler);
        String handlerClassName = handler.getClass().getName();
        String handlerRecipeName = strVal(ReflectionUtils.invokeNoArg(handler, METH_GET_RECIPE_NAME));
        int idx = 0;

        for (Object cached : cachedRecipes) {
            if (cached == null) { idx++; continue; }
            try {
                // GTNEIDefaultHandler$CachedDefaultRecipe has mRecipe → use GT path
                Object gtRecipe = ReflectionUtils.readField(cached, "mRecipe");
                if (gtRecipe != null && CLS_GT_RECIPE.equals(gtRecipe.getClass().getName())) {
                    ExportRecipe r = GTRecipeMapSource.convertGTRecipe(gtRecipe, handlerClassName, handlerRecipeName, context);
                    if (r != null) {
                        r.setType(handlerType);
                        r.putExtra("handler_class", handlerClassName);
                        out.add(r);
                    }
                    idx++;
                    continue;
                }

                ExportRecipe recipe = new ExportRecipe(handlerType, handlerClassName + "/" + context.incrIndex(handlerClassName));

                // Inputs
                List<ItemStack> ingList = new ArrayList<>();
                Object ingRaw = ReflectionUtils.invokeNoArg(cached, "getIngredients");
                if (ingRaw instanceof List) {
                    for (Object ps : (List<Object>) ingRaw) {
                        ItemStack s = extractSingleStackFromPositioned(ps);
                        if (s != null) ingList.add(s);
                    }
                }
                int slot = 1;
                for (ItemStack stack : ingList) {
                    FluidStack fs = tryDecodeFluidDisplay(stack);
                    if (fs != null && fs.getFluid() != null) {
                        recipe.putFluidInput(slot, fs.getFluid().getName(),
                                stripFormat(fs.getLocalizedName()), fs.amount);
                    } else {
                        recipe.putInputItem(slot, stack, context.stackToKey(stack));
                    }
                    slot++;
                }

                // Outputs
                List<ItemStack> outList = new ArrayList<>();
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
                    FluidStack fs = tryDecodeFluidDisplay(stack);
                    if (fs != null && fs.getFluid() != null) {
                        recipe.putFluidOutput(slot, fs.getFluid().getName(),
                                stripFormat(fs.getLocalizedName()), fs.amount);
                    } else {
                        recipe.putOutputItem(slot, stack, context.stackToKey(stack));
                    }
                    slot++;
                }

                String name = handlerRecipeName;
                if (name == null || name.isEmpty()) {
                    if (!outList.isEmpty() && outList.get(0) != null)
                        name = context.stripFormat(outList.get(0).getDisplayName());
                    if (name == null || name.isEmpty()) name = handlerClassName + "/" + idx;
                }
                recipe.setName(name);
                recipe.putExtra("handler_class", handlerClassName);
                idx++;

                if (!recipe.getInput().isEmpty() || !recipe.getOutput().isEmpty()) out.add(recipe);
            } catch (Exception e) { idx++; /* per-recipe safety */ }
        }
        return out;
    }

    // ---- catalysts ----

    private void attachCatalysts(Object handler, List<ExportRecipe> recipes, NEIRecipeExtractor context) {
        if (recipes.isEmpty()) return;
        List<String> catalystIds = getCatalystIds(handler, context);
        if (catalystIds.isEmpty()) return;
        for (ExportRecipe r : recipes) r.putExtra("catalysts", new ArrayList<>(catalystIds));
    }

    @SuppressWarnings("unchecked")
    private List<String> getCatalystIds(Object handler, NEIRecipeExtractor context) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> irecipeHandlerClass = Class.forName("codechicken.nei.recipe.IRecipeHandler");
            String recipeId = strVal(ReflectionUtils.invokeStaticMethod(
                    "codechicken.nei.recipe.RecipeCatalysts", "getRecipeID",
                    new Class<?>[]{irecipeHandlerClass}, new Object[]{handler}));
            Object catList = ReflectionUtils.invokeStaticMethod(
                    "codechicken.nei.recipe.RecipeCatalysts", "getRecipeCatalysts",
                    new Class<?>[]{String.class}, new Object[]{recipeId});
            if (catList == null)
                catList = ReflectionUtils.invokeStaticMethod(
                        "codechicken.nei.recipe.RecipeCatalysts", "getRecipeCatalysts",
                        new Class<?>[]{irecipeHandlerClass}, new Object[]{handler});
            if (catList instanceof List) {
                for (Object ps : (List<?>) catList) {
                    if (ps == null) continue;
                    ItemStack stack = extractSingleStackFromPositioned(ps);
                    if (stack != null && stack.getItem() != null)
                        out.add(context.stackToKey(stack) + "@" + stack.getItemDamage());
                }
            }
        } catch (Exception e) {
            System.err.println("[NEIExport] Catalyst lookup failed for " + handler.getClass().getName() + ": " + e);
        }
        return out;
    }

    // ---- helpers ----

    static ItemStack extractSingleStackFromPositioned(Object positioned) {
        if (positioned == null) return null;
        Object item = ReflectionUtils.readField(positioned, "item");
        if (item instanceof ItemStack) return ((ItemStack) item).copy();
        Object itemsObj = ReflectionUtils.readField(positioned, "items");
        if (itemsObj instanceof ItemStack[]) {
            for (ItemStack s : (ItemStack[]) itemsObj) if (s != null) return s.copy();
        }
        return null;
    }

    /**
     * If {@code stack} is GT's {@code gregtech:gt.GregTech_FluidDisplay} item,
     * decode it back into a {@link FluidStack} via NEI's
     * {@code codechicken.nei.recipe.StackInfo.getFluid(ItemStack)} reverse-lookup.
     * Otherwise return null.
     *
     * <p>GT-family handlers (BioLab, BioVat, GT++, Bartworks) wrap fluids as
     * display items so they fit through the {@code PositionedStack} pipeline;
     * without this decode the dump treats those slots as items, which the
     * downstream consumer can't classify as fluid.
     */
    private static FluidStack tryDecodeFluidDisplay(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        Object regName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        if (!"gregtech:gt.GregTech_FluidDisplay".equals(String.valueOf(regName))) return null;
        try {
            Object fs = ReflectionUtils.invokeStaticMethod(
                    "codechicken.nei.recipe.StackInfo", "getFluid",
                    new Class<?>[]{ItemStack.class}, new Object[]{stack});
            return fs instanceof FluidStack ? (FluidStack) fs : null;
        } catch (Exception e) { return null; }
    }

    private static String stripFormat(String s) {
        return s == null ? null : s.replaceAll("\\u00a7.", "");
    }

    private static String strVal(Object o) { return o != null ? o.toString() : null; }
}
