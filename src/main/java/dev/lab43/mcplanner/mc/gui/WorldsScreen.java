package dev.lab43.mcplanner.mc.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.lab43.mcplanner.core.AccessGate;
import dev.lab43.mcplanner.core.LocalPlanFile;
import dev.lab43.mcplanner.core.LocalPlans;
import dev.lab43.mcplanner.core.WorldInfo;
import dev.lab43.mcplanner.mc.Planner;
import dev.lab43.mcplanner.mc.Texts;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Pick what to show: one of your shared worlds (app.mc-planner.com, through your Lab43 account) or a plan file —
 * .mcplan files saved by the MC Planner app (Ctrl+S → Documents\MC Planner) or dropped onto this window.
 */
public final class WorldsScreen extends BaseScreen {
    private List<LocalPlanFile> files = List.of();
    private String note;

    public WorldsScreen(Screen parent) {
        super("Choose a plan", parent);
        files = LocalPlans.scan(LocalPlans.defaultFolders(p.gameDir));
    }

    @Override
    protected void init() {
        int l = left(), w = panelW();
        if (p.isSignedIn()) p.loadWorlds(false);
        rememberWorlds();

        rows = new Rows(l, 36, w, height - 36 - 64);
        if (p.isSignedIn()) {
            rows.add(new Rows.Row(0, "Shared worlds · " + p.accountName(), ACCENT, null, null, 0, null));
            if (p.worlds().isEmpty()) {
                String why = p.worldsLoading() ? "Loading…" : p.worldsError() != null ? p.worldsError()
                        : "None yet. Share a world in MC Planner (Plan together), or join one with an invite link.";
                rows.add(new Rows.Row(0, why, p.worldsError() != null ? ERROR : GREY, null, null, 0, null));
            }
            for (WorldInfo wi : p.worlds()) {
                boolean current = p.source() == Planner.Source.ROOM && wi.roomId().equals(p.roomId());
                boolean owner = "owner".equals(wi.role());
                String detail = Texts.roleLabel(wi.role()) + (wi.ownerName() != null && !owner ? " · " + wi.ownerName() + "'s world" : "")
                        + (wi.paused() ? (owner ? " · Paused — not one of your live worlds" : " · Paused — the owner's plan doesn't keep it live") : wi.canEdit() ? "" : " · read-only");
                // paused worlds: greyed out, nothing to show until the owner renews (the server answers 402)
                rows.add(new Rows.Row(wi.paused() ? 0xFF6E6E6E : 0xFF7BE07B, (current ? "● " : "") + (wi.name() == null ? "World" : wi.name()),
                        wi.paused() ? DIM : current ? ACCENT : WHITE, detail, wi.paused() ? "paused" : current ? "shown" : "Show",
                        wi.paused() ? ACCENT : current ? OK : GREY, () -> {
                            p.selectRoom(wi);
                            if (wi.paused()) minecraft.gui.setScreen(new PlanNeededScreen(parent));
                            else onClose();
                        }));
            }
        } else {
            rows.add(new Rows.Row(0, "Shared worlds", ACCENT, null, null, 0, null));
            rows.add(new Rows.Row(0, "Sign in to see the worlds you plan with friends.", GREY, "Click to sign in with your Lab43 account", "Sign in", ACCENT,
                    () -> minecraft.gui.setScreen(new SignInScreen(this))));
        }
        rows.add(new Rows.Row(0, "", 0, null, null, 0, null));
        rows.add(new Rows.Row(0, "Plan files", ACCENT, null, null, 0, null));
        AccessGate.State filesAccess = p.filesAccess();
        if (filesAccess != AccessGate.State.ALLOWED) {
            String why = switch (filesAccess) {
                case SIGN_IN -> "Plan files need your own MC Planner plan. Sign in so the mod can check it.";
                case CHECKING -> "Checking your MC Planner plan…";
                case CHECK_AGAIN -> "Your plan needs checking again (offline too long, or its end date passed).";
                default -> "Plan files need your own MC Planner plan: Cloud, Team, Lifetime or the free 7-day trial.";
            };
            rows.add(new Rows.Row(0xFFFFC857, "Plan files need an MC Planner plan", ACCENT, why, "Why?", ACCENT,
                    () -> minecraft.gui.setScreen(new PlanNeededScreen(this, true))));
        }
        if (files.isEmpty()) {
            rows.add(new Rows.Row(0, "No .mcplan files found yet.", GREY, "Save with Ctrl+S in MC Planner, or drop a file on this window.", null, 0, null));
        }
        for (LocalPlanFile f : files) {
            boolean current = p.source() == Planner.Source.FILE && f.path().toAbsolutePath().equals(p.file());
            rows.add(new Rows.Row(0xFF9AD0FF, (current ? "● " : "") + f.displayName(), current ? ACCENT : WHITE,
                    "Saved " + Texts.ago(f.modified()) + " · " + f.path().getParent().getFileName(), current ? "shown" : "Show",
                    current ? OK : GREY, () -> {
                        p.selectFile(f.path());
                        afterFile();
                    }));
        }

        int y = height - 50;
        int q = (w - 9) / 4;
        button("Refresh", l, y, q, "Load the list again", b -> {
            if (p.isSignedIn()) p.loadWorlds(true);
            files = LocalPlans.scan(LocalPlans.defaultFolders(p.gameDir));
            requestRebuild();
        });
        button("Open folder", l + q + 3, y, q, "Put .mcplan files here (" + LocalPlans.gamePlansFolder(p.gameDir) + ")", b -> {
            Path dir = LocalPlans.gamePlansFolder(p.gameDir);
            try {
                Files.createDirectories(dir);
            } catch (Exception ignored) {
                // opening shows the error
            }
            Util.getPlatform().openPath(dir);
        });
        var off = button("Show nothing", l + 2 * (q + 3), y, q, "Hide the plan in this world", b -> {
            p.clearSource(true);
            onClose();
        });
        off.active = p.source() != Planner.Source.NONE;
        button(p.isSignedIn() ? "Account…" : "Sign in", l + 3 * (q + 3), y, w - 3 * (q + 3), b -> minecraft.gui.setScreen(new SignInScreen(this)));
        button("Back", l + (w - 100) / 2, height - 26, 100, b -> onClose());
    }

