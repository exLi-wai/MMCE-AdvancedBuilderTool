package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;

public interface AdvancedBuilderCraftingRequester extends ICraftingRequester {

    void abt$setCraftingLink(ICraftingLink link);
}
