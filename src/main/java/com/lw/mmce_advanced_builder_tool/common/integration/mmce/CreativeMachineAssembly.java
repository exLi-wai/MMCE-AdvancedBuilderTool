package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import ink.ikx.mmce.common.utils.StructureIngredient;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Iterator;
import java.util.List;

public final class CreativeMachineAssembly implements AdvancedBuilderTask {

    private final World world;
    private final BlockPos ctrlPos;
    private final EntityPlayer player;
    private final StructureIngredient ingredient;
    private final int tickInterval;
    private final int operationsPerTick;

    public CreativeMachineAssembly(World world, BlockPos ctrlPos, EntityPlayer player, StructureIngredient ingredient, int tickInterval, int operationsPerTick) {
        this.world = world;
        this.ctrlPos = ctrlPos;
        this.player = player;
        this.ingredient = ingredient;
        this.tickInterval = Math.max(1, tickInterval);
        this.operationsPerTick = Math.max(1, operationsPerTick);
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public BlockPos getCtrlPos() {
        return ctrlPos;
    }

    @Override
    public EntityPlayer getPlayer() {
        return player;
    }

    @Override
    public int getTickInterval() {
        return tickInterval;
    }

    @Override
    public int getOperationsPerTick() {
        return operationsPerTick;
    }

    @Override
    public boolean isControllerInvalid() {
        TileEntity te = world.getTileEntity(ctrlPos);
        return !(te instanceof TileMultiblockMachineController);
    }

    @Override
    public boolean isCompleted() {
        return ingredient.itemIngredient().isEmpty() && ingredient.fluidIngredient().isEmpty();
    }

    @Override
    public void tick() {
        if (!ingredient.itemIngredient().isEmpty()) {
            placeNextItemEntry();
        } else if (!ingredient.fluidIngredient().isEmpty()) {
            placeNextFluidEntry();
        }
    }

    private void placeNextItemEntry() {
        List<StructureIngredient.ItemIngredient> entries = ingredient.itemIngredient();
        Iterator<StructureIngredient.ItemIngredient> iterator = entries.iterator();
        StructureIngredient.ItemIngredient entry = iterator.next();
        if (entry.ingredientList().isEmpty()) {
            iterator.remove();
            return;
        }
        BlockPos realPos = ctrlPos.add(entry.pos());
        Tuple<?, IBlockState> candidate = entry.ingredientList().get(0);
        if (placeBlock(realPos, candidate.getSecond())) {
            ConfigurableMachineAssembly.applyTileNbt(world, realPos, candidate.getSecond(), entry.nbt());
        }
        iterator.remove();
    }

    private void placeNextFluidEntry() {
        List<StructureIngredient.FluidIngredient> entries = ingredient.fluidIngredient();
        Iterator<StructureIngredient.FluidIngredient> iterator = entries.iterator();
        StructureIngredient.FluidIngredient entry = iterator.next();
        if (entry.ingredientList().isEmpty()) {
            iterator.remove();
            return;
        }
        BlockPos realPos = ctrlPos.add(entry.pos());
        placeBlock(realPos, entry.ingredientList().get(0).getSecond());
        iterator.remove();
    }

    private boolean placeBlock(BlockPos realPos, IBlockState state) {
        return state != null && world.setBlockState(realPos, state);
    }

    @Override
    public void report() {
    }

    @Override
    public String getCancelledMessageKey() {
        return "message.mmce_advanced_builder_tool.cancelled";
    }

    @Override
    public String getSuccessMessageKey() {
        return "message.mmce_advanced_builder_tool.success";
    }
}
