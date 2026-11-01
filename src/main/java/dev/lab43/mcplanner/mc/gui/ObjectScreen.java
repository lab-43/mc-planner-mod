package dev.lab43.mcplanner.mc.gui;

import dev.lab43.mcplanner.core.Kind;
import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.Status;
import dev.lab43.mcplanner.mc.Schematics;
import dev.lab43.mcplanner.mc.Texts;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/** One plan object: what it is, where, its notes; navigate there; change its status (editors of a shared world). */
public final class ObjectScreen extends BaseScreen {
    private final String id;
    private String message;
    private int messageColor = GREY;

    public ObjectScreen(Screen parent, String id) {
        super("Plan object", parent);
        this.id = id;
    }

    private PlanObject obj() {
        return p.object(id);
    }

    @Override
    protected void init() {
        PlanObject o = obj();
        int l = left(), w = panelW();
        if (o == null) {
            button("Back", l + (w - 100) / 2, height - 26, 100, b -> onClose());
            return;
        }
        if (o.schematic != null) {
            int half = (w - 3) / 2, sy = height - 98;
            button("Schematics folder", l, sy, half, "Put the schematic file here (or in Downloads) — the game shows its blocks then", b -> {
                try {
                    java.nio.file.Files.createDirectories(p.schematics.folder());
                    Util.getPlatform().openPath(p.schematics.folder());
                } catch (Exception e) {
                    message = "Couldn't open " + p.schematics.folder();
                    messageColor = ERROR;
                }
            });
            button("Look again", l + half + 3, sy, w - half - 3, "Look for the schematic file on this computer now", b -> {
                p.schematics.rescan();
                message = "Looking for " + o.schematic.file() + "…";
                messageColor = GREY;
            });
        }
        int y = height - 74;
        boolean target = p.navTarget() != null && p.navTarget().id.equals(o.id);
        int half = (w - 3) / 2;
        button(target ? "Stop navigating" : "Navigate here", l, y, half, "A guide line and a beam lead you there (also in the HUD)", b -> {
            p.navigate(target ? null : o);
            if (!target) onClose();
            else requestRebuild();
        });
        Button link = button("Open source link", l + half + 3, y, w - half - 3, o.sourceUrl, b -> {
            // someone else's link from a shared plan: vanilla's "are you sure" screen (and no crash on odd URLs)
            if (o.sourceUrl != null) ConfirmLinkScreen.confirmLinkNow(this, o.sourceUrl, false);
        });
        link.active = o.sourceUrl != null;
        y += 24;
        int qw = (w - 9) / 4;
        String why = p.whyReadOnly();
        Status[] all = Status.values();
        for (int i = 0; i < all.length; i++) {
            Status s = all[i];
            String label = (o.status == s ? "● " : "") + s.label();
            Button b = button(label, l + i * (qw + 3), y, i == 3 ? w - 3 * (qw + 3) : qw, why != null ? why : "Set the status to " + s.label(), btn -> {
                message = "Saving…";
                messageColor = GREY;
                p.setStatus(o, s, err -> {
                    message = err == null ? "Saved: " + s.label() : err;
                    messageColor = err == null ? OK : ERROR;
                    requestRebuild();
                });
            });
            b.active = why == null && o.status != s;
        }
        button("Back", l + (w - 100) / 2, height - 26, 100, b -> onClose());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        PlanObject o = obj();
        int l = left(), w = panelW();
        if (o == null) {
            title(g, null);
            g.centeredText(font, net.minecraft.network.chat.Component.literal("This object isn't in the plan any more."), width / 2, 50, GREY);
            return;
        }
        g.centeredText(font, net.minecraft.network.chat.Component.literal(Rows.clip(font, o.name, w)).withStyle(net.minecraft.ChatFormatting.BOLD), width / 2, 10, WHITE);
        int y = 26;
        g.fill(l, y, l + 4, y + 20, 0xFF000000 | o.color);
        g.text(font, o.kind.label() + " · ", l + 9, y, GREY, false);
        int sx = l + 9 + font.width(o.kind.label() + " · ");
        g.text(font, o.status.label(), sx, y, Texts.statusColor(o.status), false);
        g.text(font, Rows.clip(font, o.describe(), w - 12), l + 9, y + 11, WHITE, false);
        y += 28;

        var b = o.bounds();
        if (b != null) {
            String where = o.kind == Kind.MARKER
                    ? "x " + o.points[0][0] + " · y " + o.y + " · z " + o.points[0][1]
                    : "x " + b.x0() + " … " + b.x1() + " · z " + b.z0() + " … " + b.z1() + (o.kind == Kind.BUILDING ? " · y " + o.y + " … " + (o.y + o.height) : "");
            y = row(g, "Where", where, l, y, w);
            if (minecraft.player != null && o.dimension.equals(p.dimension())) {
                Vec3 pos = minecraft.player.position();
                double[] c = PlannerScreen.centre(o);
                double dx = c[0] - pos.x, dz = c[1] - pos.z;
                y = row(g, "Distance", Texts.distance(Math.hypot(dx, dz)) + " " + Texts.compass(dx, dz) + " " + Texts.arrow(dx, dz, minecraft.player.getYRot()), l, y, w);
            } else {
                y = row(g, "Dimension", o.dimension, l, y, w);
            }
        }
        if (o.material != null) y = row(g, "Material", PlanObject.materialName(o.material), l, y, w);
        if (o.schematic != null) {
            Schematics.Entry se = p.schematics.get(o.schematic);
            Schematics.State st = se == null ? Schematics.State.SEARCHING : se.state;
            y = row(g, "Schematic", o.schematic.file() + " · " + o.schematic.blocks() + " blocks", l, y, w);
            String state;
            int color;
            switch (st) {
                case READY -> {
                    String from = se.file != null && se.file.getParent() != null ? " (from " + se.file.getParent().getFileName() + ")" : "";
                    state = p.config.textures ? "Shown with its blocks" + from + ". Built blocks disappear." : "Found — switch on Display → Block textures to see its blocks";
                    color = OK;
                }
                case MISSING -> {
                    state = "Not on this computer: get it from the source link, put it in Downloads or the schematics folder";
                    color = ERROR;
                }
                case FAILED -> {
                    state = se.error;
                    color = ERROR;
                }
                default -> {
                    state = "Looking for the file…";
                    color = GREY;
                }
            }
            for (var line : font.split(net.minecraft.network.chat.Component.literal(state), w - 70)) {
                g.text(font, line, l + 64, y + 2, color, false);
                y += 10;
            }
            y += 2;
        }
        if (o.groupName != null) y = row(g, "Group", o.groupName, l, y, w);
        if (o.author != null && !o.author.isBlank()) y = row(g, "By", o.author, l, y, w);
        if (o.updatedAt > 0) y = row(g, "Changed", Texts.ago(o.updatedAt), l, y, w);
        if (o.notes != null && !o.notes.isBlank()) {
            g.text(font, "Notes", l, y + 2, DIM, false);
            int maxY = height - (o.schematic != null ? 114 : 90);
            int ny = y + 2;
            for (var line : font.split(net.minecraft.network.chat.Component.literal(o.notes), w - 70)) {
                if (ny > maxY) {
                    g.text(font, "…", l + 64, ny, GREY, false);
                    break;
                }
                g.text(font, line, l + 64, ny, WHITE, false);
                ny += 10;
            }
        }
        if (message != null) g.centeredText(font, net.minecraft.network.chat.Component.literal(message), width / 2, height - (o.schematic != null ? 110 : 86), messageColor);
    }

    private int row(GuiGraphicsExtractor g, String label, String value, int l, int y, int w) {
        g.text(font, label, l, y + 2, DIM, false);
        g.text(font, Rows.clip(font, value, w - 70), l + 64, y + 2, WHITE, false);
        return y + 12;
    }
}
