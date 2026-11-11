package dev.lab43.mcplanner.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes out of a crash report what must never leave the computer (Lab43's crash reports: the person sees the whole
 * report and presses Send): the player's name and UUID, session tokens ({@code --accessToken …}, JWTs) and keys, the
 * home folder (becomes {@code ~}), IP addresses, emails, and world and server names: the ones the mod knows (saves,
 * servers.dat, the world you're in, the plan) and where Minecraft writes them ({@code saves/<world>}, "Level name: …",
 * {@code ServerLevel[…]}, "Connecting to …"); also launcher profile names, resource pack files and plan or schematic
 * file names in paths.
 *
 * <p>A known name is first replaced by a mark (one private-use character) and labelled last, so the shape rules take a
 * path, folder or file name holding it whole ({@code saves/<world>}, {@code /<file>.mcplan}), never leaving the rest of
 * it behind ("{@code <world> 2}").
 *
 * <p>Every pattern starts only where its run starts, or with a literal, so a long input can't make it backtrack.
 */
public final class Redactor {
    /** everyday words and default names: never treated as a private name (it would blank out normal text) */
    private static final Set<String> SAFE = Set.of(
        "minecraft", "fabric", "java", "mcplanner", "mc planner", "world", "new world", "server", "client", "level",
        "overworld", "nether", "the end", "the_nether", "the_end", "lab43", "path", "area", "building", "marker", "home",
        "player", "error", "null", "true", "false", "local", "localhost", "minecraft server",
        // the folders the shape rules look for (a world called "Users" mustn't keep C:\Users\robin from going)
        "users", "saves", "instances", "file");

    private static final Pattern CONTROL = Pattern.compile("\u001B\\[[0-9;]*m|[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\uE000-\\uE00F]");

    /** where a known name was, until the shape rules are done: one private-use character per label */
    private static final Map<String, String> MARKS = Map.of(
        "<player>", "\uE001", "<world>", "\uE002", "<server>", "\uE003", "<name>", "\uE004", "<uuid>", "\uE005");
    private static final Pattern WIN_HOME = Pattern.compile("\\b([A-Za-z]:[\\\\/]+Users[\\\\/]+)(?:[^\\\\/\\r\\n\"<>|:*?]+(?=[\\\\/])|[^\\\\/\\s\"<>|:*?]+)");
    private static final Pattern NIX_HOME = Pattern.compile("(?<![\\w.])/(?:home|Users)/(?:[^/\\r\\n\"<>|]+(?=/)|[^/\\s\"<>|]+)");

    /**
     * Secrets found by their shape, before the known names are marked (a world called "Server" or "Token" mustn't keep
     * "--server …" or "token=…" from going): launcher arguments, tokens, keys, "password: …", emails.
     */
    private static final List<Map.Entry<Pattern, String>> SECRETS = List.of(
        // launcher arguments: --accessToken <token>, --username <name>, --uuid <id>, quick play into a world or server
        rule("(--(?:accessToken|uuid|username|xuid|clientId|session|password|quickPlayMultiplayer|quickPlaySingleplayer|quickPlayRealms|quickPlayPath|server))(\\s+|=)(\"[^\"]*\"|\\S+)",
            "$1$2<redacted>"),
        rule("\\beyJ[\\w-]{5,}\\.[\\w-]{5,}\\.[\\w-]*", "<token>"),
        rule("(?i)\\bBearer\\s+[\\w.~+/=-]+", "Bearer <token>"),
        rule("\\b(?:l43d_|l43k_|hqs_|sb_secret_|sb_publishable_|sk_live_|sk_test_|rk_live_|whsec_|ghp_|gho_|github_pat_)[\\w-]+", "<token>"),
        rule("(?i)\\b(access[_-]?token|refresh[_-]?token|token|session[_-]?id|password|passwd|secret|api[_-]?key|authorization)([\"']?\\s*[:=]\\s*[\"']?)(?!<)[^\\s\"',;&}]+",
            "$1$2<redacted>"),
        rule("(?<![\\w.+-])[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63}){1,8}", "<email>"));

