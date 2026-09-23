package com.lw.mmce_advanced_builder_tool.common.registry;

import com.lw.mmce_advanced_builder_tool.common.item.AdvancedBuilderToolItem;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class BuilderToolRegistry {

    public static Item ADVANCED_BUILDER_TOOL;

    public static void init() {
        ADVANCED_BUILDER_TOOL = new AdvancedBuilderToolItem();
    }

    @SubscribeEvent
    public void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(ADVANCED_BUILDER_TOOL);
    }
}
