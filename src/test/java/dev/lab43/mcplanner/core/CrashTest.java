package dev.lab43.mcplanner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

/** Crash reports: redaction, finding the crash and the log lines, the report and its request, sending it once. */
final class CrashTest {
    private CrashTest() {}

    static final String PLAYER = "RobinCraft";
    static final UUID PLAYER_ID = UUID.fromString("0f9a8b7c-6d5e-4f3a-8b1c-0d9e8f7a6b5c");
    static final String HOME = "C:\\Users\\Robin Smith";
    static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyb2JpbiJ9.c2lnbmF0dXJlLXBhcnQ";

    static final String CRASH = String.join("\n",
        "---- Minecraft Crash Report ----",
        "// Don't be sad, have a hug! <3",
        "",
        "Time: 2026-10-14 12:34:56",
        "Description: Rendering overlay",
        "",
        "java.lang.NullPointerException: Cannot invoke \"dev.lab43.mcplanner.core.PlanObject.bounds()\" because \"o\" is null",
        "\tat dev.lab43.mcplanner.mc.render.Overlay.submit(Overlay.java:210) ~[mcplanner-0.2.0.jar:?]",
        "\tat net.minecraft.client.renderer.LevelRenderer.renderLevel(LevelRenderer.java:1234) ~[client-intermediary.jar:?]",
        "",
        "",
        "A detailed walkthrough of the error, its code path and all known details is as follows:",
        "---------------------------------------------------------------------------------------",
        "",
        "-- Affected level --",
        "Details:",
        "\tAll players: 1 total; [LocalPlayer['RobinCraft'/42, l='ClientLevel', x=10.50, y=64.00, z=-3.25]]",
        "\tLevel name: Robin's Base",
        "\tLevel: ServerLevel[Robin's Base]",
        "",
        "-- System Details --",
        "Details:",
        "\tMinecraft Version: 26.2",
        "\tOperating System: Windows 11 (amd64) version 10.0",
        "\tJava Version: 25.0.1, Eclipse Adoptium",
        "\tBackend API: NVIDIA GeForce RTX 3060/PCIe/SSE2 GL version 4.6.0 NVIDIA 560.94, NVIDIA Corporation",
        "\tJVM Flags: 2 total; -Xmx4G -Djava.library.path=C:\\Users\\Robin Smith\\AppData\\Roaming\\.minecraft\\bin\\abc",
        "\tFabric Mods:",
        "\t\tfabric-api: Fabric API 0.160.0+26.2",
        "\t\tmcplanner: MC Planner 0.2.0",
        "\tLauncher args: --username RobinCraft --uuid 0f9a8b7c6d5e4f3a8b1c0d9e8f7a6b5c --accessToken " + JWT + " --gameDir C:\\Users\\Robin Smith\\AppData\\Roaming\\.minecraft",
        "\tServer: Connecting to 85.12.3.4, 25565",
        "\tContact: robin@example.com",
        "\tWorld folder: C:/Users/Robin Smith/AppData/Roaming/.minecraft/saves/Robins Base/region",
        "\tHome: /home/robin/.minecraft and /Users/robin/Library/Application Support/minecraft",
        "");

    static void run() throws Exception {
        redaction();
        sessionErrors();
        crashFiles();
        knownNames();
        report();
        config();
        sender();
    }

    static Redactor redactor() {
        return Redactor.builder().home(HOME).player(PLAYER).uuid(PLAYER_ID).world("Robin's Base").world("Robins Base")
            .server("Robin's SMP").server("play.robin-smp.net:25565").name("Secret Castle").name("ab").world("New World").build();
    }