    /** pattern → replacement, in this order (after the known names and the home folder) */
    private static final List<Map.Entry<Pattern, String>> RULES = List.of(
        // an email whose local part was a known name ("<mark>@example.com")
        rule("(?<![\\w.+\\uE000-\\uE00F-])[\\w.+\\uE000-\\uE00F-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63}){1,8}", "<email>"),
        // world and server names where Minecraft writes them
        rule("((?<![\\w.-])saves[\\\\/]+)[^\\\\/\\r\\n\"<>|]+", "$1<world>"),
        // launcher profiles (Prism, MultiMC, CurseForge) are named by people too; so are resource packs and plan files
        rule("(?i)((?<![\\w.-])instances[\\\\/]+)[^\\\\/\\r\\n\"<>|]+", "$1<profile>"),
        rule("(?<![^\\s,\\[])(file/)[^,\\]\\r\\n]+", "$1<file>"),
        rule("([\\\\/])[^\\\\/\\r\\n\"<>|:]+\\.(mcplan|litematic|schem|schematic|nbt)(?![\\w.])", "$1<file>.$2"),
        rule("(?im)^(\\s*(?:Level name|World name|Server name|Server address|Level folder|Singleplayer world name)\\s*:\\s*)\\S.*$", "$1<redacted>"),
        rule("(ServerLevel\\[)[^\\]\\r\\n]*(\\])", "$1<world>$2"),
        rule("(Preparing level \")[^\"\\r\\n]*(\")", "$1<world>$2"),
        rule("(Connecting to )[^,\\s]+", "$1<server>"),
        rule("((?<!\\w)\\w{0,40}Player\\[')[^'\\r\\n]*(')", "$1<player>$2"),
        rule("(Setting user: )\\S+", "$1<player>"),
        rule("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "<uuid>"),
        rule("(?i)(?<![\\w:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![\\w:])", "<ip>"));

    private static final Pattern IPV6_SHORT = Pattern.compile("(?i)(?<![\\w:])(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,5})?(?![\\w:])");
    // a full stop after it ends the sentence, not the address
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\w|\\.\\d)");
    private static final Pattern VERSION_BEFORE = Pattern.compile("(?i)(?:version|java|jdk|jre|build|driver|opengl|\\bv|[a-z]/)\\s*[:=]?\\s*$");
    private static final Pattern HEX = Pattern.compile("(?i)(?<![0-9a-z_])[0-9a-f]{32,}(?![0-9a-z_])");
    private static final Pattern LONG_TOKEN = Pattern.compile("(?<![\\w-])[\\w-]{40,}(?![\\w-])");

    /** a name or the home folder: its pattern, the text looked for first (lower case: a fast skip), the label */
    private record Known(Pattern pattern, String needle, String label) {}

    private final List<Known> known;

    private Redactor(List<Known> known) {
        this.known = known;
    }

    private static Map.Entry<Pattern, String> rule(String regex, String replacement) {
        return Map.entry(Pattern.compile(regex), replacement);
    }

    /** Only the shape rules (tokens, emails, ids, IPs, paths under a user's folder): for text noted before the names are known. */
    public static Redactor basic() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the names this computer knows; each kind gets its own label. */
    public static final class Builder {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final List<String> homes = new ArrayList<>();

        private Builder() {}

        /** The user's home folder (System.getProperty("user.home")): becomes ~ wherever it appears. */
        public Builder home(String path) {
            if (path != null && path.strip().length() >= 3) homes.add(path.strip());
            return this;
        }

        public Builder player(String name) {
            return add(name, "<player>");
        }

        public Builder uuid(UUID id) {
            if (id == null) return this;
            add(id.toString(), "<uuid>");
            return add(id.toString().replace("-", ""), "<uuid>");
        }

        public Builder world(String name) {
            return add(name, "<world>");
        }

        public Builder server(String nameOrAddress) {
            if (nameOrAddress == null) return this;
            add(nameOrAddress, "<server>");
            String host = nameOrAddress.strip();
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) add(host.substring(0, colon), "<server>"); // "play.example.com:25565"
            return this;
        }

        /** Any other name: plans, objects, files, the Lab43 account. */
        public Builder name(String name) {
            return add(name, "<name>");
        }

        public Builder names(Iterable<String> list, String label) {
            if (list != null) for (String n : list) add(n, label);
            return this;
        }

