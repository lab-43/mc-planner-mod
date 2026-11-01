package dev.lab43.mcplanner.mc.gui;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import dev.lab43.mcplanner.core.ModConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** How the plan looks in the world and the HUD. Saved to config/mcplanner.json right away. */
public final class DisplayScreen extends BaseScreen {
    public DisplayScreen(Screen parent) {
        super("Display", parent);
    }

    @Override
    protected void init() {
        ModConfig c = p.config;
        int l = left(), w = panelW(), half = (w - 4) / 2, y = 36;

        toggle("Block textures", () -> c.textures, v -> c.textures = v, l, y, w,
                "Show the block an object is planned in (gravel, water, wheat, stone bricks …) on its tiles, fill, walls and roof, and schematic buildings with their blocks");
        y += 22;
        toggle("Path tiles", () -> c.pathTiles, v -> c.pathTiles = v, l, y, half, "Colour every block of a path without a block set (what to pave)");
        toggle("Area fill", () -> c.areaFill, v -> c.areaFill = v, l + half + 4, y, half, "Tint the ground inside areas without a block set, not just the outline");
        y += 22;
        toggle("Labels through walls", () -> c.labelsThroughWalls, v -> c.labelsThroughWalls = v, l, y, half, "Names stay visible behind blocks");
        toggle("Outlines through walls", () -> c.outlinesThroughWalls, v -> c.outlinesThroughWalls = v, l + half + 4, y, half, "Lines and boxes stay visible behind blocks (X-ray style)");
        y += 22;
        cycle("Draw up to", List.of(64, 96, 128, 160, 256, 384, 512), () -> c.renderDistance, v -> c.renderDistance = v, " blocks", l, y, half);
        cycle("Labels up to", List.of(32, 48, 64, 96, 128, 192, 256), () -> c.labelDistance, v -> c.labelDistance = v, " blocks", l + half + 4, y, half);
        y += 22;
        cycle("Fill", List.of(10, 15, 25, 40, 60), () -> Math.round(c.fillOpacity * 100), v -> c.fillOpacity = v / 100f, " %", l, y, half);
        cycle("Lines", List.of(40, 60, 75, 90, 100), () -> Math.round(c.lineOpacity * 100), v -> c.lineOpacity = v / 100f, " %", l + half + 4, y, half);
        y += 28;
        toggle("HUD", () -> c.hud, v -> c.hud = v, l, y, half, "Plan name, navigation and the object you stand in");
        Button corner = button("HUD corner: " + cornerLabel(c.hudCorner), l + half + 4, y, half, b -> {
            int i = ModConfig.HUD_CORNERS.indexOf(c.hudCorner);
            c.hudCorner = ModConfig.HUD_CORNERS.get((i + 1) % ModConfig.HUD_CORNERS.size());
            b.setMessage(Component.literal("HUD corner: " + cornerLabel(c.hudCorner)));
            p.saveConfig();
        });
        corner.active = c.hud;

        button("Back", l + (w - 100) / 2, height - 26, 100, b -> onClose());
    }

    private void toggle(String name, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set, int x, int y, int w, String tip) {
        button(onOff(name, get.getAsBoolean()), x, y, w, tip, b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(Component.literal(onOff(name, get.getAsBoolean())));
            p.saveConfig();
            if (name.equals("HUD")) requestRebuild();
        });
    }

    private void cycle(String name, List<Integer> values, IntSupplier get, IntConsumer set, String unit, int x, int y, int w) {
        button(name + ": " + get.getAsInt() + unit, x, y, w, b -> {
            int cur = get.getAsInt(), next = values.get(0);
            for (int v : values) if (v > cur) { next = v; break; }
            set.accept(next);
            b.setMessage(Component.literal(name + ": " + get.getAsInt() + unit));
            p.saveConfig();
        });
    }

    private static String cornerLabel(String c) {
        return switch (c == null ? "" : c) {
            case "top_right" -> "top right";
            case "bottom_left" -> "bottom left";
            case "bottom_right" -> "bottom right";
            default -> "top left";
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        title(g, "How plans look in the world");
        int y = 36 + 22 * 4 + 28 + 28;
        paragraph(g, "Servers: " + p.config.apiUrl + " · sign-in " + p.config.hubUrl
                + ". To use another MC Planner server, change apiUrl / hubUrl in config/mcplanner.json.", left(), y, panelW(), DIM);
    }
}