    static void redaction() {
        Redactor r = redactor();
        eq("<player> joined, <uuid> / <uuid>", r.apply("robincraft joined, " + PLAYER_ID + " / " + PLAYER_ID.toString().replace("-", "")), "player name (any case) and UUID");
        eq("RobinCrafty stays", r.apply("RobinCrafty stays"), "never inside another word");
        eq("~\\AppData\\Roaming and ~/AppData and ~\\\\AppData", r.apply(HOME + "\\AppData\\Roaming and C:/Users/Robin Smith/AppData and C:\\\\Users\\\\Robin Smith\\\\AppData"), "home folder: both slashes and JSON-escaped");
        eq("~\\Desktop and ~/.minecraft and ~/Library", r.apply("D:\\Users\\other\\Desktop and /home/someone/.minecraft and /Users/mac/Library"), "other home folders by their shape");
        eq("--username <redacted> --uuid <redacted> --accessToken <redacted>", r.apply("--username Robby --uuid abc --accessToken " + JWT), "launcher arguments");
        eq("<token> · Bearer <token> · <token> · token=<redacted> · <email>", r.apply(JWT + " · Bearer abc.def · l43d_SecretDevice9 · token=hunter2 · robin@example.com"), "tokens, keys, emails");
        eq("saves/<world>/region", r.apply("saves/My Secret World/region"), "saves/<world>");
        eq("~\\AppData\\Roaming\\PrismLauncher\\instances\\<profile>\\.minecraft", r.apply(HOME + "\\AppData\\Roaming\\PrismLauncher\\instances\\Robby's pack\\.minecraft"), "launcher profile names");
        eq("Resource Packs: vanilla, fabric, file/<file>, file/<file>", r.apply("Resource Packs: vanilla, fabric, file/Robby's pack.zip, file/Other (incompatible)"), "resource pack files");
        eq("read ~/Documents/MC Planner/<file>.mcplan and Downloads/<file>.litematic", r.apply("read /home/x/Documents/MC Planner/Big Base.mcplan and Downloads/castle v2.litematic"), "plan and schematic file names");
        eq("a profile/file/x stays, mcplanner.mcplan.json stays", r.apply("a profile/file/x stays, mcplanner.mcplan.json stays"), "only whole names");
        eq("~\\Desktop and saves/<world>/x", r.apply("C:\\Users\\O'Brien\\Desktop and saves/Robby's World/x"), "names with an apostrophe");
        eq("\tLevel name: <redacted>", r.apply("\tLevel name: Hidden Valley"), "Level name: …");
        eq("ServerLevel[<world>] · Preparing level \"<world>\" · Connecting to <server>, 25565", r.apply("ServerLevel[Hidden] · Preparing level \"Hidden\" · Connecting to mc.example.org, 25565"), "where Minecraft writes world and server names");
        eq("LocalPlayer['<player>'/42 · Setting user: <player>", r.apply("LocalPlayer['Someone'/42 · Setting user: Someone"), "player names by their shape");
        eq("<server> (<server>) at <server>, port 25565", r.apply("Robin's SMP (play.robin-smp.net:25565) at play.robin-smp.net, port 25565"), "server names and addresses (with or without the port)");
        eq("<ip>:25565, <ip> and 127.0.0.1 0.0.0.0", r.apply("192.168.1.20:25565, 85.12.3.4 and 127.0.0.1 0.0.0.0"), "IPv4 (not loopback)");
        eq("Java 21.0.12.1 · version 1.2.3.4 · Fabric/0.19.1.2", r.apply("Java 21.0.12.1 · version 1.2.3.4 · Fabric/0.19.1.2"), "version numbers aren't addresses");
        eq("http://<ip>:8787/api · recv <ip>", r.apply("http://85.12.3.4:8787/api · recv 10.0.0.7"), "an address in a link is still an address");
        eq("<ip> / <ip> / ::1", r.apply("2001:0db8:85a3:0000:0000:8a2e:0370:7334 / fe80::1ff:fe23:4567:890a / ::1"), "IPv6");
        eq("[12:34:56] Foo::bar", r.apply("[12:34:56] Foo::bar"), "times and method references stay");
        eq("<hex> · <token>", r.apply("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 · aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY5zA7bC9"), "long hex and token-like runs");
        eq("red · tab\tnew\nline", r.apply("\u001B[31mred\u001B[0m · tab\tnew\nline\u0007"), "control characters and colours");
        eq("<name> and a cab and world and New World stays? <world>", r.apply("Secret Castle and a cab and world and New World stays? Robin's Base"),
            "everyday words and 2-letter names stay; 'New World' is a default name");
        eq("~/x <email>", Redactor.basic().apply("/home/u/x a@b.co"), "basic(): the shapes only");
        // a known name inside a path or file name takes the whole name with it, never leaving the rest behind
        eq("D:\\x\\<file>.mcplan", r.apply("D:\\x\\Robins Base Village.mcplan"), "a plan file holding a name");
        eq("D:\\Minecraft Worlds\\saves\\<world>\\level.dat", r.apply("D:\\Minecraft Worlds\\saves\\Robin's Base 2\\level.dat"), "a world folder holding a name");
        eq("saves/<world>/region and <server>", r.apply("saves/Robins Base 2/region and Robin's SMP"), "labels still come out where nothing else takes them");
        eq("ab", r.apply("a\uE002b"), "no stray marks");
        // names that are also key or folder words don't break the rules for secrets and homes; an email with a name
        Redactor words = Redactor.builder().world("Server").world("Users").name("Token").name("castle").build();
        // (the values go first; a key word may then read as a name, which is harmless)
        eq("--server <redacted> <name>=<redacted> ~ <email>",
            words.apply("--server play.robin.net token=hunter2 C:\\Users\\robin castle@robinsmith.co.uk"),
            "secrets and homes go before the names");
        eq("timed out at <ip>.", r.apply("timed out at 10.0.0.5."), "an address before a full stop");
        // only a real frame keeps its names
        Redactor castle = Redactor.builder().name("Castle").build();
        eq("x\n\tat <name>(Robin's thing)\n\tat a.Castle.run(Castle.java:3)", castle.apply("x\n\tat Castle(Robin's thing)\n\tat a.Castle.run(Castle.java:3)"), "a line that only looks like a frame loses its names");
        // a stack's frames keep the code's own names, even when a plan or world is called like them
        Redactor code = Redactor.builder().home(HOME).name("core").world("render").build();
        String stack = "java.lang.IllegalStateException: render failed in core at " + HOME + "\\x\n\tat dev.lab43.mcplanner.core.Overlay.render(Overlay.java:12) ~[mcplanner.jar:?]\n\tat dev.lab43.mcplanner.core.X.run(Unknown Source)\n";
        eq("java.lang.IllegalStateException: <world> failed in <name> at ~\\x\n\tat dev.lab43.mcplanner.core.Overlay.render(Overlay.java:12) ~[mcplanner.jar:?]\n\tat dev.lab43.mcplanner.core.X.run(Unknown Source)\n",
            code.apply(stack), "frames keep their names; the message and the home folder don't");

        // a long input with many names is quick (patterns can't backtrack)
        Redactor.Builder many = Redactor.builder().home(HOME);
        for (int i = 0; i < 1000; i++) many.world("World number " + i);
        Redactor big = many.build();
        String text = ("x".repeat(500_000)) + " World number 999 " + "a".repeat(100_000) + "@";
        long t0 = System.nanoTime();
        String out = big.apply(text);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(out.contains(" <world> ") && !out.contains("World number"), "a name in a long text is found");
        check(ms < 5000, "600 KB with 1000 names in " + ms + " ms");
    }

