package com.lw.mmce_advanced_builder_tool.common.task;

import com.lw.mmce_advanced_builder_tool.common.ae2.Ae2GridAccess;
import com.lw.mmce_advanced_builder_tool.common.util.MessageLimiter;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import com.lw.mmce_advanced_builder_tool.common.util.StructureIngredients;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Tick-driven teardown of a machine pattern: removes the blocks a machine occupies and returns the
 * recovered materials to the player inventory or to AE storage.
 *
 * <p>Block contents are recovered from two merged sources: the block's own drops, and - for blocks
 * that hide their contents inside a tile entity - the item-handler capability of that tile entity.
 * Removal publishes {@code BlockEvent.BreakEvent} so protection plugins can veto a position; a
 * vetoed or otherwise unremovable position is dropped from the plan instead of being retried forever.
 */
public class MachineDisassemblyTask implements BuildTask {

    private static final int MAX_REPORTS = 8;

    private final World world;
    private final BlockPos ctrlPos;
    private final EntityPlayer player;
    private final DisassemblyPlan.Plan plan;
    private final boolean useAeItems;
    private final boolean useAeFluids;
    private final int tickInterval;
    private final int operationsPerTick;
    private final MessageLimiter reportLimiter = new MessageLimiter(MAX_REPORTS);

    public MachineDisassemblyTask(World world, BlockPos ctrlPos, EntityPlayer player, DisassemblyPlan.Plan plan, boolean useAeItems, boolean useAeFluids, int tickInterval, int operationsPerTick) {
        this.world = world;
        this.ctrlPos = ctrlPos;
        this.player = player;
        this.plan = plan;
        this.useAeItems = useAeItems;
        this.useAeFluids = useAeFluids;
        this.tickInterval = tickInterval;
        this.operationsPerTick = operationsPerTick;
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
        return plan.itemEntries().isEmpty() && plan.fluidEntries().isEmpty();
    }

    @Override
    public void tick() {
        List<DisassemblyPlan.ItemEntry> itemIngredient = plan.itemEntries();
        List<DisassemblyPlan.FluidEntry> fluidIngredient = plan.fluidEntries();
        if (!itemIngredient.isEmpty()) {
            disassembleItemBlock(itemIngredient);
        } else if (!fluidIngredient.isEmpty()) {
            disassembleFluidBlock(fluidIngredient);
        }
    }

    @Override
    public void report() {
    }

    @Override
    public String getCancelledMessageKey() {
        return "message.mmce_advanced_builder_tool.disassembly_cancelled";
    }

    @Override
    public String getSuccessMessageKey() {
        return "message.mmce_advanced_builder_tool.disassembly_success";
    }

    private void disassembleItemBlock(List<DisassemblyPlan.ItemEntry> itemIngredient) {
        Iterator<DisassemblyPlan.ItemEntry> iterator = itemIngredient.iterator();
        DisassemblyPlan.ItemEntry ingredient = iterator.next();
        BlockPos realPos = ctrlPos.add(ingredient.pos());
        if (realPos.equals(ctrlPos)) {
            iterator.remove();
            return;
        }

        Tuple<ItemStack, IBlockState> matched = StructureIngredients.findMatchingItemCandidate(world, realPos, ingredient.candidates());
        if (matched == null) {
            iterator.remove();
            return;
        }

        ItemStack recovered = matched.getFirst().copy();
        if (recovered.isEmpty()) {
            iterator.remove();
            return;
        }
        if (useAeItems) {
            if (!Mods.AE2.isLoading() || !Ae2GridAccess.canInsertItem(player, recovered)) {
                reportLimited("message.mmce_advanced_builder_tool.ae_insert_failed");
                iterator.remove();
                return;
            }
        }
        List<ItemStack> nativeDrops = breakItemBlock(realPos);
        if (nativeDrops == null) {
            return;
        }

        boolean blockReturnedByNativeDrop = false;
        for (ItemStack nativeDrop : nativeDrops) {
            if (ItemStack.areItemsEqual(nativeDrop, recovered)) {
                blockReturnedByNativeDrop = true;
            }
            returnItem(nativeDrop);
        }
        if (!blockReturnedByNativeDrop) {
            returnItem(recovered);
        }
        world.playSound(null, realPos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.0F, 1.0F);
        iterator.remove();
    }

