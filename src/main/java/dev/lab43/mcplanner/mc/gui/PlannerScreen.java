package dev.lab43.mcplanner.mc.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.lab43.mcplanner.core.ModConfig;
import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.mc.Links;
import dev.lab43.mcplanner.mc.Planner;
import dev.lab43.mcplanner.mc.Texts;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;

/**
 * The mod's home (key J, Mod Menu, /mcplanner): account, which plan is shown, the overlay switches and filters, and
 * the plan's objects in this dimension, nearest first — click one for details, navigation and its status.
 */
public final class PlannerScreen extends BaseScreen {
    public PlannerScreen(Screen parent) {
        super("MC Planner", parent);
    }

    @Override
    protected void init() {
        ModConfig c = p.config;
        int l = left(), w = panelW(), y = 34;
        button("Help", l + w - 40, 6, 40, "How the mod works (mc-planner.com/academy)", b -> ConfirmLinkScreen.confirmLinkNow(this, Links.HELP, true));
        if (p.crashReports.available()) {
            button("Crash report", l, 6, 80, p.crashReports.isCrash()
                    ? "Minecraft crashed last time, with the mod in the crash. See the report, and send it to Lab43 if you like."
                    : "The mod hit an error this session. See the report, and send it to Lab43 if you like.",
                    b -> minecraft.gui.setScreen(new CrashReportScreen(this)));
        }

        // account
        if (p.isSignedIn()) button("Sign out", l + w - 76, y, 76, "Disconnect this device from your Lab43 account", b -> p.signOut());
        else button("Sign in", l + w - 76, y, 76, "Sign in with your Lab43 account (auth.lab43.dev)",
                b -> minecraft.gui.setScreen(new SignInScreen(this)));
        y += 24;
        // plan
        button(p.source() == Planner.Source.NONE ? "Choose…" : "Change…", l + w - 76, y, 76, b -> minecraft.gui.setScreen(new WorldsScreen(this)));
        y += 28;

        // switches
        int bw = (w - 9) / 4;
        button(onOff("Overlay", c.overlay), l, y, bw, "Show the plan in the world (key N)", b -> {
            c.overlay = !c.overlay;
            save(b, onOff("Overlay", c.overlay));
        });
        button(onOff("Labels", c.labels), l + (bw + 3), y, bw, b -> {
            c.labels = !c.labels;
            save(b, onOff("Labels", c.labels));
        });
        button("Display…", l + 2 * (bw + 3), y, bw, "Block textures, tiles and fill, see-through, distances, HUD", b -> minecraft.gui.setScreen(new DisplayScreen(this)));
        button("Refresh", l + 3 * (bw + 3), y, bw, "Load the newest version of the plan now", b -> p.refresh()).active = p.source() != Planner.Source.NONE;
        y += 24;

        // filters: kinds
        button(check("Paths", c.showPaths), l, y, bw, b -> { c.showPaths = !c.showPaths; save(b, check("Paths", c.showPaths)); });
        button(check("Areas", c.showAreas), l + (bw + 3), y, bw, b -> { c.showAreas = !c.showAreas; save(b, check("Areas", c.showAreas)); });
        button(check("Buildings", c.showBuildings), l + 2 * (bw + 3), y, bw, b -> { c.showBuildings = !c.showBuildings; save(b, check("Buildings", c.showBuildings)); });
        button(check("Markers", c.showMarkers), l + 3 * (bw + 3), y, bw, b -> { c.showMarkers = !c.showMarkers; save(b, check("Markers", c.showMarkers)); });
        y += 22;
        // filters: status
        button(check("Ideas", c.showIdea), l, y, bw, b -> { c.showIdea = !c.showIdea; save(b, check("Ideas", c.showIdea)); });
        button(check("Planned", c.showPlanned), l + (bw + 3), y, bw, b -> { c.showPlanned = !c.showPlanned; save(b, check("Planned", c.showPlanned)); });
        button(check("In progress", c.showInProgress), l + 2 * (bw + 3), y, bw, b -> { c.showInProgress = !c.showInProgress; save(b, check("In progress", c.showInProgress)); });
        button(check("Done", c.showDone), l + 3 * (bw + 3), y, bw, b -> { c.showDone = !c.showDone; save(b, check("Done", c.showDone)); });
        y += 28;

        // the objects
        int bottom = height - 30;
        rows = new Rows(l, y + 12, w, bottom - y - 18);
        fillRows();

        // bottom bar
        int third = (w - 6) / 3;
        var marker = button("+ Marker here", l, height - 26, third, p.canEdit() ? "Add a marker where you stand" : p.whyReadOnly(),
                b -> minecraft.gui.setScreen(new MarkerScreen(this)));
        marker.active = p.canEdit() && minecraft.player != null;
        var web = button("Open in browser", l + third + 3, height - 26, third, "Open this plan on app.mc-planner.com", b -> {
            String url = p.config.apiUrl;
            ConfirmLinkScreen.confirmLinkNow(this, url.endsWith("/") ? url : url + "/", true);
        });
        web.active = p.source() == Planner.Source.ROOM;
        button("Done", l + 2 * (third + 3), height - 26, w - 2 * (third + 3), b -> onClose());
    }