    static void sessionErrors() {
        SessionErrors errors = new SessionErrors();
        errors.setRedactor(redactor());
        IllegalStateException e = new IllegalStateException("couldn't draw Secret Castle for RobinCraft", new RuntimeException("cause at " + HOME + "\\x"));
        errors.note("overlay", e);
        errors.note("overlay", e);
        errors.note("textures", new RuntimeException("atlas"));
        errors.note("textures", null);
        List<SessionErrors.Entry> list = errors.entries();
        eq(2, list.size(), "the same error counts up");
        eq(2, list.get(0).count(), "count");
        eq("overlay", list.get(0).where(), "where");
        String text = list.get(0).text();
        check(text.startsWith("java.lang.IllegalStateException: couldn't draw <name> for <player>"), "redacted when noted: " + text.split("\n")[0]);
        check(text.contains("\n\tat dev.lab43.mcplanner.core.CrashTest.sessionErrors(CrashTest.java:"), "with its frames");
        check(text.contains("\nCaused by: java.lang.RuntimeException: cause at ~\\x"), "and its cause, redacted");
        for (int i = 0; i < 30; i++) errors.note("w" + i, new RuntimeException("e" + i));
        eq(SessionErrors.MAX_ENTRIES, errors.entries().size(), "at most 20 kept");
        eq("w29", errors.entries().get(SessionErrors.MAX_ENTRIES - 1).where(), "the newest kept");
        errors.clear();
        check(errors.isEmpty(), "clear");
        RuntimeException loop = new RuntimeException("loop");
        loop.initCause(new RuntimeException("inner"));
        errors.note("x", loop);
        check(!errors.isEmpty(), "never throws");
    }

