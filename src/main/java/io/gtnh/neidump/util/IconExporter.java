package io.gtnh.neidump.util;

import io.gtnh.neidump.model.ExportRecipe;
import io.gtnh.neidump.util.ReflectionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Renders item/fluid icons using Minecraft's GL context and saves as PNG.
 */
public class IconExporter {

    private static final int ICON_SIZE = 32;
    private static final int ITEM_REPORT_EVERY = 500;
    private static final int FLUID_REPORT_EVERY = 200;

    /** Backwards-compatible entry point — uses a no-op progress sink. */
    public static int exportIcons(List<ExportRecipe> recipes, File iconDir) {
        return exportIcons(recipes, iconDir, io.gtnh.neidump.util.ProgressCallback.NOOP);
    }

    /**
     * Render every unique item / fluid referenced by {@code recipes} into 32×32 PNGs
     * under {@code iconDir/items} and {@code iconDir/fluids}.
     *
     * <p>Must run on the client render thread (uses GL framebuffer + RenderItem).
     *
     * @return total icons successfully written
     */
    public static int exportIcons(List<ExportRecipe> recipes, File iconDir,
                                  io.gtnh.neidump.util.ProgressCallback cb) {
        if (cb == null) cb = io.gtnh.neidump.util.ProgressCallback.NOOP;

        // Collect unique items and fluids from all recipes
        Set<String> uniqueItems = new LinkedHashSet<>();
        Set<String> uniqueFluids = new LinkedHashSet<>();
        for (ExportRecipe r : recipes) {
            collectFromSlotMap(r.getInput(), uniqueItems, uniqueFluids);
            collectFromSlotMap(r.getOutput(), uniqueItems, uniqueFluids);
        }

        Minecraft mc = Minecraft.getMinecraft();
        int total = 0;

        // Export item icons
        File itemsDir = new File(iconDir, "items");
        itemsDir.mkdirs();
        int itemCount = 0;
        int itemSeen = 0;
        for (String key : uniqueItems) {
            itemSeen++;
            try {
                String[] parts = key.split("@", 2);
                String id = parts[0];
                int meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                ItemStack stack = makeItemStack(id, meta);
                if (stack == null) continue;
                BufferedImage img = renderItemStack(mc, stack);
                if (img != null) {
                    String safeId = id.replace(":", "_");
                    File out = new File(itemsDir, safeId + "@" + meta + ".png");
                    out.getParentFile().mkdirs();
                    ImageIO.write(img, "PNG", out);
                    itemCount++;
                    if (itemCount % ITEM_REPORT_EVERY == 0) {
                        System.out.println("[NEIExport] Icons: " + itemCount + "/" + uniqueItems.size() + " items");
                        cb.onProgress("items", itemSeen, uniqueItems.size());
                    }
                }
            } catch (Exception ignored) {}
        }
        System.out.println("[NEIExport] Items: " + itemCount + "/" + uniqueItems.size());
        cb.onStageDone("items", itemCount, uniqueItems.size());
        total += itemCount;

        // Export fluid icons
        if (!uniqueFluids.isEmpty()) {
            File fluidsDir = new File(iconDir, "fluids");
            fluidsDir.mkdirs();
            int fluidCount = 0;
            int fluidSeen = 0;
            for (String fluidName : uniqueFluids) {
                fluidSeen++;
                try {
                    BufferedImage img = renderFluidIcon(mc, fluidName);
                    if (img != null) {
                        String safeName = fluidName.replace(":", "_").replace("/", "_");
                        File out = new File(fluidsDir, safeName + ".png");
                        ImageIO.write(img, "PNG", out);
                        fluidCount++;
                        if (fluidCount % FLUID_REPORT_EVERY == 0) {
                            System.out.println("[NEIExport] Fluids: " + fluidCount + "/" + uniqueFluids.size());
                            cb.onProgress("fluids", fluidSeen, uniqueFluids.size());
                        }
                    }
                } catch (Exception ignored) {}
            }
            System.out.println("[NEIExport] Fluids: " + fluidCount + "/" + uniqueFluids.size());
            cb.onStageDone("fluids", fluidCount, uniqueFluids.size());
            total += fluidCount;
        }

        System.out.println("[NEIExport] Total icons: " + total);
        return total;
    }

    private static void collectFromSlotMap(Map<String, Map<String, String>> slots,
                                            Set<String> items, Set<String> fluids) {
        for (Map.Entry<String, Map<String, String>> e : slots.entrySet()) {
            Map<String, String> v = e.getValue();
            if (v == null) continue;
            String id = v.get("id");
            if (id == null || id.equals("unknown:unknown")) continue;
            if (id.contains(":")) {
                String meta = v.containsKey("meta") ? v.get("meta") : "0";
                items.add(id + "@" + meta);
            } else {
                // Fluid IDs (no colon): "water", "steam", "ic2steam" etc.
                fluids.add(id);
            }
        }
    }