    private void disassembleFluidBlock(List<DisassemblyPlan.FluidEntry> fluidIngredient) {
        Iterator<DisassemblyPlan.FluidEntry> iterator = fluidIngredient.iterator();
        DisassemblyPlan.FluidEntry ingredient = iterator.next();
        BlockPos realPos = ctrlPos.add(ingredient.pos());
        if (realPos.equals(ctrlPos)) {
            iterator.remove();
            return;
        }

        Tuple<FluidStack, IBlockState> matched = StructureIngredients.findMatchingFluidCandidate(world, realPos, ingredient.candidates());
        if (matched == null) {
            iterator.remove();
            return;
        }

        FluidStack recovered = matched.getFirst().copy();
        if (useAeFluids) {
            if (!Mods.AE2.isLoading() || !Ae2GridAccess.canInsertFluid(player, recovered)) {
                reportLimited("message.mmce_advanced_builder_tool.ae_insert_failed");
                iterator.remove();
                return;
            }
        }
        if (!breakBlock(realPos)) {
            iterator.remove();
            return;
        }

        if (useAeFluids) {
            Ae2GridAccess.insertFluid(player, recovered);
        }
        world.playSound(null, realPos, SoundEvents.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 1.0F, 1.0F);
        iterator.remove();
    }

    private boolean breakBlock(BlockPos realPos) {
        IBlockState current = world.getBlockState(realPos);
        if (current.getBlock() == Blocks.AIR) {
            return true;
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, realPos, current, player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            reportLimited("message.mmce_advanced_builder_tool.break_cancelled");
            return false;
        }

        return world.setBlockToAir(realPos);
    }

    private List<ItemStack> breakItemBlock(BlockPos realPos) {
        IBlockState current = world.getBlockState(realPos);
        if (current.getBlock() == Blocks.AIR) {
            return Collections.emptyList();
        }
        TileEntity tileEntity = world.getTileEntity(realPos);

        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, realPos, current, player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            reportLimited("message.mmce_advanced_builder_tool.break_cancelled");
            return null;
        }

        List<ItemStack> drops = collectBlockDrops(realPos, current, tileEntity);
        if (!world.setBlockToAir(realPos)) {
            return null;
        }
        return drops;
    }

    /**
     * Uses the five argument {@code getDrops} overload. The player-aware overload that Forge patches
     * into {@code Block} is deliberately not called here: it is absent from a workspace built on
     * unpatched Minecraft sources, so invoking it would not compile. The consequence is that
     * harvest-sensitive drops (fortune, silk touch) follow the block's default metadata drops.
     */
    private List<ItemStack> collectBlockDrops(BlockPos realPos, IBlockState state, TileEntity tileEntity) {
        NonNullList<ItemStack> blockDrops = NonNullList.create();
        state.getBlock().getDrops(blockDrops, world, realPos, state, 0);

        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack drop : blockDrops) {
            if (!drop.isEmpty()) {
                drops.add(drop.copy());
            }
        }
        collectTileInventoryDrops(tileEntity, drops);
        return drops;
    }

    /**
     * Blocks that keep their contents inside the tile entity (stocking buffers, drives, crates and
     * similar) do not expose them through {@code getDrops}. Reading the inventory capability is
     * exact, whereas inferring drops from newly spawned item entities can double-return or miss
     * stacks. Inventory contents are additive: the shell still drops as a normal item.
     */
    private void collectTileInventoryDrops(TileEntity tileEntity, List<ItemStack> drops) {
        if (!(tileEntity instanceof ICapabilityProvider)
                || !((ICapabilityProvider) tileEntity).hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            return;
        }
        IItemHandler handler = ((ICapabilityProvider) tileEntity).getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (handler == null) {
            return;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stored = handler.getStackInSlot(slot);
            if (!stored.isEmpty()) {
                drops.add(stored.copy());
            }
        }
    }

    private void returnItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (useAeItems && Mods.AE2.isLoading()) {
            ItemStack leftover = Ae2GridAccess.insertItem(player, stack);
            if (!leftover.isEmpty()) {
                giveOrDrop(leftover);
            }
        } else {
            giveOrDrop(stack);
        }
    }

    /**
     * Differs from {@link StructureIngredients#giveOrDrop} on purpose: disassembly recovers bulk
     * materials that must be collectable immediately, so the entity is spawned with no pickup delay.
     */
    private void giveOrDrop(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = stack.copy();
        if (player.inventory.addItemStackToInventory(remaining) || remaining.isEmpty()) {
            return;
        }
        EntityItem entityItem = new EntityItem(world, player.posX, player.posY, player.posZ, remaining.copy());
        entityItem.setNoPickupDelay();
        world.spawnEntity(entityItem);
    }

    private void reportLimited(String key) {
        if (reportLimiter.tryAcquire(player, "message.mmce_advanced_builder_tool.disassembly_suppressed")) {
            StructureIngredients.sendTranslation(player, key);
        }
    }
}
