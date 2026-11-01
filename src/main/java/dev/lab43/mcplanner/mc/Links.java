package dev.lab43.mcplanner.mc;

/**
 * Web pages the mod opens (always through Minecraft's "open this link?" screen). The Academy pages are stable links:
 * the website lists them in apps/marketing/lib/academy-links.ts and its build fails when one breaks — a released mod
 * can't change its links any more. {@code ?ref=mod} says where people came from.
 */
public final class Links {
    private Links() {}

    private static final String SITE = "https://www.mc-planner.com";

    /** the website's plans page */
    public static final String PLANS = SITE + "/pricing?ref=mod";
    /** J menu → Help: how the mod works (academy-links: mod) */
    public static final String HELP = SITE + "/academy/mod?ref=mod";
    /** "Plan needed" screen (academy-links: modPlanNeeded) */
    public static final String PLAN_NEEDED = SITE + "/academy/mod/troubleshooting?ref=mod#plan-needed";
    /** a paused world (academy-links: modPaused) */
    public static final String PAUSED = SITE + "/academy/mod/troubleshooting?ref=mod#paused";

    /** the app with its Plans dialog open (start the 7-day trial there): {@code <app>/#plans&ref=mod} */
    public static String appPlans(String appUrl) {
        return app(appUrl) + "#plans&ref=mod";
    }

    /** the app's address with a trailing slash */
    public static String app(String appUrl) {
        String u = appUrl == null || appUrl.isBlank() ? "https://app.mc-planner.com" : appUrl.strip();
        return u.endsWith("/") ? u : u + "/";
    }
}
