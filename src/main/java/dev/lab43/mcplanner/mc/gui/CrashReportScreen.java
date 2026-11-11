package dev.lab43.mcplanner.mc.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.lab43.mcplanner.core.ApiException;
import dev.lab43.mcplanner.core.CrashReport;
import dev.lab43.mcplanner.mc.Links;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * "Crash report" (J menu, when Minecraft crashed with the mod in the crash, or the mod caught errors this session):
 * the whole report the mod would send to Lab43, redacted (core CrashReport), a note, and Send / Don't send. Nothing
 * leaves without Send; a failed send is only tried again when Send is pressed again.
 */
public final class CrashReportScreen extends BaseScreen {
    private static final int LINE_H = 10;

    private CrashReport report;
    private String failed;
    private boolean started;
    private List<FormattedCharSequence> lines = List.of();
    private int linesWidth = -1;
    private double scroll;
    private EditBox note;
    private boolean sending;
    private boolean sent;
    private String status;
    private int statusColor = GREY;

    public CrashReportScreen(Screen parent) {
        super("Crash report", parent);
    }

    @Override
    protected void init() {
        int l = left(), w = panelW();
        if (!started) {
            started = true;
            CompletableFuture<CrashReport> preparing;
            try {
                preparing = p.crashReports.prepare();
            } catch (RuntimeException e) { // never let a crash report break the menu
                preparing = CompletableFuture.failedFuture(e);
            }
            preparing.whenComplete((r, t) -> p.main.execute(() -> {
                if (t != null) failed = "Couldn't put the report together: " + ApiException.of(t).getMessage();
                else report = r;
                linesWidth = -1;
                requestRebuild();
            }));
        }
        button("Help", l + w - 40, 6, 40, "What's in a crash report and where it goes (mc-planner.com/academy)",
                b -> ConfirmLinkScreen.confirmLinkNow(this, Links.CRASH_REPORT, true));

        // a rebuild (the report arrived, a live plan update) keeps what was typed and where the keyboard was
        String keep = note == null ? "" : note.getValue();
        boolean typing = note != null && note.isFocused();
        note = new EditBox(font, l, height - 52, w, 20, Component.literal("Note"));
        note.setMaxLength(CrashReport.MAX_NOTE);
        note.setHint(Component.literal("What were you doing? (optional)"));
        note.setValue(keep);
        addRenderableWidget(note);
        if (typing) setInitialFocus(note);

        int half = (w - 3) / 2;
        Button send = button(sent ? "Sent ✔" : sending ? "Sending…" : "Send to Lab43", l, height - 26, half,
                "Sends this report to Lab43 (auth.lab43.dev), where it's kept for 90 days", b -> send());
        send.active = report != null && !sending && !sent;
        button(sent ? "Done" : "Don't send", l + half + 3, height - 26, w - half - 3, b -> {
            if (!sent) p.crashReports.done(); // declined: not offered again
            onClose();
        });
    }

    /** a rebuild (BaseScreen.tick) may move the focus on after init(); someone typing a note keeps it */
    @Override
    public void tick() {
        boolean typing = note != null && note.isFocused();
        super.tick();
        if (typing && note != null && !note.isFocused()) setInitialFocus(note);
    }

    private void send() {
        if (report == null || sending || sent) return;
        sending = true;
        status = "Sending…";
        statusColor = GREY;
        requestRebuild();
        p.crashReports.send(report, note.getValue()).whenComplete((res, err) -> p.main.execute(() -> {
            sending = false;
            if (err == null) {
                sent = true;
                status = "Sent, thank you.";
                statusColor = OK;
                p.crashReports.done();
            } else {
                status = ApiException.of(err).getMessage();
                statusColor = ERROR;
            }
            requestRebuild();
        }));
    }

    // ---------- the report: a scrolling block of text ----------

    private int top() {
        return 36;
    }

    private int bottom() {
        return height - 70;
    }

    private void layoutLines(int w) {
        if (report == null || linesWidth == w) return;
        linesWidth = w;
        lines = font.split(Component.literal(report.text().replace("\t", "    ")), w - 12);
    }

    private double maxScroll() {
        return Math.max(0, lines.size() * LINE_H + 8 - (bottom() - top()));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        int l = left(), w = panelW();
        if (x >= l && x < l + w && y >= top() && y < bottom()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * LINE_H * 3));
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        // Page Up / Down, Home and End scroll the report (the note keeps its own Home and End); the arrow keys stay
        // Minecraft's, which move between the buttons and the note
        if (note == null || !note.isFocused()) {
            double page = bottom() - top() - LINE_H * 2;
            Double to = switch (e.key()) {
                case 266 -> scroll - page; // page up
                case 267 -> scroll + page; // page down
                case 268 -> 0.0; // home
                case 269 -> maxScroll(); // end
                default -> null;
            };
            if (to != null) {
                scroll = Math.max(0, Math.min(maxScroll(), to));
                return true;
            }
        }
        return super.keyPressed(e);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        title(g, "This is the whole report. Nothing is sent unless you press Send.");
        int l = left(), w = panelW(), top = top(), bottom = bottom();
        g.fill(l - 1, top - 1, l + w + 1, bottom + 1, 0x40000000);
        if (report == null) {
            paragraph(g, failed != null ? failed : "Putting the report together…", l + 6, top + 6, w - 12, failed != null ? ERROR : GREY);
        } else {
            layoutLines(w);
            scroll = Math.max(0, Math.min(scroll, maxScroll()));
            g.enableScissor(l, top, l + w, bottom);
            int first = (int) (scroll / LINE_H);
            for (int i = first; i < lines.size(); i++) {
                int y = top + 4 + i * LINE_H - (int) scroll;
                if (y > bottom) break;
                g.text(font, lines.get(i), l + 6, y, i < 4 ? WHITE : GREY, false);
            }
            g.disableScissor();
            double max = maxScroll();
            if (max > 0) { // scroll bar
                int h = bottom - top;
                int barH = Math.max(12, (int) ((double) h * h / (lines.size() * LINE_H + 8)));
                int barY = top + (int) ((h - barH) * (scroll / max));
                g.fill(l + w - 3, barY, l + w - 1, barY + barH, 0x80FFFFFF);
            }
        }
        if (status != null) g.centeredText(font, Component.literal(status), width / 2, height - 64, statusColor);
    }
}
