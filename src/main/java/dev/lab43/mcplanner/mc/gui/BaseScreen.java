package dev.lab43.mcplanner.mc.gui;

import java.util.List;

import dev.lab43.mcplanner.mc.Planner;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shared layout for the MC Planner screens: a centred column, buttons, word-wrapped text and one scrolling list. */
abstract class BaseScreen extends Screen {
    static final int WHITE = 0xFFFFFFFF;
    static final int GREY = 0xFFA8A8A8;
    static final int DIM = 0xFF7C7C7C;
    static final int ACCENT = 0xFFFFC857;
    static final int ERROR = 0xFFFF7A6B;
    static final int OK = 0xFF7BE07B;

    protected final Screen parent;
    protected final Planner p;
    protected Rows rows;
    private int seenVersion;
    private boolean seenSignedIn;
    private Planner.Source seenSource;
    private boolean seenPaused;
    private boolean dirty;

    protected BaseScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
        this.p = Planner.get();
        remember();
    }

    Screen parentScreen() {
        return parent;
    }

    protected int panelW() {
        return Math.min(400, width - 16);
    }

    protected int left() {
        return (width - panelW()) / 2;
    }

    protected Button button(String label, int x, int y, int w, Button.OnPress onPress) {
        return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, 20).build());
    }

    protected Button button(String label, int x, int y, int w, String tooltip, Button.OnPress onPress) {
        Button b = button(label, x, y, w, onPress);
        if (tooltip != null) b.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return b;
    }

    protected static String onOff(String name, boolean on) {
        return name + ": " + (on ? "On" : "Off");
    }

    protected static String check(String name, boolean on) {
        return (on ? "✔ " : "✘ ") + name;
    }

    /** word-wrapped text, returns the y below it */
    protected int paragraph(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(text), w);
        for (net.minecraft.util.FormattedCharSequence l : lines) {
            g.text(font, l, x, y, color, false);
            y += 10;
        }
        return y;
    }

    protected void title(GuiGraphicsExtractor g, String sub) {
        g.centeredText(font, Component.literal(getTitle().getString()).withStyle(net.minecraft.ChatFormatting.BOLD), width / 2, 10, WHITE);
        if (sub != null) g.centeredText(font, Component.literal(sub), width / 2, 22, GREY);
    }

    // ---------- keep in step with the planner ----------

    private void remember() {
        if (p == null) return;
        seenVersion = p.planVersion();
        seenSignedIn = p.isSignedIn();
        seenSource = p.source();
        seenPaused = p.isPaused();
    }

    /**
     * Rebuild the widgets on the next tick — never from inside a button's callback: Minecraft focuses the pressed
     * button after the callback, and a focused button that was already replaced would fire again on Enter.
     */
    protected void requestRebuild() {
        dirty = true;
    }

    /** rebuild the widgets when asked to, or when the plan, the account, the source or the gate changed under us */
    @Override
    public void tick() {
        super.tick();
        if (p == null) return;
        if (dirty || p.planVersion() != seenVersion || p.isSignedIn() != seenSignedIn || p.source() != seenSource || p.isPaused() != seenPaused) {
            dirty = false;
            remember();
            double keep = rows == null ? 0 : rows.scroll;
            rebuildWidgets();
            if (rows != null) rows.scroll = keep;
        }
    }

    /** this screen is still the one shown (callbacks from the network may arrive after it was left) */
    protected boolean isCurrent() {
        return minecraft.gui.screen() == this;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        if (rows != null) rows.extract(g, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        return rows != null && e.button() == 0 && rows.click(e.x(), e.y());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (rows != null && rows.scroll(x, y, sy)) return true;
        return super.mouseScrolled(x, y, sx, sy);
    }
}