    private static BufferedImage renderFluidIcon(Minecraft mc, String fluidName) {
        Fluid fluid = FluidRegistry.getFluid(fluidName);
        if (fluid == null) return null;

        IIcon icon = fluid.getStillIcon();
        if (icon == null) icon = fluid.getFlowingIcon();
        if (icon == null) return null;

        Framebuffer fb = null;
        try {
            drainLeakedGLState();

            fb = new Framebuffer(ICON_SIZE, ICON_SIZE, true);
            fb.bindFramebuffer(true);
            setupRenderState();
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // Flat textured quad — disable lighting and depth for clean output.
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            // Many fluids ship a greyscale/uncoloured texture and rely on getColor()
            // for tinting. Multiply via glColor4f.
            int color = fluid.getColor();
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            GL11.glColor4f(r, g, b, 1.0f);

            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            float u = icon.getMinU();
            float v = icon.getMinV();
            float u2 = icon.getMaxU();
            float v2 = icon.getMaxV();

            // Quad spans 0..16 in GUI-space coords (matches setupRenderState's
            // ortho * scale(1/16) — NOT 0..ICON_SIZE).
            final int QUAD = 16;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(u, v2);  GL11.glVertex2f(0,    QUAD);
            GL11.glTexCoord2f(u2, v2); GL11.glVertex2f(QUAD, QUAD);
            GL11.glTexCoord2f(u2, v);  GL11.glVertex2f(QUAD, 0);
            GL11.glTexCoord2f(u, v);   GL11.glVertex2f(0,    0);
            GL11.glEnd();

            BufferedImage img = readFramebuffer();
            fb.unbindFramebuffer();
            return img;
        } catch (Throwable t) {
            return null;
        } finally {
            if (fb != null) { try { fb.deleteFramebuffer(); } catch (Exception ignored) {} }
            try { GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); } catch (Exception ignored) {}
            try { GL11.glColor4f(1f, 1f, 1f, 1f); } catch (Exception ignored) {}
            drainLeakedGLState();
        }
    }

    private static ItemStack makeItemStack(String id, int meta) {
        // Parse "modid:name" → Item
        if (!id.contains(":")) return null;
        String[] parts = id.split(":", 2);

        // Try to find item by registry name
        Item item = (Item) Item.itemRegistry.getObject(id);
        if (item == null) {
            // Try finding block
            net.minecraft.block.Block block = (net.minecraft.block.Block) net.minecraft.block.Block.blockRegistry.getObject(id);
            if (block != null) {
                item = Item.getItemFromBlock(block);
            }
        }
        if (item == null) return null;
        return new ItemStack(item, 1, meta);
    }

    private static BufferedImage renderItemStack(Minecraft mc, ItemStack stack) {
        Framebuffer fb = null;
        try {
            // Reset any leaked GL state from a previous failed render
            drainLeakedGLState();

            // Depth=true: block items need depth test for correct layering.
            fb = new Framebuffer(ICON_SIZE, ICON_SIZE, true);
            fb.bindFramebuffer(true);
            // Clear AFTER setupRenderState so we hit the right viewport/matrices.
            setupRenderState();
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // NEI's drawItem expects depth test ON (its enable3DRender() does this).
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1f, 1f, 1f, 1f);

            // Try NEI's wrapper first — it handles state quirks (zLevel=200, etc.)
            // that the bare RenderItem path doesn't, and is what NESQL uses.
            boolean drawn = drawItemViaNEI(stack);
            if (!drawn) {
                drawItemViaVanilla(mc, stack);
            }

            RenderHelper.disableStandardItemLighting();
            BufferedImage img = readFramebuffer();
            fb.unbindFramebuffer();
            return img;
        } catch (Throwable t) {
            return null;
        } finally {
            if (fb != null) { try { fb.deleteFramebuffer(); } catch (Exception ignored) {} }
            try { GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); } catch (Exception ignored) {}
            // Clean up any state leaked by this render (for the next item)
            drainLeakedGLState();
        }
    }

    /**
     * Render an item via {@code codechicken.nei.guihook.GuiContainerManager.drawItem(int, int, ItemStack)}.
     *
     * <p>NEI's wrapper sets {@code RenderItem.zLevel = 200} internally before
     * calling {@code renderItemAndEffectIntoGUI}, plus other state that the bare
     * vanilla path doesn't establish. Since NEI is a reflection-only dependency,
     * we fail soft if the class isn't found.
     *
     * @return true if the call succeeded; false if NEI isn't loaded.
     */
    private static boolean drawItemViaNEI(ItemStack stack) {
        try {
            Class<?> cls = Class.forName("codechicken.nei.guihook.GuiContainerManager");
            java.lang.reflect.Method m = cls.getMethod(
                    "drawItem", int.class, int.class, ItemStack.class);
            m.invoke(null, 0, 0, stack);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // NEI was found but call failed — propagate so the outer catch logs.
            throw new RuntimeException("NEI drawItem failed", t);
        }
    }

    /**
     * Vanilla fallback: directly call {@link net.minecraft.client.renderer.entity.RenderItem#renderItemAndEffectIntoGUI}
     * with {@code zLevel} bumped to 200 to match NEI's behaviour.
     */
    private static void drawItemViaVanilla(Minecraft mc, ItemStack stack) {
        Object ri = ReflectionUtils.readStaticField(
                "net.minecraft.client.renderer.entity.RenderItem", "instance");
        if (!(ri instanceof net.minecraft.client.renderer.entity.RenderItem)) return;
        net.minecraft.client.renderer.entity.RenderItem renderItem =
                (net.minecraft.client.renderer.entity.RenderItem) ri;
        float oldZ = renderItem.zLevel;
        try {
            renderItem.zLevel = 200f;
            renderItem.renderItemAndEffectIntoGUI(
                    mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        } finally {
            renderItem.zLevel = oldZ;
        }
    }

    /**
     * Set up an orthographic projection matching the NESQL renderer:
     * <pre>
     *   ortho(0, 1, 1, 0, -100, 100)  // Y flipped (top-left origin)
     *   scale(1/16)                   // model coords 0..16 map to clip 0..1
     * </pre>
     * Combined with the framebuffer viewport (size = ICON_SIZE), an icon drawn
     * at GUI coords {@code (0, 0)..(16, 16)} fills the entire output image.
     *
     * <p>Without this, the inherited projection from the previous game frame
     * places items outside the FBO clip volume — readback returns all zeros
     * and you get blank 83-byte PNGs.
     */
    private static void setupRenderState() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, 1.0, 1.0, 0.0, -100.0, 100.0);
        double scaleFactor = 1.0 / 16.0;
        GL11.glScaled(scaleFactor, scaleFactor, scaleFactor);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
    }

    /**
     * Read the current framebuffer back into a {@link BufferedImage}.
     * Uses ByteBuffer + asIntBuffer + bulk setRGB — much faster than
     * pixel-by-pixel setRGB in a nested loop.
     *
     * <p>OpenGL's origin is bottom-left while AWT's is top-left, so we flip
     * the row order via the index calculation rather than a GL matrix flip
     * (matrix-flipping breaks rendering for some unknown reason — see NESQL).
     */
    private static BufferedImage readFramebuffer() {
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * ICON_SIZE * ICON_SIZE);
        GL11.glReadPixels(0, 0, ICON_SIZE, ICON_SIZE, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bb);
        int[] pixels = new int[ICON_SIZE * ICON_SIZE];
        bb.asIntBuffer().get(pixels);

        // Flip Y: GL row 0 is bottom; AWT row 0 is top.
        int[] flipped = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int x = i % ICON_SIZE;
            int y = ICON_SIZE - (i / ICON_SIZE + 1);
            flipped[i] = pixels[x + ICON_SIZE * y];
        }

        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, ICON_SIZE, ICON_SIZE, flipped, 0, ICON_SIZE);
        return img;
    }

    /**
     * Drain leaked GL attrib stack entries and reset the Tessellator if it's
     * stuck in a drawing state. This prevents cascading failures when a mod's
     * custom block renderer crashes mid-render (e.g. OpenMods pushing attribs
     * or starting tessellation without finishing).
     *
     * <p>Safe to call at any time — if the stack is already empty or the
     * tessellator is idle, this is a no-op.
     */
    private static void drainLeakedGLState() {
        // Pop any leaked glPushAttrib calls. Angelica caps at 18; vanilla GL
        // has a driver-dependent limit. We loop up to 20 to be safe.
        for (int i = 0; i < 20; i++) {
            try {
                GL11.glPopAttrib();
            } catch (Throwable t) {
                // Stack underflow or Angelica "stack empty" — we're clean
                break;
            }
        }

        // Reset Tessellator if it's stuck in "isDrawing" state.
        // Field name: MCP = "isDrawing", SRG = "field_78415_z"
        try {
            net.minecraft.client.renderer.Tessellator tess =
                    net.minecraft.client.renderer.Tessellator.instance;
            boolean drawing = false;
            // Try MCP name first, then SRG
            for (String fieldName : new String[]{"isDrawing", "field_78415_z"}) {
                try {
                    java.lang.reflect.Field f = tess.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    drawing = f.getBoolean(tess);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (drawing) {
                // Discard whatever partial geometry is in the buffer
                try { tess.draw(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Clear any lingering GL error flags so they don't trip up the next call
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }
    }
}
