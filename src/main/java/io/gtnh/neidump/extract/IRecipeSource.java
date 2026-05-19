package io.gtnh.neidump.extract;

import io.gtnh.neidump.model.ExportRecipe;

import java.util.List;

/**
 * A source of recipes that can be extracted from the game.
 * Each strategy (GT recipe maps, CraftingManager, NEI handlers) implements this.
 */
public interface IRecipeSource {
    /** Human-readable name for log messages (e.g. "GregTech", "CraftingManager"). */
    String getName();

    /**
     * Extract recipes from this source.
     * @return list of recipes (may contain duplicates across sources; dedup is handled by the orchestrator)
     */
    List<ExportRecipe> extract(NEIRecipeExtractor context);
}
