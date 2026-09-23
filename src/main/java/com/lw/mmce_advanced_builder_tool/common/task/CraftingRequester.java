package com.lw.mmce_advanced_builder_tool.common.task;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;

public interface CraftingRequester extends ICraftingRequester {

    void abt$setCraftingLink(ICraftingLink link);
}
