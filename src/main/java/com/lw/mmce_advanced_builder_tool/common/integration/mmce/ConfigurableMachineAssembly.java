package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.implementations.ContainerCraftConfirm;
import com.google.common.collect.ImmutableSet;
import com.lw.mmce_advanced_builder_tool.common.util.AdvancedBuilderUtils;
import com.lw.mmce_advanced_builder_tool.common.util.MessageLimiter;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import hellfirepvp.modularmachinery.ModularMachinery;
import ink.ikx.mmce.common.assembly.MachineAssembly;
import ink.ikx.mmce.common.utils.StructureIngredient;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ConfigurableMachineAssembly extends MachineAssembly implements AdvancedBuilderTask, AdvancedBuilderCraftingRequester {

    private static final int MAX_MISSING_REPORTS = 8;
    private static final int CRAFTING_BUILD_INTERVAL_TICKS = 20;
    private static final int CRAFT_REQUEST_LOST_GRACE_TICKS = 100;
    private static final ResourceLocation MMCE_BLOCK_CASING = new ResourceLocation("modularmachinery", "blockcasing");

    private final boolean useAeItems;
    private final boolean useAeFluids;
    private final boolean craftMissing;
    private final int tickInterval;
    private final int operationsPerTick;
    private final MessageLimiter blockedReportLimiter = new MessageLimiter(MAX_MISSING_REPORTS);
    private final List<MissingItemEntry> missingItems = new ArrayList<>();
    private final List<MissingFluidEntry> missingFluids = new ArrayList<>();
    private final List<CraftableMissingEntry> craftableMissingEntries = new ArrayList<>();
    private List<IFluidHandlerItem> batchFluidHandlers;
    private Ae2AssemblyExtractor.CraftingGuiRequest activeCraftRequest;
    private CraftableMissingEntry activeCraftGuiEntry;
    private CraftableMissingEntry activeCraftAmountEntry;
    private long lastCraftStateCheckTick = -1;
    private boolean skippedMissingMaterials;
    private boolean cancelled;
    private boolean allCraftingGuisOffered;
    private int nextCraftableIndex;
    private int submittedCraftCount;

    public ConfigurableMachineAssembly(World world, BlockPos ctrlPos, EntityPlayer player, StructureIngredient ingredient, boolean useAeItems, boolean useAeFluids, boolean craftMissing, int tickInterval, int operationsPerTick) {
        super(world, ctrlPos, player, ingredient);
        this.useAeItems = useAeItems;
        this.useAeFluids = useAeFluids;
        this.craftMissing = craftMissing;
        this.tickInterval = tickInterval;
        this.operationsPerTick = operationsPerTick;
        cacheInitialCraftingShortages();
    }

    public int getTickInterval() {
        return tickInterval;
    }

    public int getOperationsPerTick() {
        return operationsPerTick;
    }

    @Override
    public void beginBatch() {
        batchFluidHandlers = null;
    }

    @Override
    public void endBatch() {
        batchFluidHandlers = null;
    }

    @Override
    public void tick() {
        if (cancelled) {
            return;
        }
        updateCraftingFlow();
        if (isCancelled()) {
            return;
        }
        openCraftingGuiIfNeeded();
        if (shouldThrottleForCrafting() && getWorld().getTotalWorldTime() % CRAFTING_BUILD_INTERVAL_TICKS != 0) {
            return;
        }
        assembly(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled || (!isCompleted() && allSubmittedCraftsCancelled());
    }

    @Override
    public void cancel() {
        cancelled = true;
        activeCraftRequest = null;
        activeCraftGuiEntry = null;
        if (activeCraftAmountEntry != null) {
            activeCraftAmountEntry.cancelAmountProbe();
            activeCraftAmountEntry = null;
        }
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            entry.cancelLink();
        }
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        ImmutableSet.Builder<ICraftingLink> builder = ImmutableSet.builder();
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.link != null && !entry.link.isDone() && !entry.link.isCanceled()) {
                builder.add(entry.link);
            }
        }
        return builder.build();
    }

    @Override
    public IAEItemStack injectCraftedItems(ICraftingLink link, IAEItemStack stack, Actionable mode) {
        if (stack == null || stack.getStackSize() <= 0) {
            return null;
        }
        CraftableMissingEntry entry = findSubmittedCraft(link);
        if (entry == null || !stack.isSameType(entry.request)) {
            return stack;
        }
        if (mode == Actionable.SIMULATE) {
            return null;
        }
        long leftover = entry.insertCraftedOutput(stack);
        if (leftover <= 0) {
            return null;
        }
        IAEItemStack remaining = stack.copy();
        remaining.setStackSize(leftover);
        return remaining;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        CraftableMissingEntry entry = findSubmittedCraft(link);
        if (entry == null) {
            return;
        }
        if (link.isCanceled()) {
            entry.markCancelled(getWorld().getTotalWorldTime());
        } else if (link.isDone()) {
            entry.markDone(getWorld().getTotalWorldTime());
        }
    }

    @Override
    public void abt$setCraftingLink(ICraftingLink link) {
        if (activeCraftGuiEntry == null) {
            return;
        }
        if (link == null) {
            activeCraftGuiEntry.markSkipped();
        } else {
            activeCraftGuiEntry.markSubmitted(link, activeCraftRequest, getWorld().getTotalWorldTime());
            submittedCraftCount++;
        }
        activeCraftRequest = null;
        activeCraftGuiEntry = null;
    }

    @Override
    public IGridNode getActionableNode() {
        if (activeCraftRequest != null) {
            return activeCraftRequest.getNode();
        }
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.link != null && !entry.link.isDone() && !entry.link.isCanceled() && entry.node != null) {
                return entry.node;
            }
        }
        return null;
    }

    @Override
    public void report() {
        if (isCompleted()) {
            cancelUnfinishedCrafts();
        }
        reportMissingMaterials();
    }

    @Override
    public String getCancelledMessageKey() {
        return "message.mmce_advanced_builder_tool.cancelled";
    }

    @Override
    public String getSuccessMessageKey() {
        return "message.mmce_advanced_builder_tool.success";
    }

    @Override
    public void assembly(boolean consumeInventory) {
        List<StructureIngredient.ItemIngredient> itemIngredient = getIngredient().itemIngredient();
        List<StructureIngredient.FluidIngredient> fluidIngredient = getIngredient().fluidIngredient();
        if (!itemIngredient.isEmpty()) {
            assemblyItemBlocks(itemIngredient);
        } else if (!fluidIngredient.isEmpty()) {
            assemblyFluidBlocks(fluidIngredient);
        }
    }

    private void assemblyItemBlocks(List<StructureIngredient.ItemIngredient> itemIngredient) {
        Iterator<StructureIngredient.ItemIngredient> iterator = itemIngredient.iterator();
        StructureIngredient.ItemIngredient ingredient = iterator.next();
        BlockPos realPos = getCtrlPos().add(ingredient.pos());
        if (!replaceCheck(realPos)) {
            iterator.remove();
            return;
        }

        List<Tuple<ItemStack, IBlockState>> candidates = selectItemCandidatesForPosition(ingredient.pos(), ingredient.ingredientList());
        Tuple<ItemStack, IBlockState> consumed = consumeFirstAvailableItem(ingredient.pos(), candidates);
        if (consumed == null) {
            ItemStack required = selectRequirementItemStack(ingredient.pos(), candidates);
            if (shouldWaitForItemCraft(ingredient.pos(), required)) {
                iterator.remove();
                itemIngredient.add(ingredient);
                return;
            }
            addMissingItem(required);
            skippedMissingMaterials = true;
            iterator.remove();
            return;
        }
        ItemStack required = consumed.getFirst().copy();
        IBlockState state = consumed.getSecond();

        if (!placeAssemblyBlock(realPos, state)) {
            getPlayer().inventory.addItemStackToInventory(required);
        } else {
            getWorld().playSound(null, realPos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
            applyTileNbt(realPos, state, ingredient);
        }
        iterator.remove();
    }

    private void assemblyFluidBlocks(List<StructureIngredient.FluidIngredient> fluidIngredient) {
        Iterator<StructureIngredient.FluidIngredient> iterator = fluidIngredient.iterator();
        StructureIngredient.FluidIngredient ingredient = iterator.next();
        BlockPos realPos = getCtrlPos().add(ingredient.pos());
        if (!replaceCheck(realPos)) {
            iterator.remove();
            return;
        }

        List<Tuple<FluidStack, IBlockState>> candidates = selectFluidCandidatesForPosition(ingredient.pos(), ingredient.ingredientList());
        Tuple<FluidStack, IBlockState> consumed = consumeFirstAvailableFluid(ingredient.pos(), candidates);
        if (consumed == null) {
            FluidStack required = selectRequirementFluidStack(ingredient.pos(), candidates);
            if (shouldWaitForFluidCraft(ingredient.pos(), required)) {
                iterator.remove();
                fluidIngredient.add(ingredient);
                return;
            }
            addMissingFluid(required);
            skippedMissingMaterials = true;
            iterator.remove();
            return;
        }
        IBlockState state = consumed.getSecond();

        if (placeAssemblyBlock(realPos, state)) {
            getWorld().playSound(null, realPos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0F, 1.0F);
        }
        iterator.remove();
    }

    private boolean placeAssemblyBlock(BlockPos realPos, IBlockState state) {
        IBlockState original = getWorld().getBlockState(realPos);
        getWorld().setBlockState(realPos, state);
        BlockEvent.PlaceEvent event = new BlockEvent.PlaceEvent(new BlockSnapshot(getWorld(), realPos, state), original, getPlayer(), EnumHand.MAIN_HAND);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            getWorld().setBlockState(realPos, original);
            return false;
        }
        return true;
    }

    private Tuple<ItemStack, IBlockState> consumeFirstAvailableItem(BlockPos relativePos, List<Tuple<ItemStack, IBlockState>> candidates) {
        for (Tuple<ItemStack, IBlockState> tuple : candidates) {
            if (consumeItem(relativePos, tuple.getFirst())) {
                return tuple;
            }
        }
        return null;
    }

    private List<Tuple<ItemStack, IBlockState>> selectItemCandidatesForPosition(BlockPos relativePos, List<Tuple<ItemStack, IBlockState>> candidates) {
        Tuple<ItemStack, IBlockState> casingCandidate = findMechanicalCasingCandidate(candidates);
        if (casingCandidate != null) {
            return singleCandidate(casingCandidate);
        }
        Tuple<ItemStack, IBlockState> craftReservedCandidate = findCraftReservedItemCandidate(relativePos, candidates);
        if (craftReservedCandidate != null) {
            return singleCandidate(craftReservedCandidate);
        }
        return candidates;
    }

    private Tuple<FluidStack, IBlockState> consumeFirstAvailableFluid(BlockPos relativePos, List<Tuple<FluidStack, IBlockState>> candidates) {
        for (Tuple<FluidStack, IBlockState> tuple : candidates) {
            if (consumeFluid(relativePos, tuple.getFirst())) {
                return tuple;
            }
        }
        return null;
    }

    private List<Tuple<FluidStack, IBlockState>> selectFluidCandidatesForPosition(BlockPos relativePos, List<Tuple<FluidStack, IBlockState>> candidates) {
        Tuple<FluidStack, IBlockState> craftReservedCandidate = findCraftReservedFluidCandidate(relativePos, candidates);
        if (craftReservedCandidate != null) {
            return singleCandidate(craftReservedCandidate);
        }
        return candidates;
    }

    private Tuple<ItemStack, IBlockState> findMechanicalCasingCandidate(List<Tuple<ItemStack, IBlockState>> candidates) {
        for (Tuple<ItemStack, IBlockState> tuple : candidates) {
            if (isMechanicalCasing(tuple.getFirst())) {
                return tuple;
            }
        }
        return null;
    }

    private Tuple<ItemStack, IBlockState> findCraftReservedItemCandidate(BlockPos relativePos, List<Tuple<ItemStack, IBlockState>> candidates) {
        for (Tuple<ItemStack, IBlockState> tuple : candidates) {
            CraftableMissingEntry entry = findManagedCraftableMissing(relativePos, tuple.getFirst());
            if (entry != null && entry.shouldReserveCandidate()) {
                return tuple;
            }
        }
        return null;
    }

    private Tuple<FluidStack, IBlockState> findCraftReservedFluidCandidate(BlockPos relativePos, List<Tuple<FluidStack, IBlockState>> candidates) {
        for (Tuple<FluidStack, IBlockState> tuple : candidates) {
            CraftableMissingEntry entry = findManagedCraftableMissing(relativePos, tuple.getFirst());
            if (entry != null && entry.shouldReserveCandidate()) {
                return tuple;
            }
        }
        return null;
    }

    private <T> List<Tuple<T, IBlockState>> singleCandidate(Tuple<T, IBlockState> candidate) {
        List<Tuple<T, IBlockState>> selected = new ArrayList<>(1);
        selected.add(candidate);
        return selected;
    }

    private boolean consumeItem(BlockPos relativePos, ItemStack required) {
        CraftableMissingEntry managedEntry = findManagedCraftableMissing(relativePos, required);
        if (managedEntry != null) {
            return consumeManagedItem(managedEntry, required);
        }
        if (MachineAssembly.consumeInventoryItem(required, getPlayer().inventory.mainInventory)) {
            return true;
        }
        return useAeItems && Mods.AE2.isLoading() && Ae2AssemblyExtractor.extractItemSilently(getPlayer(), required);
    }

    private boolean consumeManagedItem(CraftableMissingEntry managedEntry, ItemStack required) {
        CraftReservation reservation = managedEntry.reserveCrafted(required.getCount());
        if (reservation != null) {
            if (Mods.AE2.isLoading() && Ae2AssemblyExtractor.extractCraftedItem(getPlayer(), required)) {
                return true;
            }
            managedEntry.restoreAvailable(reservation);
        }
        reservation = managedEntry.reserveDirect(required.getCount());
        if (reservation != null) {
            if (MachineAssembly.consumeInventoryItem(required, getPlayer().inventory.mainInventory)
                    || useAeItems && Mods.AE2.isLoading() && Ae2AssemblyExtractor.extractItemSilently(getPlayer(), required)) {
                return true;
            }
            managedEntry.restoreAvailable(reservation);
        }
        return false;
    }

    private boolean consumeFluid(BlockPos relativePos, FluidStack required) {
        CraftableMissingEntry managedEntry = findManagedCraftableMissing(relativePos, required);
        if (managedEntry != null) {
            return consumeManagedFluid(managedEntry, required);
        }
        if (MachineAssembly.consumeInventoryFluid(required, getBatchFluidHandlers())) {
            return true;
        }
        return useAeFluids && Mods.AE2.isLoading() && Ae2AssemblyExtractor.extractFluidSilently(getPlayer(), required);
    }

    private boolean consumeManagedFluid(CraftableMissingEntry managedEntry, FluidStack required) {
        CraftReservation reservation = managedEntry.reserveCrafted(required.amount);
        if (reservation != null) {
            if (Mods.AE2.isLoading() && Ae2AssemblyExtractor.extractCraftedFluid(getPlayer(), required)) {
                return true;
            }
            managedEntry.restoreAvailable(reservation);
        }
        reservation = managedEntry.reserveDirect(required.amount);
        if (reservation != null) {
            if (MachineAssembly.consumeInventoryFluid(required, getBatchFluidHandlers())
                    || useAeFluids && Mods.AE2.isLoading() && Ae2AssemblyExtractor.extractFluidSilently(getPlayer(), required)) {
                return true;
            }
            managedEntry.restoreAvailable(reservation);
        }
        return false;
    }

    private List<IFluidHandlerItem> getBatchFluidHandlers() {
        if (batchFluidHandlers == null) {
            batchFluidHandlers = AdvancedBuilderUtils.getFluidHandlerItems(getPlayer().inventory.mainInventory);
        }
        return batchFluidHandlers;
    }

    public void openCraftingGuiIfNeeded() {
        if (!craftMissing || !Mods.AE2.isLoading() || activeCraftGuiEntry != null || allCraftingGuisOffered) {
            return;
        }
        if (activeCraftAmountEntry != null) {
            if (!activeCraftAmountEntry.tickAmountProbe()) {
                return;
            }
            CraftableMissingEntry entry = activeCraftAmountEntry;
            activeCraftAmountEntry = null;
            long amount = entry.getProbedAmount();
            if (amount <= 0) {
                entry.markSkipped();
                return;
            }
            openCraftingGui(entry, amount);
            return;
        }
        while (nextCraftableIndex < craftableMissingEntries.size()) {
            CraftableMissingEntry entry = craftableMissingEntries.get(nextCraftableIndex++);
            if (!entry.canOfferGui()) {
                continue;
            }
            entry.startAmountProbe();
            activeCraftAmountEntry = entry;
            if (!entry.tickAmountProbe()) {
                return;
            }
            activeCraftAmountEntry = null;
            long amount = entry.getProbedAmount();
            if (amount <= 0) {
                entry.markSkipped();
                continue;
            }
            openCraftingGui(entry, amount);
            return;
        }
        allCraftingGuisOffered = true;
    }

    private void openCraftingGui(CraftableMissingEntry entry, long amount) {
        entry.prepareRequest(amount);
        Ae2AssemblyExtractor.CraftingGuiRequest request = Ae2AssemblyExtractor.openCraftConfirmGui(getPlayer(), entry.request.copy(), this);
        if (request != null) {
            activeCraftGuiEntry = entry;
            activeCraftRequest = request;
            entry.markOffered(request, getWorld().getTotalWorldTime());
            return;
        }
        entry.markSkipped();
    }

    private void cacheInitialCraftingShortages() {
        if (!craftMissing || !Mods.AE2.isLoading()) {
            allCraftingGuisOffered = true;
            return;
        }
        cacheInitialItemCraftingShortages();
        cacheInitialFluidCraftingShortages();
        allCraftingGuisOffered = craftableMissingEntries.isEmpty();
    }

    private void cacheInitialItemCraftingShortages() {
        List<RequiredItemEntry> requiredItems = collectRequiredItemEntries();
        for (RequiredItemEntry entry : requiredItems) {
            if (entry.amount <= 0) {
                continue;
            }
            IAEItemStack request = Ae2AssemblyExtractor.toAeItemRequest(entry.stack, entry.amount);
            if (request != null) {
                craftableMissingEntries.add(new CraftableMissingEntry(entry.stack, null, request, entry.amount, entry.directAmount, entry.positions));
            }
        }
    }

    private void cacheInitialFluidCraftingShortages() {
        List<RequiredFluidEntry> requiredFluids = collectRequiredFluidEntries();
        subtractPlayerFluids(requiredFluids);
        for (RequiredFluidEntry entry : requiredFluids) {
            if (entry.amount <= 0) {
                continue;
            }
            long afterPlayer = entry.amount;
            long stored = useAeFluids ? Ae2AssemblyExtractor.getStoredFluidAmount(getPlayer(), entry.fluid) : 0;
            long storedUsed = Math.min(afterPlayer, stored);
            long shortage = afterPlayer - storedUsed;
            if (shortage <= 0) {
                continue;
            }
            IAEItemStack request = Ae2AssemblyExtractor.toAeFluidRequest(entry.fluid, shortage);
            if (request != null) {
                long playerUsed = entry.totalAmount - afterPlayer;
                craftableMissingEntries.add(new CraftableMissingEntry(ItemStack.EMPTY, entry.fluid, request, shortage, playerUsed + storedUsed, entry.positions));
            }
        }
    }

    private List<RequiredItemEntry> collectRequiredItemEntries() {
        List<RequiredItemEntry> requiredItems = new ArrayList<>();
        List<ItemAvailabilityEntry> availableItems = new ArrayList<>();
        for (StructureIngredient.ItemIngredient ingredient : getIngredient().itemIngredient()) {
            if (ingredient.ingredientList().isEmpty()) {
                continue;
            }
            ItemStack selected = selectRequirementItemStack(ingredient.pos(), ingredient.ingredientList(), availableItems);
            if (selected.isEmpty()) {
                continue;
            }
            addItemShortageIfNeeded(requiredItems, availableItems, selected, ingredient.pos());
        }
        return requiredItems;
    }

    private List<RequiredFluidEntry> collectRequiredFluidEntries() {
        List<RequiredFluidEntry> requiredFluids = new ArrayList<>();
        for (StructureIngredient.FluidIngredient ingredient : getIngredient().fluidIngredient()) {
            if (ingredient.ingredientList().isEmpty()) {
                continue;
            }
            FluidStack fluid = selectRequirementFluidStack(ingredient.pos(), ingredient.ingredientList());
            if (fluid == null || fluid.amount <= 0) {
                continue;
            }
            addRequiredFluid(requiredFluids, fluid, fluid.amount, ingredient.pos());
        }
        return requiredFluids;
    }

    private ItemStack selectRequirementItemStack(BlockPos relativePos, List<Tuple<ItemStack, IBlockState>> candidates) {
        return selectRequirementItemStack(relativePos, candidates, null);
    }

    private ItemStack selectRequirementItemStack(BlockPos relativePos, List<Tuple<ItemStack, IBlockState>> candidates, List<ItemAvailabilityEntry> availableItems) {
        Tuple<ItemStack, IBlockState> casingCandidate = findMechanicalCasingCandidate(candidates);
        if (casingCandidate != null) {
            return casingCandidate.getFirst();
        }
        for (Tuple<ItemStack, IBlockState> candidate : candidates) {
            ItemStack stack = candidate.getFirst();
            if (stack.isEmpty()) {
                continue;
            }
            long available = availableItems == null ? getStoredAvailableItemAmount(stack) : getAvailableItemAmount(availableItems, stack);
            if (available >= stack.getCount()) {
                return stack;
            }
        }
        if (useAeItems && Mods.AE2.isLoading()) {
            for (Tuple<ItemStack, IBlockState> candidate : candidates) {
                ItemStack stack = candidate.getFirst();
                IAEItemStack request = Ae2AssemblyExtractor.toAeItemRequest(stack, stack.isEmpty() ? 0 : stack.getCount());
                if (request != null && Ae2AssemblyExtractor.canCraftAeItem(getPlayer(), request)) {
                    return stack;
                }
            }
        }
        if (relativePos != null) {
            for (Tuple<ItemStack, IBlockState> candidate : candidates) {
                CraftableMissingEntry entry = findManagedCraftableMissing(relativePos, candidate.getFirst());
                if (entry != null && entry.shouldReserveCandidate()) {
                    return candidate.getFirst();
                }
            }
        }
        return candidates.isEmpty() ? ItemStack.EMPTY : candidates.get(0).getFirst();
    }

    private FluidStack selectRequirementFluidStack(BlockPos relativePos, List<Tuple<FluidStack, IBlockState>> candidates) {
        for (Tuple<FluidStack, IBlockState> candidate : candidates) {
            FluidStack fluid = candidate.getFirst();
            if (fluid != null && fluid.amount > 0 && getStoredAvailableFluidAmount(fluid) >= fluid.amount) {
                return fluid;
            }
        }
        if (useAeFluids && Mods.AE2.isLoading()) {
            for (Tuple<FluidStack, IBlockState> candidate : candidates) {
                FluidStack fluid = candidate.getFirst();
                IAEItemStack request = Ae2AssemblyExtractor.toAeFluidRequest(fluid, fluid == null ? 0 : fluid.amount);
                if (request != null && Ae2AssemblyExtractor.canCraftAeItem(getPlayer(), request)) {
                    return fluid;
                }
            }
        }
        if (relativePos != null) {
            for (Tuple<FluidStack, IBlockState> candidate : candidates) {
                CraftableMissingEntry entry = findManagedCraftableMissing(relativePos, candidate.getFirst());
                if (entry != null && entry.shouldReserveCandidate()) {
                    return candidate.getFirst();
                }
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0).getFirst();
    }

    private void addItemShortageIfNeeded(List<RequiredItemEntry> requiredItems, List<ItemAvailabilityEntry> availableItems, ItemStack stack, BlockPos relativePos) {
        if (stack.isEmpty()) {
            return;
        }
        long directAmount = reserveItemAmount(availableItems, stack, stack.getCount());
        long shortage = stack.getCount() - directAmount;
        if (shortage > 0) {
            addRequiredItem(requiredItems, stack, shortage, directAmount, relativePos);
        }
    }

    private long getAvailableItemAmount(List<ItemAvailabilityEntry> availableItems, ItemStack stack) {
        return getItemAvailability(availableItems, stack).amount;
    }

    private long reserveItemAmount(List<ItemAvailabilityEntry> availableItems, ItemStack stack, long requestedAmount) {
        ItemAvailabilityEntry availability = getItemAvailability(availableItems, stack);
        long reserved = Math.min(availability.amount, requestedAmount);
        availability.amount -= reserved;
        return reserved;
    }

    private ItemAvailabilityEntry getItemAvailability(List<ItemAvailabilityEntry> availableItems, ItemStack stack) {
        for (ItemAvailabilityEntry entry : availableItems) {
            if (AdvancedBuilderUtils.areItemStacksEqual(entry.stack, stack)) {
                return entry;
            }
        }
        ItemAvailabilityEntry entry = new ItemAvailabilityEntry(stack, getStoredAvailableItemAmount(stack));
        availableItems.add(entry);
        return entry;
    }

    private long getStoredAvailableItemAmount(ItemStack stack) {
        return getPlayerItemAmount(stack) + (useAeItems ? Ae2AssemblyExtractor.getStoredItemAmount(getPlayer(), stack) : 0);
    }

    private long getStoredAvailableFluidAmount(FluidStack fluid) {
        return getPlayerFluidAmount(fluid) + (useAeFluids ? Ae2AssemblyExtractor.getStoredFluidAmount(getPlayer(), fluid) : 0);
    }

    private long getPlayerItemAmount(ItemStack stack) {
        long amount = 0;
        for (ItemStack inventoryStack : getPlayer().inventory.mainInventory) {
            if (AdvancedBuilderUtils.areItemStacksEqual(inventoryStack, stack)) {
                amount += inventoryStack.getCount();
            }
        }
        return amount;
    }

    private long getPlayerFluidAmount(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) {
            return 0;
        }
        long amount = 0;
        for (IFluidHandlerItem handler : AdvancedBuilderUtils.getFluidHandlerItems(getPlayer().inventory.mainInventory)) {
            for (IFluidTankProperties property : handler.getTankProperties()) {
                FluidStack contained = property.getContents();
                if (AdvancedBuilderUtils.areFluidsEqual(contained, fluid)) {
                    amount += contained.amount;
                }
            }
        }
        return amount;
    }

    private void subtractPlayerFluids(List<RequiredFluidEntry> requiredFluids) {
        for (IFluidHandlerItem handler : AdvancedBuilderUtils.getFluidHandlerItems(getPlayer().inventory.mainInventory)) {
            for (IFluidTankProperties property : handler.getTankProperties()) {
                FluidStack contained = property.getContents();
                if (contained == null || contained.amount <= 0) {
                    continue;
                }
                int remaining = contained.amount;
                for (RequiredFluidEntry entry : requiredFluids) {
                    if (remaining <= 0) {
                        break;
                    }
                    if (!AdvancedBuilderUtils.areFluidsEqual(contained, entry.fluid)) {
                        continue;
                    }
                    long consumed = Math.min(entry.amount, remaining);
                    entry.amount -= consumed;
                    remaining -= consumed;
                }
            }
        }
    }

    private void addRequiredItem(List<RequiredItemEntry> requiredItems, ItemStack stack, long amount, long directAmount, BlockPos relativePos) {
        for (RequiredItemEntry entry : requiredItems) {
            if (AdvancedBuilderUtils.areItemStacksEqual(entry.stack, stack)) {
                entry.amount += amount;
                entry.directAmount += directAmount;
                entry.positions.add(relativePos);
                return;
            }
        }
        requiredItems.add(new RequiredItemEntry(stack, amount, directAmount, relativePos));
    }

    private void addRequiredFluid(List<RequiredFluidEntry> requiredFluids, FluidStack fluid, long amount, BlockPos relativePos) {
        for (RequiredFluidEntry entry : requiredFluids) {
            if (AdvancedBuilderUtils.areFluidsEqual(entry.fluid, fluid)) {
                entry.totalAmount += amount;
                entry.amount += amount;
                entry.positions.add(relativePos);
                return;
            }
        }
        requiredFluids.add(new RequiredFluidEntry(fluid, amount, relativePos));
    }

    private boolean shouldWaitForItemCraft(BlockPos relativePos, ItemStack required) {
        CraftableMissingEntry entry = findManagedCraftableMissing(relativePos, required);
        if (entry == null) {
            return false;
        }
        openCraftingGuiIfNeeded();
        return entry.shouldWait(getWorld().getTotalWorldTime());
    }

    private boolean shouldWaitForFluidCraft(BlockPos relativePos, FluidStack required) {
        CraftableMissingEntry entry = findManagedCraftableMissing(relativePos, required);
        if (entry == null) {
            return false;
        }
        openCraftingGuiIfNeeded();
        return entry.shouldWait(getWorld().getTotalWorldTime());
    }

    private CraftableMissingEntry findManagedCraftableMissing(BlockPos relativePos, ItemStack required) {
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.matches(relativePos, required)) {
                return entry;
            }
        }
        return null;
    }

    private CraftableMissingEntry findManagedCraftableMissing(BlockPos relativePos, FluidStack required) {
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.matches(relativePos, required)) {
                return entry;
            }
        }
        return null;
    }

    private CraftableMissingEntry findSubmittedCraft(ICraftingLink link) {
        if (link == null) {
            return null;
        }
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.link == link || (entry.link != null && entry.link.getCraftingID().equals(link.getCraftingID()))) {
                return entry;
            }
        }
        return null;
    }

    private boolean isMechanicalCasing(ItemStack stack) {
        return !stack.isEmpty() && MMCE_BLOCK_CASING.equals(stack.getItem().getRegistryName()) && stack.getMetadata() == 0;
    }

    private void updateCraftingFlow() {
        long now = getWorld().getTotalWorldTime();
        if (lastCraftStateCheckTick == now) {
            return;
        }
        lastCraftStateCheckTick = now;
        updateActiveCraftGui(now);
        updateSubmittedCrafts(now);
    }

    private void updateActiveCraftGui(long now) {
        if (activeCraftGuiEntry == null) {
            return;
        }
        if (activeCraftGuiEntry.link != null) {
            activeCraftRequest = null;
            activeCraftGuiEntry = null;
            return;
        }
        boolean confirmOpen = getPlayer().openContainer instanceof ContainerCraftConfirm;
        if (confirmOpen) {
            ContainerCraftConfirm confirm = (ContainerCraftConfirm) getPlayer().openContainer;
            if (confirm.isSimulation() && confirm.getUsedBytes() > 0 && now - activeCraftGuiEntry.offeredTick > 20) {
                activeCraftGuiEntry.markSkipped();
                activeCraftRequest = null;
                activeCraftGuiEntry = null;
            }
            return;
        }
        if (activeCraftRequest != null && activeCraftRequest.isRequesting()) {
            activeCraftGuiEntry.markSubmitted(null, activeCraftRequest, now);
            submittedCraftCount++;
            activeCraftRequest = null;
            activeCraftGuiEntry = null;
            return;
        }
        activeCraftGuiEntry.markSkipped();
        activeCraftRequest = null;
        activeCraftGuiEntry = null;
    }

    private void updateSubmittedCrafts(long now) {
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.done || entry.cancelled) {
                continue;
            }
            if (entry.link == null) {
                entry.updateLinklessRequestState(now);
                continue;
            }
            if (entry.link.isCanceled()) {
                entry.markCancelled(now);
                continue;
            }
            if (entry.link.isDone()) {
                entry.markDone(now);
                continue;
            }
            if (entry.isRequestLost(now)) {
                entry.markCancelled(now);
            }
        }
    }

    private boolean shouldThrottleForCrafting() {
        if (activeCraftGuiEntry != null || activeCraftAmountEntry != null) {
            return true;
        }
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.shouldThrottle()) {
                return true;
            }
        }
        return false;
    }

    private boolean allSubmittedCraftsCancelled() {
        if (submittedCraftCount <= 0 || !allCraftingGuisOffered || activeCraftGuiEntry != null) {
            return false;
        }
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            if (entry.submitted && entry.link == null && !entry.cancelled) {
                return false;
            }
            if (entry.link != null && !entry.cancelled) {
                return false;
            }
        }
        return true;
    }

    private void cancelUnfinishedCrafts() {
        activeCraftRequest = null;
        activeCraftGuiEntry = null;
        if (activeCraftAmountEntry != null) {
            activeCraftAmountEntry.cancelAmountProbe();
            activeCraftAmountEntry = null;
        }
        for (CraftableMissingEntry entry : craftableMissingEntries) {
            entry.cancelLink();
        }
    }

    private void applyTileNbt(BlockPos realPos, IBlockState state, StructureIngredient.ItemIngredient ingredient) {
        TileEntity te = getWorld().getTileEntity(realPos);
        if (te != null && ingredient.nbt() != null) {
            try {
                te.readFromNBT(ingredient.nbt());
            } catch (Exception e) {
                ModularMachinery.log.warn("Failed to apply NBT to TileEntity!", e);
                getWorld().removeTileEntity(realPos);
                getWorld().setTileEntity(realPos, state.getBlock().createTileEntity(getWorld(), state));
            }
        }
    }

    public void reportMissingMaterials() {
        if (isCompleted() && !skippedMissingMaterials) {
            return;
        }
        if (missingItems.isEmpty() && missingFluids.isEmpty()) {
            return;
        }
        AdvancedBuilderUtils.sendTranslation(getPlayer(), "message.mmce_advanced_builder_tool.missing_summary_header");
        for (MissingItemEntry entry : missingItems) {
            AdvancedBuilderUtils.sendTranslation(getPlayer(), "message.mmce_advanced_builder_tool.missing_item_entry",
                    entry.amount, entry.stack.getDisplayName());
        }
        for (MissingFluidEntry entry : missingFluids) {
            AdvancedBuilderUtils.sendTranslation(getPlayer(), "message.mmce_advanced_builder_tool.missing_fluid_entry",
                    entry.amount, entry.fluid.getLocalizedName());
        }
    }

    private void addMissingItem(ItemStack required) {
        addMissingItem(required, required.getCount());
    }

    private void addMissingItem(ItemStack required, long amount) {
        if (required.isEmpty() || amount <= 0) {
            return;
        }
        for (MissingItemEntry entry : missingItems) {
            if (AdvancedBuilderUtils.areItemStacksEqual(entry.stack, required)) {
                entry.amount += amount;
                return;
            }
        }
        missingItems.add(new MissingItemEntry(required, amount));
    }

    private void addMissingFluid(FluidStack required) {
        if (required == null) {
            return;
        }
        addMissingFluid(required, required.amount);
    }

    private void addMissingFluid(FluidStack required, long amount) {
        if (required == null || required.amount <= 0 || amount <= 0) {
            return;
        }
        for (MissingFluidEntry entry : missingFluids) {
            if (AdvancedBuilderUtils.areFluidsEqual(entry.fluid, required)) {
                entry.amount += amount;
                return;
            }
        }
        missingFluids.add(new MissingFluidEntry(required, amount));
    }

    private boolean replaceCheck(BlockPos realPos) {
        if (getWorld().isOutsideBuildHeight(realPos)) {
            reportBlocked(realPos, "message.mmce_advanced_builder_tool.too_high");
            return false;
        }

        if (AdvancedBuilderUtils.isReplaceableForAssembly(getWorld(), realPos)) {
            return true;
        }

        reportBlocked(realPos, "message.mmce_advanced_builder_tool.cannot_replace");
        return false;
    }

    private void reportBlocked(BlockPos pos, String key) {
        if (blockedReportLimiter.tryAcquire(getPlayer(), "message.mmce_advanced_builder_tool.blocked_suppressed")) {
            AdvancedBuilderUtils.sendTranslation(getPlayer(), key, AdvancedBuilderUtils.posToString(pos));
        }
    }

    private static final class MissingItemEntry {
        private final ItemStack stack;
        private long amount;

        private MissingItemEntry(ItemStack stack, long amount) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.amount = amount;
        }
    }

    private static final class MissingFluidEntry {
        private final FluidStack fluid;
        private long amount;

        private MissingFluidEntry(FluidStack fluid, long amount) {
            this.fluid = fluid.copy();
            this.amount = amount;
        }
    }

    private static final class RequiredItemEntry {
        private final ItemStack stack;
        private final List<BlockPos> positions = new ArrayList<>();
        private long amount;
        private long directAmount;

        private RequiredItemEntry(ItemStack stack, long amount, long directAmount, BlockPos relativePos) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.amount = amount;
            this.directAmount = directAmount;
            this.positions.add(relativePos);
        }
    }

    private static final class ItemAvailabilityEntry {
        private final ItemStack stack;
        private long amount;

        private ItemAvailabilityEntry(ItemStack stack, long amount) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.amount = amount;
        }
    }

    private static final class RequiredFluidEntry {
        private final FluidStack fluid;
        private final List<BlockPos> positions = new ArrayList<>();
        private long totalAmount;
        private long amount;

        private RequiredFluidEntry(FluidStack fluid, long amount, BlockPos relativePos) {
            this.fluid = fluid.copy();
            this.totalAmount = amount;
            this.amount = amount;
            this.positions.add(relativePos);
        }
    }

    private final class CraftableMissingEntry {
        private final ItemStack item;
        private final FluidStack fluid;
        private final IAEItemStack baseRequest;
        private IAEItemStack request;
        private final List<BlockPos> positions;
        private final long shortageAmount;
        private long directRemaining;
        private long craftedRemaining;
        private long outputReceived;
        private ICraftingLink link;
        private IGridNode node;
        private Ae2AssemblyExtractor.CraftingGuiRequest guiRequest;
        private Ae2AssemblyExtractor.CraftingAmountProbe amountProbe;
        private long probedAmount;
        private boolean offered;
        private boolean submitted;
        private boolean skipped;
        private boolean done;
        private boolean cancelled;
        private long offeredTick;
        private long submittedTick;
        private long doneTick;

        private CraftableMissingEntry(ItemStack item, FluidStack fluid, IAEItemStack baseRequest, long shortageAmount, long directAmount, List<BlockPos> positions) {
            this.item = item.isEmpty() ? ItemStack.EMPTY : item.copy();
            if (!this.item.isEmpty()) {
                this.item.setCount(1);
            }
            this.fluid = fluid == null ? null : fluid.copy();
            this.baseRequest = baseRequest.copy();
            this.positions = new ArrayList<>(positions);
            this.shortageAmount = shortageAmount;
            this.directRemaining = directAmount;
        }

        private boolean isFluid() {
            return fluid != null;
        }

        private boolean canOfferGui() {
            return !offered && !submitted && !skipped && !done && !cancelled && shortageAmount > 0;
        }

        private void prepareRequest(long amount) {
            request = baseRequest.copy();
            request.setStackSize(amount);
        }

        private void startAmountProbe() {
            amountProbe = Ae2AssemblyExtractor.startCraftingAmountProbe(getPlayer(), baseRequest, shortageAmount);
            probedAmount = 0;
        }

        private boolean tickAmountProbe() {
            if (amountProbe == null) {
                startAmountProbe();
            }
            if (!amountProbe.tick()) {
                return false;
            }
            probedAmount = amountProbe.getAmount();
            amountProbe = null;
            return true;
        }

        private long getProbedAmount() {
            return probedAmount;
        }

        private void cancelAmountProbe() {
            if (amountProbe != null) {
                amountProbe.cancel();
                amountProbe = null;
            }
        }

        private void markOffered(Ae2AssemblyExtractor.CraftingGuiRequest request, long now) {
            this.guiRequest = request;
            this.node = request.getNode();
            this.offered = true;
            this.offeredTick = now;
        }

        private void markSubmitted(ICraftingLink link, Ae2AssemblyExtractor.CraftingGuiRequest request, long now) {
            this.link = link;
            this.guiRequest = request;
            this.node = request == null ? null : request.getNode();
            this.submitted = true;
            this.submittedTick = now;
            this.craftedRemaining = this.request == null ? 0 : this.request.getStackSize();
        }

        private void markSkipped() {
            cancelAmountProbe();
            this.skipped = true;
            this.guiRequest = null;
            this.request = null;
        }

        private void markDone(long now) {
            this.done = true;
            this.doneTick = now;
            this.guiRequest = null;
        }

        private void markCancelled(long now) {
            this.cancelled = true;
            this.guiRequest = null;
        }

        private void cancelLink() {
            if (link != null && !link.isDone() && !link.isCanceled()) {
                link.cancel();
            }
        }

        private boolean shouldReserveCandidate() {
            return !skipped && !cancelled && (canOfferGui() || activeCraftGuiEntry == this || activeCraftAmountEntry == this
                    || amountProbe != null || submitted || directRemaining > 0 || craftedRemaining > 0);
        }

        private boolean matches(ItemStack required) {
            return !item.isEmpty() && AdvancedBuilderUtils.areItemStacksEqual(item, required);
        }

        private boolean matches(FluidStack required) {
            return fluid != null && AdvancedBuilderUtils.areFluidsEqual(fluid, required);
        }

        private boolean matches(BlockPos relativePos, ItemStack required) {
            return positions.contains(relativePos) && matches(required);
        }

        private boolean matches(BlockPos relativePos, FluidStack required) {
            return positions.contains(relativePos) && matches(required);
        }

        private CraftReservation reserveCrafted(long amount) {
            if (!isCraftOutputReady() || amount <= 0 || craftedRemaining < amount) {
                return null;
            }
            craftedRemaining -= amount;
            return new CraftReservation(0, amount);
        }

        private CraftReservation reserveDirect(long amount) {
            if (amount <= 0 || directRemaining < amount) {
                return null;
            }
            directRemaining -= amount;
            return new CraftReservation(amount, 0);
        }

        private void restoreAvailable(CraftReservation reservation) {
            if (reservation == null) {
                return;
            }
            directRemaining += reservation.directAmount;
            craftedRemaining += reservation.craftedAmount;
        }

        private boolean shouldWait(long now) {
            if (canOfferGui() || activeCraftGuiEntry == this || activeCraftAmountEntry == this || amountProbe != null) {
                return true;
            }
            if (submitted) {
                if (cancelled) {
                    return false;
                }
                if (link == null) {
                    if (guiRequest != null && isGuiRequesting()) {
                        return true;
                    }
                    return craftedRemaining > 0;
                }
                if (!done && !link.isDone() && !link.isCanceled()) {
                    return true;
                }
                if ((done || link.isDone()) && craftedRemaining > 0) {
                    return true;
                }
            }
            return false;
        }

        private boolean isCraftOutputReady() {
            return done || link != null && link.isDone();
        }

        private boolean shouldThrottle() {
            if (activeCraftGuiEntry == this || activeCraftAmountEntry == this || amountProbe != null) {
                return true;
            }
            return submitted && !cancelled && (!done || craftedRemaining > 0);
        }

        private boolean isRequestLost(long now) {
            if (guiRequest == null || now - submittedTick <= CRAFT_REQUEST_LOST_GRACE_TICKS) {
                return false;
            }
            try {
                return !guiRequest.isRequesting();
            } catch (Exception ignored) {
                return true;
            }
        }

        private void updateLinklessRequestState(long now) {
            if (!submitted || guiRequest == null) {
                return;
            }
            if (isGuiRequesting()) {
                return;
            }
            markDone(now);
        }

        private boolean isGuiRequesting() {
            try {
                return guiRequest != null && guiRequest.isRequesting();
            } catch (Exception ignored) {
                return false;
            }
        }

        private long insertCraftedOutput(IAEItemStack stack) {
            long amount = stack.getStackSize();
            long leftover;
            if (isFluid()) {
                FluidStack toInsert = fluid.copy();
                toInsert.amount = (int) Math.min(Integer.MAX_VALUE, amount);
                FluidStack remaining = Ae2AssemblyExtractor.insertCraftedFluid(getPlayer(), toInsert);
                leftover = remaining == null ? 0 : remaining.amount;
            } else {
                ItemStack toInsert = item.copy();
                toInsert.setCount((int) Math.min(Integer.MAX_VALUE, amount));
                ItemStack remaining = Ae2AssemblyExtractor.insertCraftedItem(getPlayer(), toInsert);
                leftover = remaining.isEmpty() ? 0 : remaining.getCount();
            }
            long inserted = amount - leftover;
            outputReceived += inserted;
            return leftover;
        }
    }

    private static final class CraftReservation {
        private final long directAmount;
        private final long craftedAmount;

        private CraftReservation(long directAmount, long craftedAmount) {
            this.directAmount = directAmount;
            this.craftedAmount = craftedAmount;
        }
    }
}
