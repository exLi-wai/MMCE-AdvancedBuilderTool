package com.lw.mmce_advanced_builder_tool.common;

import com.lw.mmce_advanced_builder_tool.common.integration.mmcecomplement.AttachmentModuleCompat;
import com.lw.mmce_advanced_builder_tool.common.item.AdvancedBuilderToolItem;
import com.lw.mmce_advanced_builder_tool.common.task.CreativeMachineAssemblyTask;
import com.lw.mmce_advanced_builder_tool.common.task.DisassemblyPlan;
import com.lw.mmce_advanced_builder_tool.common.task.MachineAssemblyTask;
import com.lw.mmce_advanced_builder_tool.common.task.MachineDisassemblyTask;
import com.lw.mmce_advanced_builder_tool.common.task.BuildTaskScheduler;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import com.lw.mmce_advanced_builder_tool.common.util.StructureIngredients;
import com.lw.mmce_advanced_builder_tool.common.variable.BlockVariables;
import hellfirepvp.modularmachinery.common.block.BlockController;
import hellfirepvp.modularmachinery.common.block.BlockFactoryController;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import ink.ikx.mmce.common.utils.StructureIngredient;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Iterator;

/**
 * Entry point that turns "the player used the builder on a controller" into a queued task.
 *
 * <p>It resolves the target controller and its {@code DynamicMachine}, builds the pattern to work
 * on (rotated to the controller facing, optionally expanded by the dynamic length or replaced by an
 * MMCE Complement attachment module), and then picks the task flavour:
 * assembly, disassembly, or the free creative assembly. Rejections (no controller, no machine, a
 * task already running on that controller) are reported to the player and nothing is queued.
 */
public final class BuilderToolService {

    private BuilderToolService() {
    }

    public static void start(EntityPlayerMP player, BlockPos pos, boolean useAeItems, boolean useAeFluids,
                             boolean craftMissing, boolean disassembleMode, int dynamicLength,
                             int tickInterval, int operationsPerTick) {
        start(player, pos, useAeItems, useAeFluids, craftMissing, disassembleMode, dynamicLength,
                "", false, tickInterval, operationsPerTick);
    }

    public static void start(EntityPlayerMP player, BlockPos pos, boolean useAeItems, boolean useAeFluids,
                             boolean craftMissing, boolean disassembleMode, int dynamicLength,
                             String attachmentModule, boolean skipExistingBlocks,
                             int tickInterval, int operationsPerTick) {
        World world = player.world;
        TileEntity tile = world.getTileEntity(pos);
        Block block = world.getBlockState(pos).getBlock();
        if (!(tile instanceof TileMultiblockMachineController)) {
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.no_controller");
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
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.no_machine");
            return;
        }

        if (BuildTaskScheduler.hasTask(world, pos)) {
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.already_running");
            return;
        }

        EnumFacing controllerFacing = world.getBlockState(pos).getValue(BlockController.FACING);
        BlockArray selectedPattern = resolveAttachmentPattern(machine, attachmentModule);
        boolean buildingAttachment = selectedPattern != null;
        if (!buildingAttachment) {
            selectedPattern = machine.getPattern();
        }
        BlockArray machinePattern = rotateToFacing(new BlockArray(selectedPattern), controllerFacing);
        if (!buildingAttachment) {
            StructureIngredients.appendDynamicPatterns(machine, machinePattern, controllerFacing, dynamicLength);
        }
        applyVariableSelections(player, machinePattern);

        if (disassembleMode) {
            DisassemblyPlan.Plan plan = StructureIngredients.createDisassemblyPlan(machinePattern);
            BuildTaskScheduler.addTask(new MachineDisassemblyTask(world, pos, player, plan, useAeItems, useAeFluids, tickInterval, operationsPerTick));
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.disassembly_started");
            return;
        }

        if (skipExistingBlocks) {
            Iterator<Map.Entry<BlockPos, BlockArray.BlockInformation>> entries = machinePattern.getPattern().entrySet().iterator();
            while (entries.hasNext()) {
                if (!world.isAirBlock(pos.add(entries.next().getKey()))) {
                    entries.remove();
                }
            }
            machinePattern.flushTileBlocksCache();
        }

        StructureIngredient ingredient = StructureIngredient.of(world, pos, machinePattern);
        if (player.isCreative()) {
            BuildTaskScheduler.addTask(new CreativeMachineAssemblyTask(world, pos, player, ingredient,
                    tickInterval, operationsPerTick));
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.started");
            return;
        }

        MachineAssemblyTask assembly = new MachineAssemblyTask(world, pos, player, ingredient, useAeItems, useAeFluids, craftMissing, tickInterval, operationsPerTick);
        BuildTaskScheduler.addTask(assembly);
        assembly.openCraftingGuiIfNeeded();
        StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.started");
    }

    private static void applyVariableSelections(EntityPlayer player, BlockArray pattern) {
        ItemStack tool = player.getHeldItemMainhand();
        if (tool.isEmpty() || !(tool.getItem() instanceof AdvancedBuilderToolItem)) {
            tool = ItemStack.EMPTY;
            for (ItemStack candidate : player.inventory.mainInventory) {
                if (!candidate.isEmpty() && candidate.getItem() instanceof AdvancedBuilderToolItem) {
                    tool = candidate;
                    break;
                }
            }
        }
        Map<String, String> selections = BuilderToolSettings.variables(tool);
        if (selections.isEmpty()) {
            return;
        }
        BlockVariables.load().applySelections(pattern, selections);
    }

    private static BlockArray resolveAttachmentPattern(DynamicMachine machine, String attachmentModule) {
        if (attachmentModule == null || attachmentModule.trim().isEmpty()) {
            return null;
        }
        if (!Mods.MMCE_COMPLEMENT.isLoading()) {
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
