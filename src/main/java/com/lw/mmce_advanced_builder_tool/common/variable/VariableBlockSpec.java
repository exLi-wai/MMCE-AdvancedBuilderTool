package com.lw.mmce_advanced_builder_tool.common.variable;

import ink.ikx.mmce.common.utils.StackUtils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One block entry from a {@code *.var.json} alias list, such as {@code "modularmachinery:blockcasing@0"}.
 *
 * <p>The syntax mirrors what Modular Machinery accepts in machine patterns: an optional {@code @meta}
 * suffix. Without it the entry covers every metadata value of the block, exactly like MMCE's
 * {@code BlockInformation.getDescriptor} treats a bare registry name.
 */
final class VariableBlockSpec {

    private final Block block;
    private final int meta;
    private final List<Integer> acceptedMetas;

    private VariableBlockSpec(Block block, int meta, List<Integer> acceptedMetas) {
        this.block = block;
        this.meta = meta;
        this.acceptedMetas = acceptedMetas;
    }

    Block getBlock() {
        return block;
    }

    int getMeta() {
        return meta;
    }

    List<Integer> getAcceptedMetas() {
        return acceptedMetas;
    }

    boolean accepts(Block otherBlock, int otherMeta) {
        return block == otherBlock && acceptedMetas.contains(otherMeta);
    }

    IBlockState state() {
        return block.getStateFromMeta(meta);
    }

    ItemStack displayStack() {
        ItemStack stack = stackFromState(state());
        if (!stack.isEmpty()) {
            return stack;
        }
        return new ItemStack(block, 1, meta);
    }

    String describe() {
        ResourceLocation name = block.getRegistryName();
        String base = name == null ? block.getTranslationKey() : name.toString();
        return acceptedMetas.size() == 1 ? base + "@" + meta : base;
    }

    static VariableBlockSpec parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        int meta = -1;
        int at = text.indexOf('@');
        if (at >= 0 && at < text.length() - 1) {
            try {
                meta = Integer.parseInt(text.substring(at + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            text = text.substring(0, at).trim();
        }
        ResourceLocation name;
        try {
            name = new ResourceLocation(text);
        } catch (RuntimeException e) {
            return null;
        }
        IForgeRegistry<Block> registry = ForgeRegistries.BLOCKS;
        if (!registry.containsKey(name)) {
            return null;
        }
        Block block = registry.getValue(name);
        if (block == null) {
            return null;
        }
        if (meta < 0) {
            return new VariableBlockSpec(block, 0, collectMetas(block));
        }
        IBlockState state = block.getStateFromMeta(meta);
        return new VariableBlockSpec(block, block.getMetaFromState(state), Collections.singletonList(meta));
    }

    private static List<Integer> collectMetas(Block block) {
        List<Integer> metas = new ArrayList<>();
        for (IBlockState state : block.getBlockState().getValidStates()) {
            int value = block.getMetaFromState(state);
            if (!metas.contains(value)) {
                metas.add(value);
            }
        }
        if (metas.isEmpty()) {
            metas.add(0);
        }
        return metas;
    }

    private static ItemStack stackFromState(IBlockState state) {
        try {
            return StackUtils.getStackFromBlockState(state);
        } catch (RuntimeException | LinkageError e) {
            return new ItemStack(Items.AIR);
        }
    }
}
