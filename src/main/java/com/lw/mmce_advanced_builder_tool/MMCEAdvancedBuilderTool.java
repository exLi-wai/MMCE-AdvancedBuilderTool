package com.lw.mmce_advanced_builder_tool;

import com.lw.mmce_advanced_builder_tool.common.network.BuilderNetwork;
import com.lw.mmce_advanced_builder_tool.common.registry.BuilderToolRegistry;
import com.lw.mmce_advanced_builder_tool.common.registry.ModGuiFactories;
import com.lw.mmce_advanced_builder_tool.common.task.BuildTaskScheduler;
import com.lw.mmce_advanced_builder_tool.proxy.CommonProxy;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        dependencies =
                "required-after:modularmachinery;" +
                "required-after:modularui;" +
                "after:appliedenergistics2;" +
                "after:baubles"
)
public class MMCEAdvancedBuilderTool {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_ID);

    @Instance(Tags.MOD_ID)
    public static MMCEAdvancedBuilderTool INSTANCE;

    @SidedProxy(
            clientSide = "com.lw.mmce_advanced_builder_tool.proxy.ClientProxy",
            serverSide = "com.lw.mmce_advanced_builder_tool.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModGuiFactories.init();
        BuilderToolRegistry.init();
        BuilderNetwork.init();
        MinecraftForge.EVENT_BUS.register(new BuilderToolRegistry());
        MinecraftForge.EVENT_BUS.register(new BuildTaskScheduler());
        proxy.preInit(event);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
