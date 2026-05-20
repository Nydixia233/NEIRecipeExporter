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
import java.nio.IntBuffer;
import java.util.*;

/**
 * Renders item/fluid icons using Minecraft's GL context and saves as PNG.
 */
public class IconExporter {

    private static final int ICON_SIZE = 32;

    public static int exportIcons(List<ExportRecipe> recipes, File iconDir) {
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
        for (String key : uniqueItems) {
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
                    if (itemCount % 500 == 0)
                        System.out.println("[NEIExport] Icons: " + itemCount + "/" + uniqueItems.size() + " items");
                }
            } catch (Exception ignored) {}
        }
        System.out.println("[NEIExport] Items: " + itemCount + "/" + uniqueItems.size());
        total += itemCount;

        // Export fluid icons
        if (!uniqueFluids.isEmpty()) {
            File fluidsDir = new File(iconDir, "fluids");
            fluidsDir.mkdirs();
            int fluidCount = 0;
            for (String fluidName : uniqueFluids) {
                try {
                    BufferedImage img = renderFluidIcon(mc, fluidName);
                    if (img != null) {
                        String safeName = fluidName.replace(":", "_").replace("/", "_");
                        File out = new File(fluidsDir, safeName + ".png");
                        ImageIO.write(img, "PNG", out);
                        fluidCount++;
                        if (fluidCount % 200 == 0)
                            System.out.println("[NEIExport] Fluids: " + fluidCount + "/" + uniqueFluids.size());
                    }
                } catch (Exception ignored) {}
            }
            System.out.println("[NEIExport] Fluids: " + fluidCount + "/" + uniqueFluids.size());
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
            fb = new Framebuffer(ICON_SIZE, ICON_SIZE, false);
            fb.bindFramebuffer(true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Draw fluid color background
            int color = fluid.getColor();
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            GL11.glColor4f(r, g, b, 1.0f);

            // Render the fluid icon texture
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            float u = icon.getMinU();
            float v = icon.getMinV();
            float u2 = icon.getMaxU();
            float v2 = icon.getMaxV();

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(u, v2); GL11.glVertex2f(0, 0);
            GL11.glTexCoord2f(u2, v2); GL11.glVertex2f(ICON_SIZE, 0);
            GL11.glTexCoord2f(u2, v); GL11.glVertex2f(ICON_SIZE, ICON_SIZE);
            GL11.glTexCoord2f(u, v); GL11.glVertex2f(0, ICON_SIZE);
            GL11.glEnd();

            // Read pixels
            int[] pixels = new int[ICON_SIZE * ICON_SIZE];
            IntBuffer buffer = BufferUtils.createIntBuffer(ICON_SIZE * ICON_SIZE);
            GL11.glReadPixels(0, 0, ICON_SIZE, ICON_SIZE, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);
            buffer.get(pixels);
            fb.unbindFramebuffer();

            BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ICON_SIZE; y++)
                for (int x = 0; x < ICON_SIZE; x++)
                    img.setRGB(x, ICON_SIZE - 1 - y, pixels[y * ICON_SIZE + x]);
            return img;
        } catch (Throwable t) {
            return null;
        } finally {
            if (fb != null) { try { fb.deleteFramebuffer(); } catch (Exception ignored) {} }
            try { GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); } catch (Exception ignored) {}
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
            fb = new Framebuffer(ICON_SIZE, ICON_SIZE, false);
            fb.bindFramebuffer(true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderHelper.enableGUIStandardItemLighting();
            Object ri = ReflectionUtils.readStaticField("net.minecraft.client.renderer.entity.RenderItem", "instance");
            if (ri instanceof net.minecraft.client.renderer.entity.RenderItem) {
                ((net.minecraft.client.renderer.entity.RenderItem) ri).renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
            }
            RenderHelper.disableStandardItemLighting();
            int[] pixels = new int[ICON_SIZE * ICON_SIZE];
            IntBuffer buffer = BufferUtils.createIntBuffer(ICON_SIZE * ICON_SIZE);
            GL11.glReadPixels(0, 0, ICON_SIZE, ICON_SIZE, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);
            buffer.get(pixels);
            fb.unbindFramebuffer();
            BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ICON_SIZE; y++)
                for (int x = 0; x < ICON_SIZE; x++)
                    img.setRGB(x, ICON_SIZE - 1 - y, pixels[y * ICON_SIZE + x]);
            return img;
        } catch (Throwable t) {
            return null;
        } finally {
            if (fb != null) { try { fb.deleteFramebuffer(); } catch (Exception ignored) {} }
            try { GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); } catch (Exception ignored) {}
        }
    }
}
