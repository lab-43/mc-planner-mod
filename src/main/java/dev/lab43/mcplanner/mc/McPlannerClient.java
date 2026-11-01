package dev.lab43.mcplanner.mc;

import com.mojang.blaze3d.platform.InputConstants;

import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.Status;
import dev.lab43.mcplanner.mc.gui.MarkerScreen;
import dev.lab43.mcplanner.mc.gui.PlannerScreen;
import dev.lab43.mcplanner.mc.render.Overlay;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * MC Planner for Minecraft (Fabric, client only): shows plans from app.mc-planner.com — or a .mcplan file — as an
 * overlay in the world. Works on any server, the server needs nothing.
 *
 * <p>J opens the menu, N switches the overlay, /mcplanner has the rest.
 */
public final class McPlannerClient implements ClientModInitializer {
    public static final String MOD_ID = "mcplanner";
    public static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

    private static KeyMapping keyMenu, keyOverlay, keyMarker, keyNearest;
    private static Planner planner;
    private static Overlay overlay;
    private static Hud hud;
    private static Screen pending;
    private static String arrivedAt;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        keyMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcplanner.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_J, cat));
        keyOverlay = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcplanner.overlay", InputConstants.Type.KEYSYM, InputConstants.KEY_N, cat));
        keyMarker = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcplanner.marker", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));
        keyNearest = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcplanner.nearest", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), cat));

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            planner = Planner.init(mc);
            overlay = new Overlay(planner);
            hud = new Hud(planner, overlay);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (planner != null) planner.shutdown();
        });
        ClientTickEvents.END_CLIENT_TICK.register(McPlannerClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> {
            if (planner != null) planner.onJoin();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
            if (planner != null) planner.onLeave();
        }));
        LevelExtractionEvents.END_EXTRACTION.register(ctx -> {
            if (overlay != null) overlay.extract(ctx);
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> {
            if (overlay != null) overlay.submit(ctx);
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (g, delta) -> {
            if (hud != null) hud.extract(g, delta);
        });
        ClientCommandRegistrationCallback.EVENT.register(Commands::register);
    }

    private static void tick(Minecraft mc) {
        if (planner == null) return;
        if (pending != null && mc.gui.screen() == null) {
            Screen s = pending;
            pending = null;
            mc.gui.setScreen(s);
        }
        while (keyMenu.consumeClick()) {
            if (mc.gui.screen() == null) mc.gui.setScreen(new PlannerScreen(null));
        }
        while (keyOverlay.consumeClick()) toggleOverlay();
        while (keyMarker.consumeClick()) {
            String why = planner.whyReadOnly();
            if (why != null) actionBar(why, ChatFormatting.RED);
            else if (mc.gui.screen() == null) mc.gui.setScreen(new MarkerScreen(null));
        }
        while (keyNearest.consumeClick()) navigateNearest(mc);
        planner.tick();
        overlay.tick();
        hud.tick();
        checkArrival(mc);
    }

    /** open a screen on the next tick (commands run while the chat screen is still closing) */
    static void openLater(Screen s) {
        pending = s;
    }

    static Hud hud() {
        return hud;
    }

    static void toggleOverlay() {
        planner.config.overlay = !planner.config.overlay;
        planner.saveConfig();
        actionBar("MC Planner overlay " + (planner.config.overlay ? "on" : "off"), ChatFormatting.GRAY);
    }

    private static void actionBar(String text, ChatFormatting style) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(text).withStyle(style));
    }

    /** the nearest object that isn't done (and isn't the current target) */
    private static void navigateNearest(Minecraft mc) {
        if (mc.player == null) return;
        PlanObject best = null;
        double bestD = Double.MAX_VALUE;
        PlanObject current = planner.navTarget();
        for (PlanObject o : planner.here()) {
            if (o.status == Status.DONE || !planner.config.isVisible(o) || o.bounds() == null) continue;
            if (current != null && current.id.equals(o.id)) continue;
            var b = o.bounds();
            double d = Math.hypot((b.x0() + b.x1() + 1) / 2.0 - mc.player.getX(), (b.z0() + b.z1() + 1) / 2.0 - mc.player.getZ());
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        if (best == null) {
            actionBar("Nothing left to build nearby", ChatFormatting.GRAY);
            return;
        }
        planner.navigate(best);
        actionBar("Navigating to " + best.name + " · " + Texts.distance(bestD), ChatFormatting.YELLOW);
    }

    /** reaching the navigation target ends navigation */
    private static void checkArrival(Minecraft mc) {
        PlanObject t = planner.navTarget();
        if (t == null || mc.player == null || !t.dimension.equals(planner.dimension())) return;
        PlanObject in = hud.inside();
        var b = t.bounds();
        boolean there = in != null && in.id.equals(t.id);
        if (!there && b != null) {
            double dx = Math.max(0, Math.max(b.x0() - mc.player.getX(), mc.player.getX() - (b.x1() + 1)));
            double dz = Math.max(0, Math.max(b.z0() - mc.player.getZ(), mc.player.getZ() - (b.z1() + 1)));
            there = Math.hypot(dx, dz) < 2.5;
        }
        if (there && !t.id.equals(arrivedAt)) {
            arrivedAt = t.id;
            planner.navigate(null);
            planner.toast("Arrived", t.name + " · " + t.status.label());
        } else if (!there) {
            arrivedAt = null;
        }
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }
}
