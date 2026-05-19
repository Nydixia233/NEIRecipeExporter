package io.gtnh.neidump.extract;

import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.ReflectionUtils;
import net.minecraft.item.ItemStack;

import java.util.*;

/**
 * Strategy 1.5: Extract vanilla / Forge crafting recipes from {@code CraftingManager}.
 */
public class CraftingManagerSource implements IRecipeSource {

    private static final String CLS_CRAFTING_MANAGER = "net.minecraft.item.crafting.CraftingManager";
    /** Cache first empty-input recipe class name for one-time diagnostic. */
    private static String irecipeDiagnostic = null;

    @Override
    public String getName() { return "CraftingManager"; }

    @Override
    @SuppressWarnings("unchecked")
    public List<ExportRecipe> extract(NEIRecipeExtractor context) {
        List<ExportRecipe> out = new ArrayList<>();

        Object craftMgrInstance = null;
        try {
            ClassLoader launchCl = ItemStack.class.getClassLoader();
            Class<?> cmClass = Class.forName(CLS_CRAFTING_MANAGER, false, launchCl);

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
            for (java.lang.reflect.Field f : craftMgrInstance.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(craftMgrInstance);
                        if (val instanceof List) { recipesObj = val; break; }
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
                ExportRecipe exp = convertIRecipe(irecipe, context);
                if (exp != null) { out.add(exp); accepted++; }
            } catch (Exception e) {
                if (accepted < 1) {
                    System.err.println("[NEIExport] First IRecipe conversion failed: "
                            + irecipe.getClass().getName() + " - " + e);
                }
            }
        }

        System.out.println("[NEIExport] CraftingManager: raw=" + recipes.size() + " accepted=" + accepted);
        return out;
    }

    static ExportRecipe convertIRecipe(Object irecipe, NEIRecipeExtractor context) {
        // Output: try MCP + SRG method names
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
        if (className.contains("Shapeless") || className.contains("shapeless"))
            type = "minecraft:crafting_shapeless";
        else if (className.contains("Shaped") || className.contains("shaped"))
            type = "minecraft:crafting_shaped";
        else type = "minecraft:crafting";

        String displayName = context.stripFormat(outStack.getDisplayName());
        String recipeId = "crafting:" + context.stackToKey(outStack) + "@" + outStack.getItemDamage();
        ExportRecipe recipe = new ExportRecipe(type, displayName != null ? displayName : recipeId);
        recipe.putExtra("recipe_class", className);

        // Inputs: try MCP, SRG, then scan all fields
        Object rawInputs = null;
        for (String fn : new String[]{"recipeItems", "input", "inputs", "items",
                "field_77575_c", "field_77579_b"}) {
            rawInputs = ReflectionUtils.readField(irecipe, fn);
            if (rawInputs != null) break;
        }
        if (rawInputs == null) {
            for (java.lang.reflect.Field f : irecipe.getClass().getDeclaredFields()) {
                Class<?> ft = f.getType();
                if ((ft.isArray() && ft.getComponentType() != null
                        && ItemStack.class.isAssignableFrom(ft.getComponentType()))
                        || List.class.isAssignableFrom(ft)
                        || (ft.isArray() && ft.getComponentType() == Object.class)) {
                    rawInputs = ReflectionUtils.readField(irecipe, f.getName());
                    break;
                }
            }
        }

        // Diagnostic: if inputs still null, dump class structure for first occurrence
        if (rawInputs == null && irecipeDiagnostic == null) {
            irecipeDiagnostic = irecipe.getClass().getName();
            System.out.println("[NEIExport] Empty-input recipe class: " + irecipeDiagnostic);
            for (java.lang.reflect.Field f : irecipe.getClass().getDeclaredFields()) {
                System.out.println("[NEIExport]   field: " + f.getName() + " (" + f.getType().getSimpleName() + ")");
            }
            for (java.lang.reflect.Method m : irecipe.getClass().getMethods()) {
                if (m.getParameterTypes().length <= 1 && m.getReturnType() != void.class
                        && !m.getName().equals("equals") && !m.getName().equals("hashCode")
                        && !m.getName().equals("toString") && !m.getName().equals("getClass"))
                    System.out.println("[NEIExport]   method: " + m.getReturnType().getSimpleName()
                            + " " + m.getName() + "(" + m.getParameterTypes().length + ")");
            }
        }

        int slot = 1;
        if (rawInputs instanceof ItemStack[]) {
            for (ItemStack stack : (ItemStack[]) rawInputs) {
                if (stack != null && stack.getItem() != null) {
                    recipe.putInputItem(slot, stack, context.stackToKey(stack));
                    slot++;
                }
            }
        } else if (rawInputs instanceof List) {
            for (Object elem : (List<?>) rawInputs) {
                if (elem instanceof ItemStack) {
                    ItemStack s = (ItemStack) elem;
                    if (s.getItem() != null) { recipe.putInputItem(slot, s, context.stackToKey(s)); slot++; }
                } else if (elem instanceof List) {
                    List<?> sub = (List<?>) elem;
                    if (!sub.isEmpty() && sub.get(0) instanceof ItemStack) {
                        ItemStack s = (ItemStack) sub.get(0);
                        if (s.getItem() != null) { recipe.putInputItem(slot, s, context.stackToKey(s)); slot++; }
                    }
                } else if (elem != null) {
                    ItemStack s = tryExtractStack(elem);
                    if (s != null && s.getItem() != null) { recipe.putInputItem(slot, s, context.stackToKey(s)); slot++; }
                }
            }
        } else if (rawInputs instanceof Object[]) {
            for (Object elem : (Object[]) rawInputs) {
                if (elem instanceof ItemStack) {
                    ItemStack s = (ItemStack) elem;
                    if (s.getItem() != null) { recipe.putInputItem(slot, s, context.stackToKey(s)); slot++; }
                } else if (elem instanceof List) {
                    List<?> sub = (List<?>) elem;
                    if (!sub.isEmpty() && sub.get(0) instanceof ItemStack) {
                        ItemStack s = (ItemStack) sub.get(0);
                        if (s.getItem() != null) { recipe.putInputItem(slot, s, context.stackToKey(s)); slot++; }
                    }
                } else if (elem != null) {
                    ItemStack s = tryExtractStack(elem);
                    if (s != null && s.getItem() != null) { recipe.putInputItem(slot, s, context.stackToKey(s)); slot++; }
                }
            }
        }

        recipe.putOutputItem(1, outStack, context.stackToKey(outStack));
        return recipe;
    }

    private static ItemStack tryExtractStack(Object obj) {
        if (obj instanceof ItemStack) return (ItemStack) obj;
        for (String fn : new String[]{"item", "stack", "itemStack", "theItem"}) {
            Object val = ReflectionUtils.readField(obj, fn);
            if (val instanceof ItemStack) return (ItemStack) val;
        }
        return null;
    }
}
