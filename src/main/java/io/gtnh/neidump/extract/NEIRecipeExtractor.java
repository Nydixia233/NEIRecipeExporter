package io.gtnh.neidump.extract;

import cpw.mods.fml.common.registry.GameRegistry;
import io.gtnh.neidump.model.ExportRecipe;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.*;

/**
 * Orchestrates recipe extraction from multiple sources, deduplicates, and merges.
 *
 * <p>Sources (in priority order — first occurrence wins after dedup):
 * <ol>
 *   <li>{@link GTRecipeMapSource} — GregTech {@code RecipeMap.ALL_RECIPE_MAPS}</li>
 *   <li>{@link CraftingManagerSource} — vanilla / Forge {@code CraftingManager}</li>
 *   <li>{@link NEIHandlerSource} — NEI {@code IRecipeHandler} instances (adds catalysts/machine names)</li>
 * </ol>
 */
public class NEIRecipeExtractor {

    // ---- strategy instances ----
    private final List<IRecipeSource> sources = Arrays.asList(
            new GTRecipeMapSource(),
            new CraftingManagerSource(),
            new NEIHandlerSource()
    );

    // ---- shared state ----
    private final HandlerTypeMapper typeMapper = new HandlerTypeMapper();
    private final Map<String, Integer> recipeIndexByMap = new LinkedHashMap<>();
    private final Set<String> seenFingerprints = new LinkedHashSet<>();

    // ---- result container ----
    public static final class ExtractionResult {
        public final List<ExportRecipe> recipes;
        public final int handlersSeen;
        public final int handlersFailed;
        public final Map<String, String> failedHandlerReasons;
        public final List<String> discoveredHandlers;
        public final Map<String, Integer> typeCounts;

        public ExtractionResult(List<ExportRecipe> recipes, int handlersSeen, int handlersFailed,
                                Map<String, String> failedHandlerReasons, List<String> discoveredHandlers,
                                Map<String, Integer> typeCounts) {
            this.recipes = recipes;
            this.handlersSeen = handlersSeen;
            this.handlersFailed = handlersFailed;
            this.failedHandlerReasons = failedHandlerReasons;
            this.discoveredHandlers = discoveredHandlers;
            this.typeCounts = typeCounts;
        }
    }

    // ========================================================================
    //  Public entry point
    // ========================================================================

    public ExtractionResult extractAll() {
        recipeIndexByMap.clear();
        seenFingerprints.clear();
        List<ExportRecipe> exported = new ArrayList<>();
        Map<String, String> failedReasons = new LinkedHashMap<>();
        List<String> discovered = new ArrayList<>();
        int failed = 0;

        // ---- Run each source ----
        for (IRecipeSource source : sources) {
            try {
                int before = exported.size();
                List<ExportRecipe> recs = source.extract(this);
                MergeDedup.addDeduped(exported, seenFingerprints, recs);
                System.out.println("[NEIExport] " + source.getName() + " strategy added "
                        + (exported.size() - before) + " recipes");
            } catch (Throwable e) {
                System.err.println("[NEIExport] " + source.getName() + " extraction failed: " + e);
                e.printStackTrace();
            }
        }

        // NEIHandlerSource internally discovers handlers; just count them.
        int handlerCount = NEIHandlerSource.discoverHandlers().size();

        // Type counts for report
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (ExportRecipe r : exported) {
            String t = r.getType();
            typeCounts.put(t, typeCounts.getOrDefault(t, 0) + 1);
        }

        return new ExtractionResult(exported, handlerCount, failed, failedReasons,
                new ArrayList<String>(), typeCounts);
    }

    // ========================================================================
    //  Shared utilities (public for strategy classes)
    // ========================================================================

    /** Return a stable 1-based index for recipes within a given map/type. */
    public int incrIndex(String key) {
        Integer cur = recipeIndexByMap.get(key);
        int next = (cur == null) ? 1 : cur.intValue() + 1;
        recipeIndexByMap.put(key, next);
        return next;
    }

    /** Map a handler object to its recipe type string. */
    public String handlerType(Object handler) {
        String simpleName = handler.getClass().getSimpleName();
        return typeMapper.map(handler.getClass().getName(), simpleName);
    }

    /** Convert an ItemStack to a "modid:name" registry key. */
    public String stackToKey(ItemStack stack) {
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

    /** Strip Minecraft formatting codes (§x) from a string. */
    public String stripFormat(String s) {
        return s != null ? s.replaceAll("\\u00a7.", "") : null;
    }
}
