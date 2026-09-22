package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import com.lw.mmce_advanced_builder_tool.common.integration.mmcecomplement.AttachmentModuleCompat;
import com.lw.mmce_advanced_builder_tool.common.util.AdvancedBuilderUtils;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import hellfirepvp.modularmachinery.common.block.BlockController;
import hellfirepvp.modularmachinery.common.block.BlockFactoryController;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import hellfirepvp.modularmachinery.common.util.BlockArrayCache;
import ink.ikx.mmce.common.utils.StructureIngredient;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class AdvancedBuilderService {

    private AdvancedBuilderService() {
    }

    public static void start(EntityPlayerMP player, BlockPos pos, boolean useAeItems, boolean useAeFluids,
                             boolean craftMissing, boolean disassembleMode, int dynamicLength,
                             int tickInterval, int operationsPerTick) {
        start(player, pos, useAeItems, useAeFluids, craftMissing, disassembleMode, dynamicLength,
                "", tickInterval, operationsPerTick);
    }

    public static void start(EntityPlayerMP player, BlockPos pos, boolean useAeItems, boolean useAeFluids,
                             boolean craftMissing, boolean disassembleMode, int dynamicLength,
                             String attachmentModule, int tickInterval, int operationsPerTick) {
        World world = player.world;
        TileEntity tile = world.getTileEntity(pos);
        Block block = world.getBlockState(pos).getBlock();
        if (!(tile instanceof TileMultiblockMachineController)) {
            AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.no_controller");
            return;
        }

        DynamicMachine machine = ((TileMultiblockMachineController) tile).getBlueprintMachine();
        if (machine == null && block instanceof BlockController) {
            machine = ((BlockController) block).getParentMachine();
        }
        if (machine == null && block instanceof BlockFactoryController) {
            machine = ((BlockFactoryController) block).getParentMachine();
        }
        if (machine == null) {
            AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.no_machine");
            return;
        }

        if (AdvancedBuilderTaskManager.hasTask(world, pos)) {
            AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.already_running");
            return;
        }

        EnumFacing controllerFacing = world.getBlockState(pos).getValue(BlockController.FACING);
        BlockArray selectedPattern = resolveAttachmentPattern(machine, attachmentModule);
        boolean buildingAttachment = selectedPattern != null;
        BlockArray machinePattern;
        if (buildingAttachment) {
            machinePattern = rotateToFacing(selectedPattern, controllerFacing);
        } else {
            selectedPattern = machine.getPattern();
            machinePattern = new BlockArray(BlockArrayCache.getBlockArrayCache(selectedPattern, controllerFacing));
            AdvancedBuilderUtils.appendDynamicPatterns(machine, machinePattern, controllerFacing, dynamicLength);
        }

        if (disassembleMode) {
            DisassemblyIngredient.Plan plan = AdvancedBuilderUtils.createDisassemblyPlan(machinePattern);
            AdvancedBuilderTaskManager.addTask(new ConfigurableMachineDisassembly(world, pos, player, plan, useAeItems, useAeFluids, tickInterval, operationsPerTick));
            AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.disassembly_started");
            return;
        }

        StructureIngredient ingredient = StructureIngredient.of(world, pos, machinePattern);
        if (player.isCreative()) {
            AdvancedBuilderTaskManager.addTask(new CreativeMachineAssembly(world, pos, player, ingredient,
                    tickInterval, operationsPerTick));
            AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.started");
            return;
        }

        ConfigurableMachineAssembly assembly = new ConfigurableMachineAssembly(world, pos, player, ingredient, useAeItems, useAeFluids, craftMissing, tickInterval, operationsPerTick);
        AdvancedBuilderTaskManager.addTask(assembly);
        assembly.openCraftingGuiIfNeeded();
        AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.started");
    }

    private static BlockArray resolveAttachmentPattern(DynamicMachine machine, String attachmentModule) {
        if (attachmentModule == null || attachmentModule.trim().isEmpty()) {
            return null;
        }
        if (!Mods.MMCE_COMPLEMENT.isLoading()) {
            MMCEAdvancedBuilderTool.LOGGER.info(
                    "Attachment module '{}' was configured, but MMCE Complement is not loaded; using the main pattern",
                    attachmentModule);
            return null;
        }
        return AttachmentModuleCompat.findPattern(machine, attachmentModule);
    }

    private static BlockArray rotateToFacing(BlockArray pattern, EnumFacing facing) {
        BlockArray rotated = pattern;
        EnumFacing current = EnumFacing.NORTH;
        while (current != facing) {
            current = current.rotateYCCW();
            rotated = rotated.rotateYCCW();
        }
        rotated.flushTileBlocksCache();
        return new BlockArray(rotated);
    }
}
