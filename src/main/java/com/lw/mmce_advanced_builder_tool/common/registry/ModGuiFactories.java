package com.lw.mmce_advanced_builder_tool.common.registry;

import com.cleanroommc.modularui.factory.GuiFactories;

public final class ModGuiFactories {

    private ModGuiFactories() {
    }

    public static void init() {
        GuiFactories.playerInventory();
    }
}
