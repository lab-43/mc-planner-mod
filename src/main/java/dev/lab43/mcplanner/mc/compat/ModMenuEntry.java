package dev.lab43.mcplanner.mc.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.lab43.mcplanner.mc.gui.PlannerScreen;

/** Mod Menu's "Configure" button opens the MC Planner menu (Mod Menu is optional; this class only loads with it). */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PlannerScreen::new;
    }
}