        private Builder add(String name, String label) {
            if (name == null) return this;
            String n = name.strip();
            if (n.length() < 3 || SAFE.contains(n.toLowerCase(Locale.ROOT))) return this;
            names.putIfAbsent(n, label);
            return this;
        }

        public Redactor build() {
            List<Known> out = new ArrayList<>();
            // the home folder first (longest first), with either slash and as escaped in JSON
            homes.sort(Comparator.comparingInt(String::length).reversed());
            for (String h : homes) {
                String fwd = h.replace('\\', '/'), back = h.replace('/', '\\');
                for (String v : new LinkedHashSet<>(List.of(h, fwd, back, back.replace("\\", "\\\\")))) {
                    out.add(new Known(Pattern.compile("(?i)" + Pattern.quote(v) + "(?![\\p{L}\\p{N}_-])"), v.toLowerCase(Locale.ROOT), "~"));
                }
            }
            List<Map.Entry<String, String>> sorted = new ArrayList<>(names.entrySet());
            sorted.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
            for (Map.Entry<String, String> e : sorted) {
                out.add(new Known(Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
            }
            return new Redactor(List.copyOf(out));
        }
    }

    /** a Java stack frame ("\tat dev.lab43.mcplanner.core.X.run(X.java:12)"): the code's own names, never a person's */
    private static final Pattern FRAME = Pattern.compile(
        "^\\s*at\\s+[\\w$.<>/-]+\\((?:[\\w$.-]+\\.(?:java|kt|scala|groovy)(?::\\d+)?|Unknown Source|Native Method)\\).*$");

    /**
     * The text without what must not leave this computer. In a stack trace the frames only lose what has a shape (a home
     * folder, an address): a world or plan called "core" or "render" mustn't blank out the code's own names.
     */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = CONTROL.matcher(text).replaceAll("");
        if (s.indexOf('\n') < 0 || !s.contains("at ")) return redact(s, true);
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(redact(lines[i], !FRAME.matcher(lines[i]).matches()));
        }
        return out.toString();
    }

    private String redact(String text, boolean withNames) {
        String s = text;
        for (Map.Entry<Pattern, String> r : SECRETS) s = r.getKey().matcher(s).replaceAll(r.getValue());
        String lower = s.toLowerCase(Locale.ROOT);
        for (Known k : known) {
            if (!withNames && !"~".equals(k.label())) continue; // a frame: only the home folder
            if (!lower.contains(k.needle())) continue; // most names aren't there: skip the regex
            String next = k.pattern().matcher(s).replaceAll(Matcher.quoteReplacement(MARKS.getOrDefault(k.label(), k.label())));
            if (!next.equals(s)) {
                s = next;
                lower = s.toLowerCase(Locale.ROOT);
            }
        }
        s = WIN_HOME.matcher(s).replaceAll("~");
        s = NIX_HOME.matcher(s).replaceAll("~");
        for (Map.Entry<Pattern, String> r : RULES) s = r.getKey().matcher(s).replaceAll(r.getValue());
        s = replace(IPV6_SHORT, s, m -> "::1".equals(m.group()) ? m.group() : "<ip>");
        final String in = s;
        s = replace(IPV4, s, m -> {
            String ip = m.group();
            if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")) return ip;
            return VERSION_BEFORE.matcher(in.substring(Math.max(0, m.start() - 16), m.start())).find() ? ip : "<ip>";
        });
        s = replace(HEX, s, m -> m.group().chars().anyMatch(Character::isLetter) && m.group().chars().anyMatch(Character::isDigit) ? "<hex>" : m.group());
        s = replace(LONG_TOKEN, s, m -> {
            String t = m.group();
            long digits = t.chars().filter(Character::isDigit).count();
            boolean lowerCase = t.chars().anyMatch(Character::isLowerCase), upperCase = t.chars().anyMatch(Character::isUpperCase);
            return digits >= 3 && lowerCase && upperCase ? "<token>" : t;
        });
        for (Map.Entry<String, String> m : MARKS.entrySet()) s = s.replace(m.getValue(), m.getKey());
        return s;
    }

    public List<String> apply(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(apply(l));
        return out;
    }

    private static String replace(Pattern p, String s, Function<MatchResult, String> f) {
        return p.matcher(s).replaceAll(m -> Matcher.quoteReplacement(f.apply(m)));
    }
}
