package dev.lab43.mcplanner.mc.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Name a new marker; it's placed at the block you're standing on and appears in the app for everyone at once. */
public final class MarkerScreen extends BaseScreen {
    private EditBox name;
    private Button add;
    private String message;
    private int messageColor = GREY;
    private boolean saving;

    public MarkerScreen(Screen parent) {
        super("New marker", parent);
    }

    @Override
    protected void init() {
        int l = left(), w = panelW();
        String keep = name == null ? "" : name.getValue();
        name = new EditBox(font, l, height / 2 - 20, w, 20, Component.literal("Name"));
        name.setMaxLength(100);
        name.setHint(Component.literal("Name, e.g. \"Storage room\""));
        name.setValue(keep);
        addRenderableWidget(name);
        setInitialFocus(name);
        int half = (w - 3) / 2;
        add = button("Add marker", l, height / 2 + 14, half, b -> submit());
        add.active = !saving && p.canEdit();
        button("Cancel", l + half + 3, height / 2 + 14, w - half - 3, b -> onClose());
    }

    private void submit() {
        if (saving) return;
        saving = true;
        add.active = false;
        message = "Saving…";
        messageColor = GREY;
        p.addMarker(name.getValue(), err -> {
            saving = false;
            if (!isCurrent()) return;
            if (err == null) onClose();
            else {
                message = err;
                messageColor = ERROR;
                if (add != null) add.active = p.canEdit();
            }
        });
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if ((e.key() == 257 || e.key() == 335) && name != null && name.isFocused()) { // Enter / keypad Enter
            submit();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        title(g, p.sourceName() == null ? null : "in " + p.sourceName());
        if (minecraft.player != null) {
            BlockPos pos = minecraft.player.blockPosition();
            g.centeredText(font, Component.literal("At x " + pos.getX() + " · y " + pos.getY() + " · z " + pos.getZ() + " (" + p.dimension() + ")"),
                    width / 2, height / 2 - 36, GREY);
        }
        String why = p.whyReadOnly();
        if (why != null && message == null) g.centeredText(font, Component.literal(why), width / 2, height / 2 + 42, ERROR);
        if (message != null) g.centeredText(font, Component.literal(message), width / 2, height / 2 + 42, messageColor);
    }
}
