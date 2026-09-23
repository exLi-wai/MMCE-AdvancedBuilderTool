package com.lw.mmce_advanced_builder_tool.common.util;

import net.minecraft.entity.player.EntityPlayer;

public class MessageLimiter {

    private final int limit;
    private int sent = 0;
    private boolean suppressed = false;

    public MessageLimiter(int limit) {
        this.limit = limit;
    }

    public boolean tryAcquire(EntityPlayer player, String suppressedMessageKey) {
        if (sent < limit) {
            sent++;
            return true;
        }
        if (!suppressed) {
            suppressed = true;
            StructureIngredients.sendTranslation(player, suppressedMessageKey);
        }
        return false;
    }
}
