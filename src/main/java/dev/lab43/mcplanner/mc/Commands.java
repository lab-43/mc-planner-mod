package dev.lab43.mcplanner.mc;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.Status;
import dev.lab43.mcplanner.mc.gui.MarkerScreen;
import dev.lab43.mcplanner.mc.gui.PlannerScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * /mcplanner — the menu, plus quick actions for building with the chat open:
 * reload · toggle · list · goto &lt;name&gt; · stop · marker [name] · status &lt;idea|planned|in_progress|done&gt;.
 */
final class Commands {
    private Commands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> d, CommandBuildContext ctx) {
        d.register(literal("mcplanner")
                .executes(c -> {
                    McPlannerClient.openLater(new PlannerScreen(null));
                    return 1;
                })
                .then(literal("reload").executes(c -> {
                    Planner p = Planner.get();
                    if (p.source() == Planner.Source.NONE) return fail(c, "No plan is shown. Open the menu with J or /mcplanner.");
                    p.refresh();
                    c.getSource().sendFeedback(Component.literal("Reloading " + p.sourceName() + "…").withStyle(ChatFormatting.GRAY));
                    return 1;
                }))
                .then(literal("toggle").executes(c -> {
                    McPlannerClient.toggleOverlay();
                    return 1;
                }))
                .then(literal("list").executes(Commands::list))
                .then(literal("stop").executes(c -> {
                    Planner.get().navigate(null);
                    c.getSource().sendFeedback(Component.literal("Navigation stopped.").withStyle(ChatFormatting.GRAY));
                    return 1;
                }))
                .then(literal("goto").then(argument("name", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            PlanObject o = find(name);
                            if (o == null) return fail(c, "Nothing called \"" + name + "\" in this dimension.");
                            Planner.get().navigate(o);
                            c.getSource().sendFeedback(Component.literal("Navigating to " + o.name + " — follow the line and the HUD arrow.").withStyle(ChatFormatting.YELLOW));
                            return 1;
                        })))
                .then(literal("marker")
                        .executes(c -> {
                            McPlannerClient.openLater(new MarkerScreen(null));
                            return 1;
                        })
                        .then(argument("name", StringArgumentType.greedyString()).executes(c -> {
                            Planner p = Planner.get();
                            String why = p.whyReadOnly();
                            if (why != null) return fail(c, why);
                            String name = StringArgumentType.getString(c, "name");
                            p.addMarker(name, err -> {
                                if (err != null) c.getSource().sendError(Component.literal(err));
                            });
                            return 1;
                        })))
                .then(literal("status").then(argument("status", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("idea", "planned", "in_progress", "done"), b))
                        .executes(c -> {
                            Planner p = Planner.get();
                            Status s = Status.parse(StringArgumentType.getString(c, "status").toLowerCase(Locale.ROOT));
                            if (s == null) return fail(c, "Use idea, planned, in_progress or done.");
                            PlanObject o = McPlannerClient.hud().inside();
                            if (o == null) o = p.navTarget();
                            if (o == null) return fail(c, "Stand inside a planned area, building or path (or navigate to one) first.");
                            String why = p.whyReadOnly();
                            if (why != null) return fail(c, why);
                            PlanObject target = o;
                            p.setStatus(target, s, err -> {
                                if (err != null) c.getSource().sendError(Component.literal(err));
                                else c.getSource().sendFeedback(Component.literal(target.name + " is now " + s.label() + ".").withStyle(ChatFormatting.GREEN));
                            });
                            return 1;
                        }))));
    }

    private static int list(CommandContext<FabricClientCommandSource> c) {
        Planner p = Planner.get();
        if (p.source() == Planner.Source.NONE) return fail(c, "No plan is shown. Open the menu with J or /mcplanner.");
        Vec3 pos = c.getSource().getPosition();
        List<PlanObject> here = new ArrayList<>();
        for (PlanObject o : p.here()) if (p.config.isVisible(o) && o.bounds() != null) here.add(o);
        here.sort(Comparator.comparingDouble(o -> dist(o, pos)));
        c.getSource().sendFeedback(Component.literal(p.sourceName() + " · " + here.size() + " here, nearest first:").withStyle(ChatFormatting.GOLD));
        int n = 0;
        for (PlanObject o : here) {
            if (n++ >= 10) break;
            MutableComponent line = Component.literal(" ■ ").withColor(0xFF000000 | o.color)
                    .append(Component.literal(o.name).withStyle(s -> s.withColor(ChatFormatting.WHITE).withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/mcplanner goto " + o.name))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Navigate to " + o.name)))))
                    .append(Component.literal("  " + o.kind.label() + " · " + o.status.label() + " · " + Texts.distance(dist(o, pos))).withStyle(ChatFormatting.GRAY));
            c.getSource().sendFeedback(line);
        }
        return 1;
    }

    private static double dist(PlanObject o, Vec3 pos) {
        var b = o.bounds();
        double dx = Math.max(0, Math.max(b.x0() - pos.x, pos.x - (b.x1() + 1)));
        double dz = Math.max(0, Math.max(b.z0() - pos.z, pos.z - (b.z1() + 1)));
        return Math.hypot(dx, dz);
    }

    private static List<String> names() {
        Planner p = Planner.get();
        List<String> out = new ArrayList<>();
        for (PlanObject o : p.here()) if (!o.name.isBlank()) out.add(o.name);
        return out;
    }

    private static PlanObject find(String name) {
        PlanObject partial = null;
        for (PlanObject o : Planner.get().here()) {
            if (o.name.equalsIgnoreCase(name)) return o;
            if (partial == null && o.name.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) partial = o;
        }
        return partial;
    }

    private static int fail(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendError(Component.literal(msg));
        return 0;
    }
}