    static void crashFiles() throws Exception {
        Path dir = TestMain.tempDir("crash");
        try {
            Path crashes = Files.createDirectories(dir.resolve("crash-reports"));
            long now = System.currentTimeMillis();
            Path old = write(crashes.resolve("crash-2026-10-10_10.00.00-client.txt"), CRASH, now - 400_000);
            Path mine = write(crashes.resolve("crash-2026-10-14_12.34.56-client.txt"), CRASH, now - 200_000);
            String notMine = CRASH.replace("dev.lab43.mcplanner", "com.example.othermod").replace("[mcplanner-0.2.0.jar", "[othermod.jar");
            Path other = write(crashes.resolve("crash-2026-10-14_13.00.00-client.txt"), notMine, now - 100_000);
            write(crashes.resolve("notes.txt"), CRASH, now);
            CrashFiles.Found f = CrashFiles.newest(crashes, 0);
            check(f != null && f.file().equals(mine), "the newest crash that mentions the mod (not the newer one without it)");
            eq(CRASH, f == null ? null : f.text(), "its text");
            eq("crash-2026-10-14_12.34.56-client.txt", f == null ? null : f.fileName(), "its file name");
            eq(null, CrashFiles.newest(crashes, f == null ? 0 : f.modified()), "nothing newer than the last one offered");
            check(CrashFiles.newest(crashes, Files.getLastModifiedTime(old).toMillis() - 1) != null, "older ones count while not offered");
            eq(null, CrashFiles.newest(dir.resolve("nope"), 0), "no folder");
            check(Files.exists(other), "files are only read");

            check(CrashFiles.mentionsMod(CRASH), "a frame of the mod");
            check(!CrashFiles.mentionsMod(notMine), "only in the list of installed mods: not about the mod");
            check(CrashFiles.mentionsMod("Description: Initializing game\n\nCould not execute entrypoint stage 'client' due to errors, provided by 'mcplanner'"), "a loader message about the mod");
            check(CrashFiles.mentionsMod("x\n\tat a.B.c(B.java:1) ~[mcplanner-0.3.0.jar:?]"), "the mod's jar in a frame");
            check(!CrashFiles.mentionsMod(null), "null");
            eq("java.lang.NullPointerException: Cannot invoke \"dev.lab43.mcplanner.core.PlanObject.bounds()\" because \"o\" is null\n"
                + "\tat dev.lab43.mcplanner.mc.render.Overlay.submit(Overlay.java:210) ~[mcplanner-0.2.0.jar:?]\n"
                + "\tat net.minecraft.client.renderer.LevelRenderer.renderLevel(LevelRenderer.java:1234) ~[client-intermediary.jar:?]",
                CrashFiles.stackOf(CRASH), "the exception and its stack");

            List<String> log = List.of(
                "[12:00:00] [Render thread/INFO]: Setting user: RobinCraft",
                "[12:00:01] [Render thread/INFO]: MC Planner: showing a schematic (e3b0c442)",
                "[12:00:02] [Render thread/WARN]: MC Planner overlay couldn't draw",
                "java.lang.IllegalStateException: boom",
                "\tat dev.lab43.mcplanner.mc.render.Overlay.submit(Overlay.java:10)",
                "\tat net.minecraft.Foo.bar(Foo.java:1)",
                "[12:00:03] [Render thread/INFO]: [System] [CHAT] <Friend> is mcplanner any good?",
                "[12:00:04] [Render thread/ERROR]: Reported exception thrown!",
                "java.lang.RuntimeException: x",
                "\tat dev.lab43.mcplanner.mc.Hud.extract(Hud.java:5)",
                "\tat net.minecraft.Gui.render(Gui.java:9)",
                "[14Oct2026 12:00:05.123] [Render thread/INFO] [mcplanner/]: Plan loaded");
            List<String> lines = CrashFiles.modLines(log, 200);
            eq(List.of(
                "[12:00:01] [Render thread/INFO]: MC Planner: showing a schematic (e3b0c442)",
                "[12:00:02] [Render thread/WARN]: MC Planner overlay couldn't draw",
                "java.lang.IllegalStateException: boom",
                "\tat dev.lab43.mcplanner.mc.render.Overlay.submit(Overlay.java:10)",
                "\tat net.minecraft.Foo.bar(Foo.java:1)",
                "\tat dev.lab43.mcplanner.mc.Hud.extract(Hud.java:5)",
                "[14Oct2026 12:00:05.123] [Render thread/INFO] [mcplanner/]: Plan loaded"), lines, "the mod's lines with their stack traces, never chat");
            eq(2, CrashFiles.modLines(log, 2).size(), "only the last ones");
            check(CrashFiles.modLines(List.of("[1:00:00] [x]: MC Planner " + "y".repeat(1000)), 5).get(0).length() <= 300, "long lines cut");

            Path logs = Files.createDirectories(dir.resolve("logs"));
            Files.writeString(logs.resolve("latest.log"), "[13:00:00] [Render thread/INFO]: MC Planner: this session\n[13:00:01] [main/INFO]: other\n");
            gz(logs.resolve("2026-10-14-2.log.gz"), "[12:30:00] [Render thread/INFO]: MC Planner: older session\n", now - 50_000);
            gz(logs.resolve("2026-10-14-1.log.gz"), "[12:00:01] [Render thread/WARN]: MC Planner overlay couldn't draw\n"
                + "[12:34:56] [Render thread/ERROR]: #@!@# Game crashed! Crash report saved to: #@!@# C:\\x\\crash-reports\\crash-2026-10-14_12.34.56-client.txt\n", now - 60_000);
            eq(List.of("[12:00:01] [Render thread/WARN]: MC Planner overlay couldn't draw"),
                CrashFiles.logLines(logs, "crash-2026-10-14_12.34.56-client.txt", 200), "the log of the crashed session (it names the crash file)");
            eq(List.of("[13:00:00] [Render thread/INFO]: MC Planner: this session"), CrashFiles.logLines(logs, null, 200), "this session: latest.log");
            eq(List.of(), CrashFiles.logLines(logs, "crash-1999-01-01_00.00.00-client.txt", 200), "no log names the crash: none");
            eq(List.of(), CrashFiles.logLines(dir.resolve("nope"), null, 200), "no logs folder");
        } finally {
            TestMain.deleteTree(dir);
        }
    }

