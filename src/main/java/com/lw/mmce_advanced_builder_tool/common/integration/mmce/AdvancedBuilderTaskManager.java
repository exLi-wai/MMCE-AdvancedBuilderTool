package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import com.lw.mmce_advanced_builder_tool.common.util.AdvancedBuilderUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class AdvancedBuilderTaskManager {

    private static final List<AdvancedBuilderTask> TASKS = new ArrayList<>();

    public static void addTask(AdvancedBuilderTask task) {
        TASKS.add(task);
    }

    public static boolean hasTask(World world, BlockPos pos) {
        for (AdvancedBuilderTask task : TASKS) {
            if (task.getWorld().provider.getDimension() == world.provider.getDimension() && task.getCtrlPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean cancelPlayerTask(EntityPlayer player) {
        UUID playerId = player.getGameProfile().getId();
        Iterator<AdvancedBuilderTask> iterator = TASKS.iterator();
        while (iterator.hasNext()) {
            AdvancedBuilderTask task = iterator.next();
            if (!playerId.equals(task.getPlayer().getGameProfile().getId())) {
                continue;
            }
            task.cancel();
            iterator.remove();
            task.report();
            AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        EntityPlayer player = event.player;
        if (event.phase == TickEvent.Phase.START || player.world.isRemote) {
            return;
        }

        long worldTime = player.world.getTotalWorldTime();
        UUID playerId = player.getGameProfile().getId();
        Iterator<AdvancedBuilderTask> iterator = TASKS.iterator();
        while (iterator.hasNext()) {
            AdvancedBuilderTask task = iterator.next();
            if (!playerId.equals(task.getPlayer().getGameProfile().getId())) {
                continue;
            }
            if (task.isControllerInvalid()) {
                iterator.remove();
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                continue;
            }
            if (task.isCancelled()) {
                iterator.remove();
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                continue;
            }
            if (worldTime % task.getTickInterval() != 0) {
                continue;
            }
            task.beginBatch();
            try {
                for (int i = 0; i < task.getOperationsPerTick() && !task.isCompleted(); i++) {
                    task.tick();
                    if (task.isCancelled()) {
                        break;
                    }
                }
            } finally {
                task.endBatch();
            }
            if (task.isCancelled()) {
                iterator.remove();
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                continue;
            }
            if (task.isCompleted()) {
                iterator.remove();
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getSuccessMessageKey());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.player.getGameProfile().getId();
        TASKS.removeIf(task -> playerId.equals(task.getPlayer().getGameProfile().getId()));
    }
}
