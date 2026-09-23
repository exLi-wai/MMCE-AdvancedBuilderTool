package com.lw.mmce_advanced_builder_tool.common.task;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public interface BuildTask {

    default void beginBatch() {}

    default void endBatch() {}

    World getWorld();

    BlockPos getCtrlPos();

    EntityPlayer getPlayer();

    int getTickInterval();

    int getOperationsPerTick();

    boolean isControllerInvalid();

    boolean isCompleted();

    default boolean isCancelled() {
        return false;
    }

    default void cancel() {}

    void tick();

    void report();

    String getCancelledMessageKey();

    String getSuccessMessageKey();
}
