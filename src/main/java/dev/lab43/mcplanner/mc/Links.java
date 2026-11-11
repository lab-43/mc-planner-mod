package dev.lab43.mcplanner.mc;

/**
 * Web pages the mod opens (always through Minecraft's "open this link?" screen). The Academy pages are stable links:
 * the website lists them in apps/marketing/lib/academy-links.ts and its build fails when one breaks — a released mod
 * can't change its links any more. {@code ?ref=mod} says where people came from.
 */
public final class Links {
    private Links() {}

    private static final String SITE = "https://www.mc-planner.com";

    /** J menu → Help: how the mod works (academy-links: mod) */
    public static final String HELP = SITE + "/academy/mod?ref=mod";
    /** a paused world (academy-links: modPaused) */
    public static final String PAUSED = SITE + "/academy/mod/troubleshooting?ref=mod#paused";
    /** the "Crash report" screen: what's in it and where it goes (academy-links: modCrashReport) */
    public static final String CRASH_REPORT = SITE + "/academy/mod/troubleshooting?ref=mod#crash-report";
}