    private void save(net.minecraft.client.gui.components.Button b, String label) {
        b.setMessage(net.minecraft.network.chat.Component.literal(label));
        p.saveConfig();
        requestRebuild();
    }

    private void fillRows() {
        // a paused world shows read-only: say so above its objects
        String paused = p.pausedNotice();
        if (paused != null) {
            rows.add(new Rows.Row(0xFFFFC857, "Paused: read-only", ACCENT, paused, "Why?", ACCENT,
                    () -> ConfirmLinkScreen.confirmLinkNow(this, Links.PAUSED, true)));
        }
        if (p.source() == Planner.Source.NONE) {
            rows.empty = p.isSignedIn()
                    ? "No plan shown. Press \"Choose…\" to pick one of your shared worlds or a .mcplan file."
                    : "No plan shown. Sign in with your Lab43 account to see your shared worlds from app.mc-planner.com, or press \"Choose…\" to open a .mcplan file.";
            return;
        }
        List<PlanObject> here = p.here();
        if (here.isEmpty()) {
            rows.empty = p.plan().isEmpty()
                    ? (p.error() != null ? p.error() : "Loading the plan…")
                    : "Nothing planned in this dimension (" + p.plan().size() + " objects elsewhere).";
            return;
        }
        Vec3 pos = minecraft.player == null ? Vec3.ZERO : minecraft.player.position();
        float yaw = minecraft.player == null ? 0 : minecraft.player.getYRot();
        List<PlanObject> list = new ArrayList<>();
        int hidden = 0;
        for (PlanObject o : here) {
            if (p.config.isVisible(o)) list.add(o);
            else hidden++;
        }
        list.sort(Comparator.comparingDouble(o -> dist(o, pos)));
        PlanObject target = p.navTarget();
        for (PlanObject o : list) {
            double[] c = centre(o);
            double dx = c[0] - pos.x, dz = c[1] - pos.z;
            String right = Texts.arrow(dx, dz, yaw) + " " + Texts.distance(Math.hypot(dx, dz));
            boolean isTarget = target != null && target.id.equals(o.id);
            rows.add(new Rows.Row(0xFF000000 | o.color, (isTarget ? "➤ " : "") + o.name,
                    isTarget ? ACCENT : WHITE, o.kind.label() + " · " + o.status.label() + " · " + o.describe(), right,
                    Texts.statusColor(o.status), () -> minecraft.gui.setScreen(new ObjectScreen(this, o.id))));
        }
        if (hidden > 0) rows.add(new Rows.Row(0, hidden + " hidden by the filters above", DIM, null, null, 0, null));
        if (list.isEmpty()) rows.empty = "Everything here is hidden by the filters.";
    }

    static double[] centre(PlanObject o) {
        var b = o.bounds();
        if (b == null) return new double[] {0, 0};
        return new double[] {(b.x0() + b.x1() + 1) / 2.0, (b.z0() + b.z1() + 1) / 2.0};
    }

    private static double dist(PlanObject o, Vec3 pos) {
        var b = o.bounds();
        if (b == null) return Double.MAX_VALUE;
        double dx = Math.max(0, Math.max(b.x0() - pos.x, pos.x - (b.x1() + 1)));
        double dz = Math.max(0, Math.max(b.z0() - pos.z, pos.z - (b.z1() + 1)));
        return Math.hypot(dx, dz);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        title(g, null);
        int l = left(), w = panelW(), y = 34;
        // account line
        if (p.isSignedIn()) {
            g.text(font, "Lab43 account", l, y + 1, DIM, false);
            g.text(font, Rows.clip(font, p.accountName(), w - 90), l, y + 11, WHITE, false);
        } else {
            g.text(font, "Lab43 account", l, y + 1, DIM, false);
            g.text(font, "Not signed in", l, y + 11, GREY, false);
        }
        y += 24;
        // plan line
        g.text(font, "Plan", l, y + 1, DIM, false);
        if (p.source() == Planner.Source.NONE) {
            g.text(font, "None", l, y + 11, GREY, false);
        } else {
            String name = p.sourceName() == null ? "…" : p.sourceName();
            StringBuilder sb = new StringBuilder();
            if (p.source() == Planner.Source.FILE) sb.append("File");
            else {
                if (p.role() != null) sb.append(Texts.roleLabel(p.role()));
                if (p.isPaused()) sb.append(sb.length() > 0 ? " · " : "").append("paused");
            }
            sb.append(sb.length() > 0 ? " · " : "").append(Planner.countLabel(p.plan()));
            if (p.planAt() > 0) sb.append(" · updated ").append(Texts.ago(p.planAt()));
            int nameW = font.width(name) + 6;
            g.text(font, Rows.clip(font, name, w - 90), l, y + 11, WHITE, false);
            int detailX = l + Math.min(nameW, w - 90);
            if (detailX < l + w - 90) g.text(font, Rows.clip(font, sb.toString(), l + w - 84 - detailX), detailX, y + 11, GREY, false);
        }
        String err = p.error();
        if (err != null) g.text(font, Rows.clip(font, "⚠ " + err, w), l, y + 21, ERROR, false);
        // list header
        if (rows != null) g.text(font, "In this dimension · nearest first", l, rows.y - 11, DIM, false);
    }
}
