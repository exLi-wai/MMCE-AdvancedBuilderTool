package com.lw.mmce_advanced_builder_tool.common.network;

import com.lw.mmce_advanced_builder_tool.Tags;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class AdvancedBuilderNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID);

    private static int packetId = 0;

    private AdvancedBuilderNetwork() {
    }

    public static void init() {
        CHANNEL.registerMessage(PacketBuilderConfig.class, PacketBuilderConfig.class, packetId++, Side.SERVER);
    }
}
