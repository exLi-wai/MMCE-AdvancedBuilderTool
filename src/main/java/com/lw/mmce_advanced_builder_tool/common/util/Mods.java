package com.lw.mmce_advanced_builder_tool.common.util;

import net.minecraftforge.fml.common.Loader;

public enum Mods {
    AE2("appliedenergistics2"),
    BAUBLES("baubles"),
    MMCE_COMPLEMENT("mmce_complement");

    public final String modid;

    Mods(String modid) {
        this.modid = modid;
    }

    public boolean isLoading() {
        return Loader.isModLoaded(this.modid);
    }
}
