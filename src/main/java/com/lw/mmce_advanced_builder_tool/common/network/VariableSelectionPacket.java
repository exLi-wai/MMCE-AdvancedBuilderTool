package com.lw.mmce_advanced_builder_tool.common.network;

import com.lw.mmce_advanced_builder_tool.common.BuilderToolSettings;
import com.lw.mmce_advanced_builder_tool.common.item.AdvancedBuilderToolItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class VariableSelectionPacket implements IMessage, IMessageHandler<VariableSelectionPacket, IMessage> {

    private int slot;
    private String variable;
    private String spec;

    public VariableSelectionPacket() {
    }

    public VariableSelectionPacket(int slot, String variable, String spec) {
        this.slot = slot;
        this.variable = variable;
        this.spec = spec;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        slot = buf.readInt();
        variable = ByteBufUtils.readUTF8String(buf);
        spec = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(slot);
        ByteBufUtils.writeUTF8String(buf, variable == null ? "" : variable);
        ByteBufUtils.writeUTF8String(buf, spec == null ? "" : spec);
    }

    @Override
    public IMessage onMessage(VariableSelectionPacket message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> apply(player, message));
        return null;
    }

    private static void apply(EntityPlayerMP player, VariableSelectionPacket message) {
        if (message.slot < 0 || message.slot >= player.inventory.mainInventory.size()) {
            return;
        }
        ItemStack stack = player.inventory.mainInventory.get(message.slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof AdvancedBuilderToolItem)) {
            return;
        }
        BuilderToolSettings.setVariable(stack, message.variable, message.spec);
    }
}
