package dev.lab43.mcplanner.mc;

import java.util.ArrayList;
import java.util.List;

import dev.lab43.mcplanner.core.ModConfig;
import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.mc.render.Overlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/**
 * The small corner panel: which plan is shown (and whether it's live), where the navigation target is, and which
 * planned object you're standing in. Hidden with F1 like the rest of the HUD.
 */
public final class Hud {
    private final Planner planner;
    private final Overlay overlay;
    private PlanObject inside;
    private int tick;

    public Hud(Planner planner, Overlay overlay) {
        this.planner = planner;
        this.overlay = overlay;
    }

    /** every client tick: what you're standing in (cheap, but not every frame) */
    public void tick() {
        Minecraft mc = planner.mc;
        if (mc.player == null || ++tick % 10 != 0) return;
        inside = overlay.objectAt(mc.player.getBlockX(), mc.player.getBlockZ());
    }

    public PlanObject inside() {
        return inside;
    }

    public void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = planner.mc;
        ModConfig c = planner.config;
        if (!c.hud || mc.player == null || mc.gui.hud.isHidden()) return;
        if (mc.gui.hud.getDebugOverlay().showDebugScreen()) return; // F3 owns the corners
        if (planner.source() == Planner.Source.NONE) return;

        List<Line> lines = new ArrayList<>();
        String name = planner.sourceName() == null ? "Plan" : planner.sourceName();
        String state;
        int dot;
        String gate = planner.gateLabel();
        if (gate != null) {
            // nothing is drawn: say why (J → "What now?")
            state = gate + " · J";
            dot = 0xFFFFC857;
        } else if (planner.error() != null) {
            state = "offline";
            dot = 0xFFFF7A6B;
        } else if (planner.isOffline()) {
            state = "saved copy";
            dot = 0xFFFFC857;
        } else if (planner.source() == Planner.Source.FILE) {
            state = "file";
            dot = 0xFF9AD0FF;
        } else {
            state = planner.isPaused() ? "paused" : "live";
            dot = planner.isPaused() ? 0xFFFFC857 : 0xFF7BE07B;
        }
        lines.add(new Line("● ", dot, Texts.ellipsize(name, 28) + "  ", 0xFFFFFFFF, state + (c.overlay ? "" : " · hidden"), 0xFFA0A0A0));

        PlanObject t = planner.navTarget();
        if (t != null && t.points.length > 0) {
            Vec3 p = mc.player.position();
            double tx, tz;
            var b = t.bounds();
            tx = (b.x0() + b.x1() + 1) / 2.0;
            tz = (b.z0() + b.z1() + 1) / 2.0;
            boolean otherDim = !t.dimension.equals(planner.dimension());
            String where;
            String arrow;
            if (otherDim) {
                where = "in " + prettyDimension(t.dimension);
                arrow = "⇄ ";
            } else {
                double dx = tx - p.x, dz = tz - p.z;
                where = Texts.distance(Math.hypot(dx, dz)) + " " + Texts.compass(dx, dz);
                arrow = Texts.arrow(dx, dz, mc.player.getYRot()) + " ";
            }
            lines.add(new Line(arrow, 0xFFFFE680, Texts.ellipsize(t.name, 26) + "  ", 0xFFFFE680, where, 0xFFE0E0E0));
        }
        if (inside != null) {
            lines.add(new Line("◆ ", 0xFF000000 | inside.color, Texts.ellipsize(inside.name, 26) + "  ", 0xFFFFFFFF,
                    inside.kind.label() + " · " + inside.status.label(), Texts.statusColor(inside.status)));
        }

        Font font = mc.font;
        int w = 0;
        for (Line l : lines) w = Math.max(w, font.width(l.a) + font.width(l.b) + font.width(l.c));
        int h = lines.size() * 11 + 5;
        int pad = 4;
        String corner = c.hudCorner == null ? "top_left" : c.hudCorner;
        int x = corner.endsWith("right") ? g.guiWidth() - w - pad * 2 - 4 : 4;
        int y = corner.startsWith("bottom") ? g.guiHeight() - h - 48 : 4;
        g.fill(x, y, x + w + pad * 2, y + h, 0x88000000);
        int ly = y + 4;
        for (Line l : lines) {
            int lx = x + pad;
            g.text(font, l.a, lx, ly, l.ac, true);
            lx += font.width(l.a);
            g.text(font, l.b, lx, ly, l.bc, true);
            lx += font.width(l.b);
            g.text(font, l.c, lx, ly, l.cc, true);
            ly += 11;
        }
    }

    static String prettyDimension(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> id;
        };
    }

    private record Line(String a, int ac, String b, int bc, String c, int cc) {}
}