    static void knownNames() throws Exception {
        Path dir = TestMain.tempDir("names");
        try {
            Files.createDirectories(dir.resolve("saves").resolve("Robins Base"));
            Files.createDirectories(dir.resolve("saves").resolve("Creative test"));
            Files.writeString(dir.resolve("saves").resolve("not-a-world.txt"), "x");
            List<String> saves = new ArrayList<>(KnownNames.saves(dir.resolve("saves")));
            saves.sort(null);
            eq(List.of("Creative test", "Robins Base"), saves, "the folders in saves/");
            Files.write(dir.resolve("servers.dat"), serversDat(new String[][] {{"Robin's SMP", "play.robin-smp.net:25565"}, {"Hypixel", "mc.hypixel.net"}}));
            eq(List.of("Robin's SMP", "play.robin-smp.net:25565", "Hypixel", "mc.hypixel.net"), KnownNames.servers(dir.resolve("servers.dat")), "names and addresses in servers.dat");
            Files.writeString(dir.resolve("broken.dat"), "not nbt");
            eq(List.of(), KnownNames.servers(dir.resolve("broken.dat")), "unreadable servers.dat: none");
            eq(List.of(), KnownNames.saves(dir.resolve("nope")), "no saves folder");
        } finally {
            TestMain.deleteTree(dir);
        }
    }