    /** dropping a .mcplan (or exported .json) file onto the window loads it */
    @Override
    public void onFilesDrop(List<Path> paths) {
        for (Path path : paths) {
            String n = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!n.endsWith(".mcplan") && !n.endsWith(".json")) continue;
            try {
                Path dir = LocalPlans.gamePlansFolder(p.gameDir);
                Files.createDirectories(dir);
                Path copy = dir.resolve(path.getFileName());
                Files.copy(path, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                p.selectFile(copy);
                afterFile();
                return;
            } catch (Exception e) {
                note = "Couldn't copy " + path.getFileName() + ": " + e.getMessage();
            }
        }
        if (note == null) note = "Drop a .mcplan file from MC Planner.";
    }

    /** a plan file was chosen: back to the game, or to why it can't show yet */
    private void afterFile() {
        if (p.gate() != dev.lab43.mcplanner.mc.Planner.Gate.NONE) minecraft.gui.setScreen(new PlanNeededScreen(parent));
        else onClose();
    }

    // what the rows were built from: the world list arrives (or fails) a moment after the screen opens
    private List<WorldInfo> shownWorlds;
    private boolean shownLoading;
    private String shownError;

    private void rememberWorlds() {
        shownWorlds = p.worlds();
        shownLoading = p.worldsLoading();
        shownError = p.worldsError();
    }

    @Override
    public void tick() {
        super.tick();
        if (shownWorlds != p.worlds() || shownLoading != p.worldsLoading() || !java.util.Objects.equals(shownError, p.worldsError())) {
            requestRebuild();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        title(g, "Shared worlds from app.mc-planner.com, or a plan file");
        if (note != null) g.centeredText(font, net.minecraft.network.chat.Component.literal(note), width / 2, height - 62, ERROR);
    }
}
