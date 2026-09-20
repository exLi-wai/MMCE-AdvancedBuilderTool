package com.lw.mmce_advanced_builder_tool.common.util;

import com.lw.mmce_advanced_builder_tool.common.integration.mmce.DisassemblyIngredient;
import github.kasuminova.mmce.common.util.DynamicPattern;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import ink.ikx.mmce.common.utils.FluidUtils;
import ink.ikx.mmce.common.utils.StructureIngredient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.UniversalBucket;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AdvancedBuilderUtils {

    private AdvancedBuilderUtils() {
    }

    public static NBTTagCompound getOrCreateTag(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        return stack.getTagCompound();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static void sendTranslation(EntityPlayer player, String key, Object... args) {
        if (player == null) {
            return;
        }
        if (player.world.isRemote) {
            return;
        }
        String fingerprint = buildMessageFingerprint(key, args);
        long now = player.world.getTotalWorldTime();
        String lastFingerprint = player.getEntityData().getString(AdvancedBuilderMessages.LAST_MESSAGE_TAG);
        long lastMessageTick = player.getEntityData().getLong(AdvancedBuilderMessages.LAST_MESSAGE_TICK_TAG);
        if (fingerprint.equals(lastFingerprint) && now - lastMessageTick <= 2) {
            return;
        }
        player.getEntityData().setString(AdvancedBuilderMessages.LAST_MESSAGE_TAG, fingerprint);
        player.getEntityData().setLong(AdvancedBuilderMessages.LAST_MESSAGE_TICK_TAG, now);
        player.sendMessage(new TextComponentTranslation(key, args));
    }

    private static String buildMessageFingerprint(String key, Object... args) {
        StringBuilder builder = new StringBuilder(key);
        if (args != null) {
            for (Object arg : args) {
                builder.append('\u0001').append(arg);
            }
        }
        return builder.toString();
    }

    public static String posToString(BlockPos pos) {
        return hellfirepvp.modularmachinery.common.util.MiscUtils.posToString(pos);
    }

    public static boolean isReplaceableForAssembly(World world, BlockPos pos) {
        IBlockState blockState = world.getBlockState(pos);
        Block block = blockState.getBlock();
        return world.isAirBlock(pos) || block instanceof IPlantable || block instanceof BlockLiquid || block instanceof IFluidBlock;
    }

    public static List<IFluidHandlerItem> getFluidHandlerItems(List<ItemStack> inventory) {
        List<IFluidHandlerItem> fluidHandlers = new ArrayList<>();
        for (ItemStack invStack : inventory) {
            Item item = invStack.getItem();
            if (item instanceof UniversalBucket || item == Items.LAVA_BUCKET || item == Items.WATER_BUCKET) {
                continue;
            }
            if (!FluidUtils.isFluidHandler(invStack)) {
                continue;
            }
            IFluidHandlerItem handler = FluidUtil.getFluidHandler(invStack);
            if (handler != null) {
                fluidHandlers.add(handler);
            }
        }
        return fluidHandlers;
    }

    public static void appendDynamicPatterns(DynamicMachine machine, BlockArray machinePattern, EnumFacing controllerFacing, int requestedLength) {
        Map<String, DynamicPattern> dynamicPatterns = machine.getDynamicPatterns();
        int length = requestedLength;
        for (DynamicPattern pattern : dynamicPatterns.values()) {
            length = Math.max(length, pattern.getMinSize());
        }
        for (DynamicPattern pattern : dynamicPatterns.values()) {
            int clamped = Math.min(Math.max(pattern.getMinSize(), length), pattern.getMaxSize());
            pattern.addPatternToBlockArray(machinePattern, clamped, pattern.getFaces().iterator().next(), controllerFacing);
        }
    }

    public static StructureIngredient createFullStructureIngredient(BlockArray blockArray) {
        List<StructureIngredient.ItemIngredient> itemIngredients = new ArrayList<>();
        List<StructureIngredient.FluidIngredient> fluidIngredients = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockArray.BlockInformation> entry : blockArray.getPattern().entrySet()) {
            SplitStructureCandidates candidates = splitStructureCandidates(entry.getValue());
            if (candidates.hasFluids()) {
                fluidIngredients.add(new StructureIngredient.FluidIngredient(entry.getKey(), candidates.fluidCandidates));
            } else if (candidates.hasItems()) {
                itemIngredients.add(new StructureIngredient.ItemIngredient(entry.getKey(), candidates.itemCandidates, entry.getValue().getMatchingTag()));
            }
        }
        return new StructureIngredient(itemIngredients, fluidIngredients);
    }

    public static DisassemblyIngredient.Plan createDisassemblyPlan(BlockArray blockArray) {
        List<DisassemblyIngredient.ItemEntry> itemEntries = new ArrayList<>();
        List<DisassemblyIngredient.FluidEntry> fluidEntries = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockArray.BlockInformation> entry : blockArray.getPattern().entrySet()) {
            SplitStructureCandidates candidates = splitStructureCandidates(entry.getValue());
            if (candidates.hasFluids()) {
                fluidEntries.add(new DisassemblyIngredient.FluidEntry(entry.getKey(), entry.getValue(), candidates.fluidCandidates));
            } else if (candidates.hasItems()) {
                itemEntries.add(new DisassemblyIngredient.ItemEntry(entry.getKey(), entry.getValue(), candidates.itemCandidates));
            }
        }
        return new DisassemblyIngredient.Plan(itemEntries, fluidEntries);
    }

    public static boolean areItemStacksEqual(ItemStack first, ItemStack second) {
        return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    public static boolean areFluidsEqual(FluidStack first, FluidStack second) {
        return first != null && second != null && first.isFluidEqual(second);
    }

    public static Tuple<ItemStack, IBlockState> findMatchingItemCandidate(World world, BlockPos realPos, List<Tuple<ItemStack, IBlockState>> candidates) {
        IBlockState current = world.getBlockState(realPos);
        if (current.getBlock() == Blocks.AIR) {
            return null;
        }

        Tuple<ItemStack, IBlockState> matched = findMatchingCandidateByState(current, candidates);
        if (matched != null) {
            return matched;
        }

        return null;
    }

    public static Tuple<FluidStack, IBlockState> findMatchingFluidCandidate(World world, BlockPos realPos, List<Tuple<FluidStack, IBlockState>> candidates) {
        IBlockState current = world.getBlockState(realPos);
        if (current.getBlock() == Blocks.AIR) {
            return null;
        }

        Tuple<FluidStack, IBlockState> matched = findMatchingCandidateByState(current, candidates);
        if (matched != null) {
            return matched;
        }

        return null;
    }

    private static <T> Tuple<T, IBlockState> findMatchingCandidateByState(IBlockState current, List<Tuple<T, IBlockState>> candidates) {
        // Disassembly must never fall back to BlockInformation.matches(): that
        // matcher can intentionally ignore metadata for wildcard ingredients.
        // State equality (including metadata) is required before removing a block.
        for (Tuple<T, IBlockState> candidate : candidates) {
            if (candidate.getSecond() == current || candidate.getSecond().equals(current)) {
                return candidate;
            }
        }
        Block currentBlock = current.getBlock();
        int currentMeta = currentBlock.getMetaFromState(current);
        for (Tuple<T, IBlockState> candidate : candidates) {
            IBlockState candidateState = candidate.getSecond();
            Block candidateBlock = candidateState.getBlock();
            if (currentBlock == candidateBlock && currentMeta == candidateBlock.getMetaFromState(candidateState)) {
                return candidate;
            }
        }
        return null;
    }

    private static SplitStructureCandidates splitStructureCandidates(BlockArray.BlockInformation information) {
        List<Tuple<ItemStack, IBlockState>> itemCandidates = new ArrayList<>();
        List<Tuple<FluidStack, IBlockState>> fluidCandidates = new ArrayList<>();
        for (Tuple<ItemStack, IBlockState> tuple : information.getBlockStateIngredientList()) {
            FluidStack fluidStack = FluidUtils.getFluidStackFromBlockState(tuple.getSecond());
            if (fluidStack == null) {
                itemCandidates.add(tuple);
            } else {
                fluidCandidates.add(new Tuple<>(fluidStack, tuple.getSecond()));
            }
        }
        return new SplitStructureCandidates(itemCandidates, fluidCandidates);
    }

    private static final class SplitStructureCandidates {
        private final List<Tuple<ItemStack, IBlockState>> itemCandidates;
        private final List<Tuple<FluidStack, IBlockState>> fluidCandidates;

        private SplitStructureCandidates(List<Tuple<ItemStack, IBlockState>> itemCandidates, List<Tuple<FluidStack, IBlockState>> fluidCandidates) {
            this.itemCandidates = itemCandidates;
            this.fluidCandidates = fluidCandidates;
        }

        private boolean hasItems() {
            return !itemCandidates.isEmpty();
        }

        private boolean hasFluids() {
            return !fluidCandidates.isEmpty();
        }
    }
}