    static CrashReport.Facts facts() {
        return new CrashReport.Facts("0.2.0", "26.2", "0.19.1", "0.160.0+26.2", "25.0.1 (Eclipse Adoptium)", "Windows 11 10.0 amd64",
            "NVIDIA GeForce RTX 3060/PCIe/SSE2", 723, new ModConfig().reportSettings());
    }

    static void report() {
        Redactor r = redactor();
        SessionErrors errors = new SessionErrors();
        errors.setRedactor(r);
        errors.note("overlay", new IllegalStateException("couldn't prepare a building"));
        List<String> log = List.of("[12:00:01] [Render thread/WARN]: MC Planner: couldn't load C:\\Users\\Robin Smith\\Documents\\MC Planner\\Secret Castle.mcplan");
        CrashReport c = CrashReport.ofCrash("crash-2026-10-14_12.34.56-client.txt", CRASH, errors.entries(), log, facts(), r);
        JsonObject body = c.body("  I flew to the castle  ");
        String all = body.toString();
        for (String bad : new String[] {"robin", "0f9a8b7c", JWT, "85.12.3.4", "Secret Castle", "Smith"}) {
            check(!all.toLowerCase(Locale.ROOT).contains(bad.toLowerCase(Locale.ROOT)), "not in the report: " + bad);
        }
        eq(CrashReport.Kind.CRASH, c.kind(), "kind");
        eq("java.lang.NullPointerException: Cannot invoke \"dev.lab43.mcplanner.core.PlanObject.bounds()\" because \"o\" is null", c.title(), "title: the exception");
        eq("at dev.lab43.mcplanner.mc.render.Overlay.submit(Overlay.java)", c.fingerprint(), "fingerprint: the top frame without line or jar");
        List<String> keys = new ArrayList<>(body.keySet());
        eq(List.of("app", "component", "kind", "title", "fingerprint", "appVersion", "platform", "report", "details", "note"), keys, "the request's fields");
        eq("mcplanner", body.get("app").getAsString(), "app");
        eq("mod", body.get("component").getAsString(), "component");
        eq("crash", body.get("kind").getAsString(), "kind on the wire");
        eq("0.2.0", body.get("appVersion").getAsString(), "appVersion: the mod's version");
        eq("Windows 11 10.0 amd64 · Java 25.0.1 (Eclipse Adoptium) · Minecraft 26.2 · Fabric 0.19.1", body.get("platform").getAsString(), "platform");
        eq("I flew to the castle", body.get("note").getAsString(), "the note, trimmed");
        eq(c.text(), body.get("report").getAsString(), "report = the text shown");
        String text = c.text();
        for (String want : new String[] {"MC Planner mod crash report\nWhat: Minecraft crashed: crash-2026-10-14_12.34.56-client.txt\n",
            "Minecraft: 26.2 · Fabric Loader 0.19.1 · Fabric API 0.160.0+26.2", "GPU: NVIDIA GeForce RTX 3060/PCIe/SSE2", "This session: running for 12 min 3 s",
            "Settings: overlay on · labels on", "HUD top_left", "sign-in server default", "Errors the mod caught this session (1):", "overlay: java.lang.IllegalStateException: couldn't prepare a building",
            "Minecraft's crash report:\n---- Minecraft Crash Report ----", "LocalPlayer['<player>'/42", "Level name: <redacted>", "--accessToken <redacted>", "Connecting to <server>, 25565",
            "The log's last lines about the mod (1):\n[12:00:01] [Render thread/WARN]: MC Planner: couldn't load ~\\Documents\\MC Planner\\<file>.mcplan"}) {
            check(text.contains(want), "the report has: " + want);
        }
        JsonObject d = body.getAsJsonObject("details");
        eq("crash-2026-10-14_12.34.56-client.txt", d.get("crashFile").getAsString(), "details.crashFile");
        eq(723L, d.get("uptimeS").getAsLong(), "details.uptimeS");
        eq("on", d.getAsJsonObject("settings").get("overlay").getAsString(), "details.settings");
        check(d.get("stack").getAsString().startsWith("java.lang.NullPointerException"), "details.stack");
        eq(1, d.getAsJsonArray("log").size(), "details.log");
        eq(1, d.getAsJsonArray("errors").size(), "details.errors");
        check(!c.body("   ").has("note"), "no note when empty");
        eq(2000, c.body("n".repeat(5000)).get("note").getAsString().length(), "the note is cut at 2000");

        // a huge crash report and log still fit the request
        String huge = CRASH.replace("-- System Details --", ("\tat x.Y.z(Y.java:1) " + "q".repeat(80) + "\n").repeat(5000) + "-- System Details --");
        List<String> longLog = new ArrayList<>();
        for (int i = 0; i < 200; i++) longLog.add("[12:00:00] [Render thread/WARN]: MC Planner " + "z".repeat(290));
        CrashReport h = CrashReport.ofCrash("crash-x.txt", huge, errors.entries(), longLog, facts(), r);
        JsonObject hb = h.body("note");
        check(h.text().length() <= CrashReport.MAX_REPORT, "report ≤ 200,000 characters");
        check(h.text().contains("characters left out"), "the middle of a huge crash is left out");
        check(hb.getAsJsonObject("details").toString().getBytes(StandardCharsets.UTF_8).length <= 100_000, "details ≤ 100 KB");
        check(hb.toString().getBytes(StandardCharsets.UTF_8).length < 400_000, "body < 400 KB");
        // 200,000 characters of three bytes each would be 600 KB: the text is shortened before it's shown, and what's
        // sent is what's shown, a long note included
        String wide = CRASH.replace("-- System Details --", ("\tat x.Y.z(Y.java:1) " + "€".repeat(80) + "\n").repeat(5000) + "-- System Details --");
        CrashReport w = CrashReport.ofCrash("crash-x.txt", wide, errors.entries(), longLog, facts(), r);
        JsonObject wb = w.body("€".repeat(5000));
        check(wb.toString().getBytes(StandardCharsets.UTF_8).length <= 400_000, "body ≤ 400 KB with multi-byte text: " + wb.toString().getBytes(StandardCharsets.UTF_8).length);
        eq(w.text(), wb.get("report").getAsString(), "report = the text shown, after shortening");

        CrashReport e = CrashReport.ofErrors(errors.entries(), List.of(), facts(), r);
        eq(CrashReport.Kind.ERROR, e.kind(), "errors only: kind error");
        eq("error", e.body(null).get("kind").getAsString(), "kind on the wire");
        eq("java.lang.IllegalStateException: couldn't prepare a building", e.title(), "title: the first error");
        eq("at dev.lab43.mcplanner.core.CrashTest.report(CrashTest.java)", e.fingerprint(), "fingerprint: its top frame");
        check(e.text().contains("What: the mod caught an error this session") && !e.text().contains("Minecraft's crash report"), "no crash part");
        eq("Minecraft crashed: Initializing game", CrashReport.titleOf("Description: Initializing game\n"), "title without a stack: the description");
        eq("3 h 2 min", CrashReport.duration(3 * 3600 + 125), "duration");
    }

