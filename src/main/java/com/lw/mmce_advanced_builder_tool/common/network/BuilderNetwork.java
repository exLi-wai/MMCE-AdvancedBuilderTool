package com.lw.mmce_advanced_builder_tool.common.network;

import com.lw.mmce_advanced_builder_tool.Tags;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class BuilderNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID);

    private static int packetId = 0;

    public static void init() {
        CHANNEL.registerMessage(BuilderConfigPacket.class, BuilderConfigPacket.class, packetId++, Side.SERVER);
        CHANNEL.registerMessage(VariableSelectionPacket.class, VariableSelectionPacket.class, packetId++, Side.SERVER);
    }
}
