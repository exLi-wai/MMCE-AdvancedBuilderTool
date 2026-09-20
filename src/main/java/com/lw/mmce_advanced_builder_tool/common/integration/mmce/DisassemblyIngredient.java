package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import hellfirepvp.modularmachinery.common.util.BlockArray;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

public final class DisassemblyIngredient {

    private DisassemblyIngredient() {
    }

    public static final class Plan {
        private final List<ItemEntry> itemEntries;
        private final List<FluidEntry> fluidEntries;

        public Plan(List<ItemEntry> itemEntries, List<FluidEntry> fluidEntries) {
            this.itemEntries = itemEntries;
            this.fluidEntries = fluidEntries;
        }

        public List<ItemEntry> itemEntries() {
            return itemEntries;
        }

        public List<FluidEntry> fluidEntries() {
            return fluidEntries;
        }
    }

    public static final class ItemEntry {
        private final BlockPos pos;
        private final BlockArray.BlockInformation blockInformation;
        private final List<Tuple<ItemStack, IBlockState>> candidates;

        public ItemEntry(BlockPos pos, BlockArray.BlockInformation blockInformation, List<Tuple<ItemStack, IBlockState>> candidates) {
            this.pos = pos;
            this.blockInformation = blockInformation;
            this.candidates = candidates;
        }

        public BlockPos pos() {
            return pos;
        }

        public BlockArray.BlockInformation blockInformation() {
            return blockInformation;
        }

        public List<Tuple<ItemStack, IBlockState>> candidates() {
            return candidates;
        }
    }

    public static final class FluidEntry {
        private final BlockPos pos;
        private final BlockArray.BlockInformation blockInformation;
        private final List<Tuple<FluidStack, IBlockState>> candidates;

        public FluidEntry(BlockPos pos, BlockArray.BlockInformation blockInformation, List<Tuple<FluidStack, IBlockState>> candidates) {
            this.pos = pos;
            this.blockInformation = blockInformation;
            this.candidates = candidates;
        }

        public BlockPos pos() {
            return pos;
        }

        public BlockArray.BlockInformation blockInformation() {
            return blockInformation;
        }

        public List<Tuple<FluidStack, IBlockState>> candidates() {
            return candidates;
        }
    }
}
