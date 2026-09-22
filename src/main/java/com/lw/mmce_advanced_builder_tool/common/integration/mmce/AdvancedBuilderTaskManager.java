package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import com.lw.mmce_advanced_builder_tool.common.util.AdvancedBuilderUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AdvancedBuilderTaskManager {

    private static final long CLEANUP_GRACE_TICKS = 100L;

    private static final List<AdvancedBuilderTask> TASKS = new ArrayList<>();
    private static final Map<AdvancedBuilderTask, Long> NEXT_RUN_TICK = new IdentityHashMap<>();
    private static final Map<AdvancedBuilderTask, Long> NEXT_CLEANUP_TICK = new IdentityHashMap<>();

    public static void addTask(AdvancedBuilderTask task) {
        TASKS.add(task);
    }

    public static boolean hasTask(World world, BlockPos pos) {
        for (AdvancedBuilderTask task : TASKS) {
            if (task.getWorld() == world && task.getCtrlPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean cancelPlayerTask(EntityPlayer player) {
        UUID playerId = player.getGameProfile().getId();
        boolean cancelled = false;
        Iterator<AdvancedBuilderTask> iterator = TASKS.iterator();
        while (iterator.hasNext()) {
            AdvancedBuilderTask task = iterator.next();
            if (task.getWorld() != player.world || task.getPlayer() == null) {
                continue;
            }
            if (!playerId.equals(task.getPlayer().getGameProfile().getId())) {
                continue;
            }
            task.cancel();
            removeTask(task, iterator);
            task.report();
            AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
            cancelled = true;
        }
        return cancelled;
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
            if (task.getWorld() != player.world || task.getPlayer() == null) {
                if (isCleanupDue(task, worldTime)) {
                    task.cancel();
                    removeTask(task, iterator);
                    AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                }
                continue;
            }
            if (!playerId.equals(task.getPlayer().getGameProfile().getId())) {
                continue;
            }
            if (task.isControllerInvalid()) {
                removeTask(task, iterator);
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                continue;
            }
            if (task.isCancelled()) {
                removeTask(task, iterator);
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                continue;
            }
            if (!isRunDue(task, worldTime)) {
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
                removeTask(task, iterator);
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getCancelledMessageKey());
                continue;
            }
            if (task.isCompleted()) {
                removeTask(task, iterator);
                task.report();
                AdvancedBuilderUtils.sendTranslation(player, task.getSuccessMessageKey());
            }
        }
    }

    private static boolean isRunDue(AdvancedBuilderTask task, long worldTime) {
        Long nextRun = NEXT_RUN_TICK.get(task);
        if (nextRun == null) {
            nextRun = worldTime;
        }
        if (worldTime < nextRun) {
            return false;
        }
        NEXT_RUN_TICK.put(task, worldTime + Math.max(1, task.getTickInterval()));
        return true;
    }

    private static boolean isCleanupDue(AdvancedBuilderTask task, long worldTime) {
        long interval = Math.max(1, task.getTickInterval());
        Long nextCleanup = NEXT_CLEANUP_TICK.get(task);
        if (nextCleanup == null) {
            NEXT_CLEANUP_TICK.put(task, worldTime + CLEANUP_GRACE_TICKS);
            return false;
        }
        if (worldTime < nextCleanup) {
            return false;
        }
        NEXT_CLEANUP_TICK.put(task, worldTime + interval);
        return true;
    }

    private static void removeTask(AdvancedBuilderTask task, Iterator<AdvancedBuilderTask> iterator) {
        clearSchedule(task);
        iterator.remove();
    }

    private static void clearSchedule(AdvancedBuilderTask task) {
        NEXT_RUN_TICK.remove(task);
        NEXT_CLEANUP_TICK.remove(task);
    }

    @SubscribeEvent
    public void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.player.getGameProfile().getId();
        Iterator<AdvancedBuilderTask> iterator = TASKS.iterator();
        while (iterator.hasNext()) {
            AdvancedBuilderTask task = iterator.next();
            if (task.getPlayer() != null && playerId.equals(task.getPlayer().getGameProfile().getId())) {
                task.cancel();
                removeTask(task, iterator);
            }
        }
    }
}
