package com.lw.mmce_advanced_builder_tool.common.network;

import com.lw.mmce_advanced_builder_tool.common.integration.mmce.AdvancedBuilderConfig;
import com.lw.mmce_advanced_builder_tool.common.items.AdvancedBuilderToolItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketBuilderConfig implements IMessage, IMessageHandler<PacketBuilderConfig, IMessage> {

    private int slot;
    private boolean useAeItems;
    private boolean useAeFluids;
    private boolean craftMissing;
    private boolean disassembleMode;
    private int dynamicLength;
    private String attachmentModule;

    public PacketBuilderConfig() {
    }

    public PacketBuilderConfig(int slot, boolean useAeItems, boolean useAeFluids, boolean craftMissing,
                               boolean disassembleMode, int dynamicLength, String attachmentModule) {
        this.slot = slot;
        this.useAeItems = useAeItems;
        this.useAeFluids = useAeFluids;
        this.craftMissing = craftMissing;
        this.disassembleMode = disassembleMode;
        this.dynamicLength = dynamicLength;
        this.attachmentModule = attachmentModule;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        slot = buf.readInt();
        useAeItems = buf.readBoolean();
        useAeFluids = buf.readBoolean();
        craftMissing = buf.readBoolean();
        disassembleMode = buf.readBoolean();
        dynamicLength = buf.readInt();
        attachmentModule = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(slot);
        buf.writeBoolean(useAeItems);
        buf.writeBoolean(useAeFluids);
        buf.writeBoolean(craftMissing);
        buf.writeBoolean(disassembleMode);
        buf.writeInt(dynamicLength);
        ByteBufUtils.writeUTF8String(buf, attachmentModule == null ? "" : attachmentModule);
    }

    @Override
    public IMessage onMessage(PacketBuilderConfig message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> apply(player, message));
        return null;
    }

    private static void apply(EntityPlayerMP player, PacketBuilderConfig message) {
        if (message.slot < 0 || message.slot >= player.inventory.mainInventory.size()) {
            return;
        }
        ItemStack stack = player.inventory.mainInventory.get(message.slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof AdvancedBuilderToolItem)) {
            return;
        }
        AdvancedBuilderConfig.setUseAeItems(stack, message.useAeItems);
        AdvancedBuilderConfig.setUseAeFluids(stack, message.useAeFluids);
        AdvancedBuilderConfig.setCraftMissing(stack, message.craftMissing);
        AdvancedBuilderConfig.setDisassembleMode(stack, message.disassembleMode);
        AdvancedBuilderConfig.setDynamicLength(stack, message.dynamicLength);
        AdvancedBuilderConfig.setAttachmentModule(stack, message.attachmentModule);
    }

}
