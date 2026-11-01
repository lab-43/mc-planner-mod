package dev.lab43.mcplanner.mc.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A plain scrolling list of two-line rows (title + detail, a colour swatch, something on the right), clickable. */
final class Rows {
    static final int ROW_H = 24;

    record Row(int swatch, String title, int titleColor, String detail, String right, int rightColor, Runnable onClick) {}

    final int x, y, w, h;
    final List<Row> items = new ArrayList<>();
    double scroll;
    String empty = "";

    Rows(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = Math.max(ROW_H, h);
    }

    void add(Row r) {
        items.add(r);
    }

    private int maxScroll() {
        return Math.max(0, items.size() * ROW_H - h);
    }

    void extract(GuiGraphicsExtractor g, Font font, int mx, int my) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x40000000);
        if (items.isEmpty()) {
            int ty = y + 8;
            for (var line : font.split(net.minecraft.network.chat.Component.literal(empty), w - 12)) {
                g.text(font, line, x + 6, ty, BaseScreen.GREY, false);
                ty += 10;
            }
            return;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        g.enableScissor(x, y, x + w, y + h);
        int first = (int) (scroll / ROW_H);
        for (int i = first; i < items.size(); i++) {
            int ry = y + i * ROW_H - (int) scroll;
            if (ry > y + h) break;
            Row r = items.get(i);
            boolean hover = r.onClick() != null && mx >= x && mx < x + w && my >= ry && my < ry + ROW_H && my >= y && my < y + h;
            if (hover) g.fill(x, ry, x + w, ry + ROW_H, 0x30FFFFFF);
            int tx = x + 6;
            if (r.swatch() != 0) {
                g.fill(x + 4, ry + 4, x + 8, ry + ROW_H - 4, r.swatch());
                tx = x + 13;
            }
            int rightW = r.right() == null ? 0 : font.width(r.right());
            int titleMax = w - (tx - x) - rightW - 10;
            g.text(font, clip(font, r.title(), titleMax), tx, ry + 3, r.titleColor(), false);
            if (r.detail() != null) g.text(font, clip(font, r.detail(), w - (tx - x) - 6), tx, ry + 13, BaseScreen.DIM, false);
            if (r.right() != null) g.text(font, r.right(), x + w - rightW - 6, ry + 3, r.rightColor(), false);
        }
        g.disableScissor();
        if (maxScroll() > 0) { // scroll bar
            int barH = Math.max(12, h * h / (items.size() * ROW_H));
            int barY = y + (int) ((h - barH) * (scroll / maxScroll()));
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0x80FFFFFF);
        }
    }

    boolean click(double mx, double my) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        int i = (int) ((my - y + scroll) / ROW_H);
        if (i < 0 || i >= items.size()) return false;
        Runnable r = items.get(i).onClick();
        if (r == null) return false;
        r.run();
        return true;
    }

    boolean scroll(double mx, double my, double dy) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - dy * ROW_H));
        return true;
    }

    static String clip(Font font, String s, int max) {
        if (s == null) return "";
        if (font.width(s) <= max) return s;
        String e = "…";
        int cut = s.length();
        while (cut > 0 && font.width(s.substring(0, cut)) + font.width(e) > max) cut--;
        return s.substring(0, cut) + e;
    }
}
