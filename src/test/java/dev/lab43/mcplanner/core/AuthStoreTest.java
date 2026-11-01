package dev.lab43.mcplanner.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class AuthStoreTest {
    private AuthStoreTest() {}

    static void run() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = TestMain.tempDir("home");
        Path game = TestMain.tempDir("game");
        try {
            System.setProperty("user.home", home.toString());
            eq("mcplanner-minecraft", AuthStore.CLIENT_ID, "client id");

            AuthStore store = new AuthStore(game);
            eq(home.resolve(".lab43").resolve("mcplanner-minecraft.json"), store.primaryFile(), "token lives in the home folder");
            eq(game.resolve("config").resolve("mcplanner-auth.json"), store.fallbackFile(), "fallback in the instance config");
            check(store.load().isEmpty(), "nothing saved yet");
            eq(null, store.load("https://auth.lab43.dev"), "no entry yet");

            check(store.save("https://auth.lab43.dev/", "tok-real", "u1", "Joni"), "save real hub");
            check(store.save("http://localhost:8788", "tok-test", "u2", "Tester"), "save test hub");
            Path f = store.primaryFile();
            check(Files.exists(f), "file written");
            check(!Files.exists(store.fallbackFile()), "fallback untouched");
            if (Files2.posix()) {
                eq("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)), "owner-only file");
                eq("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.getParent())), "owner-only folder");
            }

            AuthStore again = new AuthStore(game);
            Map<String, AuthStore.Entry> all = again.load();
            eq(2, all.size(), "two hubs");
            AuthStore.Entry real = again.load("HTTPS://auth.lab43.dev");
            check(real != null, "lookup is case/slash-insensitive");
            if (real != null) {
                eq("tok-real", real.token(), "token");
                eq("u1", real.userId(), "user id");
                eq("Joni", real.userName(), "user name");
                eq("https://auth.lab43.dev", real.hub(), "hub key");
                check(real.savedAt() > 0, "savedAt");
            }
            eq("tok-test", again.load("http://localhost:8788").token(), "test hub doesn't clash");

            check(again.save("https://auth.lab43.dev", "tok-new", "u1", "Joni"), "replace");
            eq("tok-new", again.load("https://auth.lab43.dev").token(), "replaced token");
            eq(2, again.load().size(), "still two");

            again.clear("https://auth.lab43.dev");
            eq(null, again.load("https://auth.lab43.dev"), "cleared");
            eq("tok-test", again.load("http://localhost:8788").token(), "other hub kept");
            again.clear("http://localhost:8788");
            check(again.load().isEmpty(), "all cleared");
            check(!Files.exists(f), "empty file removed");

            // home not writable → fallback in the instance
            Path home2 = TestMain.tempDir("home2");
            Files.write(home2.resolve(".lab43"), new byte[] {1}); // a file where the folder should be
            System.setProperty("user.home", home2.toString());
            AuthStore fb = new AuthStore(game);
            check(fb.save("https://auth.lab43.dev", "tok-fb", "u3", "Robin"), "save falls back");
            check(Files.exists(fb.fallbackFile()), "fallback file written");
            eq("tok-fb", fb.load("https://auth.lab43.dev").token(), "fallback entry loads");
            fb.clear("https://auth.lab43.dev");
            eq(null, fb.load("https://auth.lab43.dev"), "fallback cleared");
            TestMain.deleteTree(home2);

            // corrupt file is ignored
            System.setProperty("user.home", home.toString());
            Files.createDirectories(f.getParent());
            Files.write(f, "garbage{".getBytes());
            check(new AuthStore(game).load().isEmpty(), "corrupt auth file → nothing");
            check(new AuthStore(game).save("https://auth.lab43.dev", "t", null, null), "save over a corrupt file");
            eq("t", new AuthStore(game).load("https://auth.lab43.dev").token(), "saved over corrupt");
        } finally {
            System.setProperty("user.home", oldHome);
            TestMain.deleteTree(home);
            TestMain.deleteTree(game);
        }
    }
}
