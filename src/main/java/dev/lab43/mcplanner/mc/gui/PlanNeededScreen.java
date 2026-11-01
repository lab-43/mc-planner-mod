package dev.lab43.mcplanner.mc.gui;

import dev.lab43.mcplanner.core.Access;
import dev.lab43.mcplanner.core.WorldInfo;
import dev.lab43.mcplanner.mc.Links;
import dev.lab43.mcplanner.mc.Planner;
import dev.lab43.mcplanner.mc.Texts;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Why the chosen plan doesn't show, and what to do about it. The mod needs an active MC Planner plan: a plan file the
 * player's own (sign in → Start 7 days free or See plans → I have a plan → Check again), a shared world its owner's
 * (paused: the owner renews in the app). Says "All set" once the plan shows. Its words and buttons are the ones the
 * Academy describes (mc-planner.com/academy/mod/troubleshooting#plan-needed and #paused).
 */
public final class PlanNeededScreen extends BaseScreen {
    /** explain plan files (from the "Choose a plan" list), not what's shown now */
    private final boolean forFiles;
    private Planner.Gate shown;
    private boolean checkedOnOpen;
    private boolean wasChecking;

    /** why the chosen plan doesn't show */
    public PlanNeededScreen(Screen parent) {
        this(parent, false);
    }

    /** {@code forFiles}: why plan files don't open (whatever is shown now) */
    public PlanNeededScreen(Screen parent, boolean forFiles) {
        super("MC Planner", parent);
        this.forFiles = forFiles;
    }

    private Planner.Gate current() {
        return forFiles ? p.filesGate() : p.gate();
    }

    @Override
    public Component getTitle() {
        return Component.literal(titleFor(p == null ? Planner.Gate.NONE : current()));
    }

    static String titleFor(Planner.Gate g) {
        return switch (g) {
            case PAUSED -> "This world is paused";
            case NONE -> "MC Planner";
            default -> "Plan files need an MC Planner plan";
        };
    }

    @Override
    protected void init() {
        shown = current();
        wasChecking = p.accessChecking();
        int l = left(), w = panelW();
        int half = (w - 3) / 2;
        int y = height - 50;
        // a check that hasn't run yet (or ran long ago) starts when the screen opens — once, not on every rebuild
        if (!checkedOnOpen && (shown == Planner.Gate.CHECKING || shown == Planner.Gate.CHECK_AGAIN)) p.checkAgain();
        checkedOnOpen = true;
        String trial = Links.appPlans(p.config.apiUrl);

        switch (shown) {
            case NONE -> button("Done", l + (w - 120) / 2, height - 26, 120, b -> onClose());
            case SIGN_IN -> {
                button("Start 7 days free", l, y, half, "Opens MC Planner's plans in your browser — free, no card needed", b -> link(trial));
                button("See plans", l + half + 3, y, w - half - 3, "Cloud, Team and Lifetime on mc-planner.com", b -> link(Links.PLANS));
                button("Sign in", l, height - 26, half, "Sign in with your Lab43 account (auth.lab43.dev)", b -> minecraft.gui.setScreen(new SignInScreen(this)));
                button("Back", l + half + 3, height - 26, w - half - 3, b -> onClose());
            }
            case NEEDS_PLAN -> {
                Access a = p.access();
                if (a == null || a.trialAvailable()) {
                    button("Start 7 days free", l, y, half, "Opens MC Planner's plans in your browser — free, no card needed", b -> link(trial));
                } else {
                    button("Open MC Planner", l, y, half, "Opens MC Planner's plans in your browser", b -> link(trial));
                }
                button("See plans", l + half + 3, y, w - half - 3, "Cloud, Team and Lifetime on mc-planner.com", b -> link(Links.PLANS));
                checkAndBack("I have a plan → Check again", l, w, half);
            }
            case PAUSED -> {
                if (isOwner() && p.access() != null && p.access().active()) {
                    // the owner has a plan, but it keeps fewer worlds live: the app's "Choose which stay live"
                    String app = Links.app(p.config.apiUrl);
                    button("Choose live worlds", l, y, half, "Opens MC Planner in your browser", b -> link(app));
                } else if (isOwner()) {
                    button("Renew in the app", l, y, half, "Opens MC Planner's plans in your browser", b -> link(trial));
                } else {
                    button("Choose another plan…", l, y, half, b -> minecraft.gui.setScreen(new WorldsScreen(parent)));
                }
                button("See plans", l + half + 3, y, w - half - 3, "Cloud, Team and Lifetime on mc-planner.com", b -> link(Links.PLANS));
                checkAndBack("Check again", l, w, half);
            }
            case CHECKING, CHECK_AGAIN -> checkAndBack("Check again", l, w, half);
        }
        if (shown != Planner.Gate.NONE) {
            String help = shown == Planner.Gate.PAUSED ? Links.PAUSED : Links.PLAN_NEEDED;
            button("Help", l + w - 40, 6, 40, "What this means and what to do (mc-planner.com/academy)", b -> link(help));
        }
    }

    private void checkAndBack(String label, int l, int w, int half) {
        button(p.accessChecking() ? "Checking…" : label, l, height - 26, half, "Ask MC Planner again — e.g. after starting the trial", b -> {
            p.checkAgain();
            requestRebuild();
        }).active = !p.accessChecking();
        button("Back", l + half + 3, height - 26, w - half - 3, b -> onClose());
    }

    private boolean isOwner() {
        WorldInfo wi = p.world();
        return wi != null && "owner".equals(wi.role());
    }

    private void link(String url) {
        p.expectPlanSoon(); // back from the browser with a plan: the mod looks every minute for a while
        ConfirmLinkScreen.confirmLinkNow(this, url, true);
    }

    @Override
    public void tick() {
        super.tick();
        // the answer arrived, or the gate changed under us (e.g. the trial started in the browser): new text and buttons
        if (current() != shown || p.accessChecking() != wasChecking) requestRebuild();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        Planner.Gate gate = shown == null ? Planner.Gate.NONE : shown;
        title(g, gate == Planner.Gate.NONE ? null : "The mod works with an active MC Planner plan — yours, or your friend's");
        int l = left(), w = panelW();
        int y = 40;
        String what = forFiles || p.sourceName() == null ? null : (p.source() == Planner.Source.FILE ? "Plan file: " : "Shared world: ") + p.sourceName();
        if (what != null && gate != Planner.Gate.NONE) {
            g.text(font, Rows.clip(font, what, w), l, y, DIM, false);
            y += 14;
        }
        switch (gate) {
            case NONE -> {
                g.centeredText(font, Component.literal("All set ✔").withStyle(net.minecraft.ChatFormatting.BOLD), width / 2, height / 2 - 20, OK);
                g.centeredText(font, Component.literal(forFiles ? "Plan files open with your plan." : "Your plan shows in the world now."), width / 2, height / 2 - 6, WHITE);
            }
            case SIGN_IN -> {
                y = paragraph(g, "Plan files open in Minecraft with an MC Planner plan of your own: Cloud, Team, Lifetime, or the 7-day trial (free, no card needed).", l, y, w, WHITE) + 6;
                y = paragraph(g, "Sign in with your Lab43 account (the one you use for MC Planner), so the mod can check yours.", l, y, w, GREY) + 6;
                paragraph(g, "In a friend's shared world you don't need a plan: theirs covers you.", l, y, w, GREY);
            }
            case NEEDS_PLAN -> {
                Access ac = p.access();
                String plan = ac == null || ac.name() == null ? "Free" : ac.name();
                y = paragraph(g, "Plan files open in Minecraft with an MC Planner plan of your own: Cloud, Team, Lifetime, or the 7-day trial (free, no card needed, nothing to cancel).", l, y, w, WHITE) + 6;
                y = paragraph(g, "Your plan now: " + plan + (ac != null && !ac.trialAvailable() && "free".equals(ac.plan()) ? " (the trial was used)" : "") + ".", l, y, w, ACCENT) + 6;
                y = paragraph(g, "In a friend's shared world you don't need a plan: theirs covers you.", l, y, w, GREY) + 6;
                paragraph(g, "Started a plan in the browser? Press \"Check again\" — the mod also looks every minute while the file waits.", l, y, w, GREY);
            }
            case PAUSED -> {
                y = paragraph(g, p.gateMessage(gate), l, y, w, WHITE) + 6;
                if (isOwner() && p.access() != null && p.access().active()) {
                    y = paragraph(g, "Your plan keeps fewer worlds live than you have. In the app, choose which worlds stay live (or pick a bigger plan) and this one comes back on its own. Nothing was lost.", l, y, w, GREY) + 6;
                } else if (isOwner()) {
                    y = paragraph(g, "Renew your plan in the app (See plans there) and the world comes back on its own — for you and everyone you invited. Nothing was lost.", l, y, w, GREY) + 6;
                } else {
                    WorldInfo wi = p.world();
                    String owner = wi != null && wi.ownerName() != null ? wi.ownerName() : "the owner";
                    y = paragraph(g, "Shared worlds show while their owner's plan keeps them live. Only " + owner + " can change that; until then the mod draws nothing in this world. Nothing is lost.", l, y, w, GREY) + 6;
                }
                paragraph(g, "The mod looks again every few minutes.", l, y, w, DIM);
            }
            case CHECKING -> {
                String err = p.accessError();
                y = paragraph(g, err == null ? "Checking your MC Planner plan…" : "Couldn't check your MC Planner plan.", l, y, w, WHITE) + 6;
                if (err != null) paragraph(g, err, l, y, w, ERROR);
            }
            case CHECK_AGAIN -> {
                y = paragraph(g, p.gateMessage(gate), l, y, w, WHITE) + 6;
                y = paragraph(g, "Without internet, plan files keep working for up to 72 hours after the last check, and never after your plan ends.", l, y, w, GREY) + 6;
                if (p.accessError() != null) paragraph(g, p.accessError(), l, y, w, ERROR);
                else if (p.accessChecking()) paragraph(g, "Checking…", l, y, w, GREY);
            }
        }
        if (gate != Planner.Gate.NONE && gate != Planner.Gate.PAUSED && p.accessCheckedAt() > 0) {
            g.centeredText(font, Component.literal("Last check: " + Texts.ago(p.accessCheckedAt())), width / 2, height - 62, DIM);
        }
    }
}
