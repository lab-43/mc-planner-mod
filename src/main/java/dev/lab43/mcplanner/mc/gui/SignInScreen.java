package dev.lab43.mcplanner.mc.gui;

import java.util.function.Consumer;

import dev.lab43.mcplanner.core.DeviceLogin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Sign in with a Lab43 account, the way TVs do it: Minecraft shows a code, you open auth.lab43.dev/device in your
 * browser (the button does it), sign in there, check the code and press Connect. The mod never sees your email or
 * password — it gets its own token, which you can disconnect any time on auth.lab43.dev/account.
 *
 * <p>Signed in already: shows the account and "Sign out".
 */
public final class SignInScreen extends BaseScreen {
    private final Consumer<DeviceLogin.State> listener = s -> requestRebuild();
    private long approvedAt;
    private boolean signedOutHere;

    public SignInScreen(Screen parent) {
        super("Sign in · Lab43 account", parent);
    }

    @Override
    protected void init() {
        int l = left(), w = panelW();
        p.removeLoginListener(listener);
        p.addLoginListener(listener);
        DeviceLogin.State s = p.loginState();
        int half = (w - 3) / 2;

        if (p.isSignedIn() && (s == null || s.phase() != DeviceLogin.Phase.APPROVED)) {
            button("Sign out", l, height / 2 + 10, half, "Disconnect this device from your account", b -> {
                p.signOut();
                signedOutHere = true;
                requestRebuild();
            });
            button("Back", l + half + 3, height / 2 + 10, w - half - 3, b -> onClose());
            return;
        }
        if (s == null && signedOutHere) { // just signed out: don't start a new sign-in by itself
            button("Sign in again", l, height / 2 + 10, half, b -> {
                signedOutHere = false;
                p.startSignIn();
                requestRebuild();
            });
            button("Back", l + half + 3, height / 2 + 10, w - half - 3, b -> onClose());
            return;
        }
        if (s == null) {
            p.startSignIn();
            s = p.loginState();
        }
        int y = height - 52;
        int third = (w - 6) / 3;
        if (s != null && s.phase() == DeviceLogin.Phase.WAITING) {
            String uri = s.verificationUriComplete() != null ? s.verificationUriComplete() : s.verificationUri();
            button("Open browser", l, y, third, "Opens " + uri, b -> Util.getPlatform().openUri(uri));
            String code = s.userCode();
            button("Copy code", l + third + 3, y, third, b -> minecraft.keyboardHandler.setClipboard(code));
            button("Cancel", l + 2 * (third + 3), y, w - 2 * (third + 3), b -> {
                p.cancelSignIn();
                onClose();
            });
        } else if (s != null && s.isFinal() && s.phase() != DeviceLogin.Phase.APPROVED) {
            button("Try again", l, y, half, b -> {
                p.startSignIn();
                requestRebuild();
            });
            button("Back", l + half + 3, y, w - half - 3, b -> {
                p.cancelSignIn();
                onClose();
            });
        } else if (s != null && s.phase() == DeviceLogin.Phase.APPROVED) {
            if (approvedAt == 0) approvedAt = System.currentTimeMillis();
            button("Choose a plan", l + (w - 140) / 2, y, 140, b -> next());
        } else {
            button("Cancel", l + (w - 100) / 2, y, 100, b -> {
                p.cancelSignIn();
                onClose();
            });
        }
    }

    /** after signing in: the plan picker (or back where we came from, if that was the picker) */
    private void next() {
        p.cancelSignIn();
        minecraft.gui.setScreen(new WorldsScreen(parent instanceof WorldsScreen ws ? ws.parentScreen() : parent));
    }

    @Override
    public void removed() {
        p.removeLoginListener(listener);
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        DeviceLogin.State s = p.loginState();
        // after a successful sign-in, carry on to the plan picker by itself
        if (s != null && s.phase() == DeviceLogin.Phase.APPROVED && approvedAt > 0 && System.currentTimeMillis() - approvedAt > 1800 && isCurrent()) next();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        title(g, "MC Planner uses your Lab43 account (auth.lab43.dev)");
        int l = left(), w = panelW();
        int cx = width / 2;
        DeviceLogin.State s = p.loginState();
        if (p.isSignedIn() && (s == null || s.phase() != DeviceLogin.Phase.APPROVED)) {
            g.centeredText(font, Component.literal("Signed in as " + p.accountName()), cx, height / 2 - 30, WHITE);
            centred(g, "This device stays signed in until you sign out here or disconnect it on auth.lab43.dev/account.", height / 2 - 14, w);
            return;
        }
        if (s == null && signedOutHere) {
            g.centeredText(font, Component.literal("Signed out"), cx, height / 2 - 20, WHITE);
            return;
        }
        if (s == null || s.phase() == DeviceLogin.Phase.STARTING) {
            g.centeredText(font, Component.literal("Asking auth.lab43.dev for a code…"), cx, height / 2 - 10, GREY);
            return;
        }
        switch (s.phase()) {
            case WAITING -> {
                int y = 40;
                y = step(g, "1", "Open auth.lab43.dev/device in your browser (the button below does it).", l, y, w);
                y = step(g, "2", "Sign in with your Lab43 account — the same one you use for MC Planner.", l, y, w);
                y = step(g, "3", "Check that this code matches, then press Connect:", l, y, w);
                String code = s.userCode() == null ? "" : s.userCode();
                g.pose().pushMatrix();
                g.pose().translate(cx, y + 6);
                g.pose().scale(2.5f, 2.5f);
                g.centeredText(font, Component.literal(code).withStyle(ChatFormatting.BOLD), 0, 0, ACCENT);
                g.pose().popMatrix();
                long left = Math.max(0, (s.expiresAt() - System.currentTimeMillis()) / 1000);
                int waitY = Math.min(y + 36, height - 66);
                g.centeredText(font, Component.literal(String.format(java.util.Locale.ROOT,
                        "Waiting for you to press Connect… the code expires in %d:%02d", left / 60, left % 60)), cx, waitY, GREY);
            }
            case APPROVED -> {
                g.centeredText(font, Component.literal("Connected ✔").withStyle(ChatFormatting.BOLD), cx, height / 2 - 20, OK);
                g.centeredText(font, Component.literal("Signed in as " + (s.userName() == null ? p.accountName() : s.userName())), cx, height / 2 - 6, WHITE);
            }
            default -> g.centeredText(font, Component.literal(s.message() == null ? "Sign-in didn't work." : s.message()), cx, height / 2 - 10, ERROR);
        }
    }

    private int step(GuiGraphicsExtractor g, String n, String text, int l, int y, int w) {
        g.text(font, n, l + 4, y, ACCENT, false);
        return paragraph(g, text, l + 16, y, w - 16, WHITE) + 4;
    }

    private void centred(GuiGraphicsExtractor g, String text, int y, int w) {
        for (var line : font.split(Component.literal(text), w)) {
            g.centeredText(font, line, width / 2, y, GREY);
            y += 10;
        }
    }
}