    static void config() throws Exception {
        Path dir = TestMain.tempDir("crash-config");
        try {
            Path file = dir.resolve("mcplanner.json");
            ModConfig c = new ModConfig();
            eq(0L, c.crashReportSeen, "nothing offered yet");
            c.crashReportSeen = 1_760_000_000_000L;
            c.apiUrl = "https://planner.robin-home.example";
            c.setWorldFor(ModConfig.singleplayerContext("Robins Base"), ModConfig.roomRef("abc"));
            c.saveOrThrow(file);
            eq(1_760_000_000_000L, ModConfig.load(file).crashReportSeen, "round trip");
            String settings = c.reportSettings().toString();
            check(settings.contains("plan server=custom") && settings.contains("worlds with a plan=1"), "settings: custom server, a count: " + settings);
            check(!settings.contains("robin") && !settings.contains("Robins"), "no address or world name in the settings");
            Files.writeString(file, "{\"crashReportSeen\": -5}");
            eq(0L, ModConfig.load(file).crashReportSeen, "a negative value is repaired");
        } finally {
            TestMain.deleteTree(dir);
        }
    }

    static void sender() throws Exception {
        String ua = PlannerClient.userAgent("0.2.0", "26.2");
        JsonObject body = CrashReport.ofErrors(List.of(), List.of(), facts(), Redactor.basic()).body("hi");
        try (FakeServer s = new FakeServer()) {
            CrashSender sender = new CrashSender(s.url() + "/", ua);
            int[] status = {201};
            String[] reply = {"{\"id\":\"c0ffee00-0000-4000-8000-000000000001\",\"repeat\":false}"};
            s.on("POST", CrashSender.PATH, r -> FakeServer.Reply.json(status[0], reply[0]));
            eq(new CrashSender.Result("c0ffee00-0000-4000-8000-000000000001", false), TestMain.await(sender.send(body)), "stored");
            FakeServer.Req req = s.requests("POST", CrashSender.PATH).get(0);
            eq(body, JsonParser.parseString(req.body()), "the body as built");
            check(req.header("Content-Type").startsWith("application/json"), "JSON");
            eq(null, req.header("Authorization"), "no sign-in, no key");
            eq(ua, req.header("User-Agent"), "the mod's User-Agent");
            status[0] = 200;
            reply[0] = "{\"id\":\"x\",\"repeat\":true}";
            eq(new CrashSender.Result("x", true), TestMain.await(sender.send(body)), "a repeat");
            Object[][] refusals = {
                {400, "{\"error\":\"bad_request\",\"message\":\"title is too long\"}", "Lab43 didn't accept the report: title is too long"},
                {404, "{\"error\":\"not_found\"}", "Lab43 isn't taking crash reports from MC Planner right now."},
                {413, "{\"error\":\"too_large\"}", "The report is too large to send."},
                {429, "{\"error\":\"slow_down\",\"message\":\"Too many reports from this address. Try again in an hour.\"}", "Too many reports from this address. Try again in an hour."},
                {429, "<html>", "Too many reports from this computer. Please try again later."},
                {503, "", "Lab43 has a problem right now. Please try again later."},
            };
            for (Object[] x : refusals) {
                status[0] = (Integer) x[0];
                reply[0] = (String) x[1];
                int before = s.count("POST", CrashSender.PATH);
                ApiException e = ApiException.of(TestMain.awaitError(sender.send(body), "HTTP " + x[0] + " fails"));
                eq(x[2], e.getMessage(), "HTTP " + x[0] + ": message");
                eq(before + 1, s.count("POST", CrashSender.PATH), "HTTP " + x[0] + ": sent once, no retry");
            }
        }
        ApiException ne = ApiException.of(TestMain.awaitError(new CrashSender("http://127.0.0.1:1", ua).send(body), "nobody listening"));
        eq("Could not reach Lab43. Check your internet connection.", ne.getMessage(), "network message");
    }

    // ------------------------------------------------------------------ helpers

    static Path write(Path p, String text, long modified) throws IOException {
        Files.writeString(p, text);
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified));
        return p;
    }

    static void gz(Path p, String text, long modified) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(p))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified));
    }

    /** servers.dat: an uncompressed NBT compound with a list "servers" of {name, ip, hidden} */
    static byte[] serversDat(String[][] servers) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(9);
            out.writeUTF("servers");
            out.writeByte(10);
            out.writeInt(servers.length);
            for (String[] s : servers) {
                out.writeByte(8);
                out.writeUTF("name");
                out.writeUTF(s[0]);
                out.writeByte(8);
                out.writeUTF("ip");
                out.writeUTF(s[1]);
                out.writeByte(1);
                out.writeUTF("hidden");
                out.writeByte(0);
                out.writeByte(0);
            }
            out.writeByte(0);
        }
        return bos.toByteArray();
    }
}
