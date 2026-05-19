package io.gtnh.neidump.model;

import net.minecraft.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExportRecipe {
    private String type;
    private String name;
    private final Map<String, Map<String, String>> input = new LinkedHashMap<String, Map<String, String>>();
    private final Map<String, Map<String, String>> output = new LinkedHashMap<String, Map<String, String>>();
    private final Map<String, Object> extra = new LinkedHashMap<String, Object>();

    public ExportRecipe(String type, String name) {
        this.type = type;
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Map<String, String>> getInput() {
        return input;
    }

    public Map<String, Map<String, String>> getOutput() {
        return output;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void putExtra(String key, Object value) {
        if (key == null || key.isEmpty() || value == null) {
            return;
        }
        extra.put(key, value);
    }

    public void putInput(int slot, String itemName, int count, int meta) {
        input.put(String.valueOf(slot), makeItem(itemName, count, meta));
    }

    public void putOutput(int slot, String itemName, int count, int meta) {
        output.put(String.valueOf(slot), makeItem(itemName, count, meta));
    }

    /** Store an ItemStack with both registry ID and human-readable display name. */
    public void putInputItem(int slot, ItemStack stack, String registryId) {
        input.put(String.valueOf(slot), makeItemFull(registryId, stack));
    }

    /** Store an ItemStack with both registry ID and human-readable display name. */
    public void putOutputItem(int slot, ItemStack stack, String registryId) {
        output.put(String.valueOf(slot), makeItemFull(registryId, stack));
    }

    /** Store a fluid ingredient — same keys as items (count=mB, meta=0). */
    public void putFluidInput(int slot, String fluidId, String displayName, int amountMb) {
        Map<String, String> entry = new LinkedHashMap<String, String>();
        entry.put("id", fluidId);
        entry.put("count", String.valueOf(amountMb));
        entry.put("meta", "0");
        if (displayName != null && !displayName.isEmpty()) {
            entry.put("display", displayName);
        }
        input.put(String.valueOf(slot), entry);
    }

    /** Store a fluid product — same keys as items (count=mB, meta=0). */
    public void putFluidOutput(int slot, String fluidId, String displayName, int amountMb) {
        Map<String, String> entry = new LinkedHashMap<String, String>();
        entry.put("id", fluidId);
        entry.put("count", String.valueOf(amountMb));
        entry.put("meta", "0");
        if (displayName != null && !displayName.isEmpty()) {
            entry.put("display", displayName);
        }
        output.put(String.valueOf(slot), entry);
    }

    private static Map<String, String> makeItem(String itemName, int count, int meta) {
        Map<String, String> item = new LinkedHashMap<String, String>();
        item.put("id", itemName);
        item.put("count", String.valueOf(count));
        item.put("meta", String.valueOf(meta));
        return item;
    }

    private static Map<String, String> makeItemFull(String registryId, ItemStack stack) {
        Map<String, String> item = new LinkedHashMap<String, String>();
        item.put("id", registryId);
        item.put("count", String.valueOf(stack.stackSize));
        item.put("meta", String.valueOf(stack.getItemDamage()));
        // Human-readable display name (localised, e.g. Chinese in GTNH)
        String display = stack.getDisplayName();
        if (display != null && !display.isEmpty()) {
            // strip Minecraft formatting codes (§x)
            display = display.replaceAll("\\u00a7.", "");
            item.put("display", display);
        }
        // NBT tag (for items with non-empty NBT, e.g. enchanted books, circuit configs)
        if (stack.stackTagCompound != null && !stack.stackTagCompound.hasNoTags()) {
            item.put("nbt", stack.stackTagCompound.toString());
        }
        return item;
    }
}
