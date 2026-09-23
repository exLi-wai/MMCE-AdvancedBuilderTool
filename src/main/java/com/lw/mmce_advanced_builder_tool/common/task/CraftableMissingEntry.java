package com.lw.mmce_advanced_builder_tool.common.task;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.storage.data.IAEItemStack;
import com.lw.mmce_advanced_builder_tool.common.ae2.Ae2GridAccess;
import com.lw.mmce_advanced_builder_tool.common.util.StructureIngredients;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks one material the machine needs but the player/network cannot supply yet, together with its
 * AE crafting lifecycle: amount probing, GUI offer, submission, completion and cancellation.
 *
 * <p>The entry owns the reservation bookkeeping ({@link CraftReservation}) that stops the assembly
 * from consuming the same shortfall twice. It stays tied to its owning assembly because the "active
 * GUI / amount probe" slots live there: only one entry may own the crafting GUI at a time, and an
 * entry is allowed to reserve a candidate only while it is that active entry.
 *
 * <p>Fields are package-private on purpose. They are part of the state machine that
 * {@link MachineAssemblyTask} drives, and exposing them keeps the owner free of a dozen
 * one-line accessors.
 */
final class CraftableMissingEntry {

    private final MachineAssemblyTask owner;
    final ItemStack item;
    final FluidStack fluid;
    final IAEItemStack baseRequest;
    final List<BlockPos> positions;
    final long shortageAmount;
    IAEItemStack request;
    long directRemaining;
    long craftedRemaining;
    long outputReceived;
    ICraftingLink link;
    IGridNode node;
    Ae2GridAccess.CraftingGuiRequest guiRequest;
    Ae2GridAccess.CraftingAmountProbe amountProbe;
    long probedAmount;
    boolean offered;
    boolean submitted;
    boolean skipped;
    boolean done;
    boolean cancelled;
    long offeredTick;
    long submittedTick;
    long doneTick;

    CraftableMissingEntry(MachineAssemblyTask owner, ItemStack item, FluidStack fluid, IAEItemStack baseRequest,
                          long shortageAmount, long directAmount, List<BlockPos> positions) {
        this.owner = owner;
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

    boolean isFluid() {
        return fluid != null;
    }

    boolean canOfferGui() {
        return !offered && !submitted && !skipped && !done && !cancelled && shortageAmount > 0;
    }

    void prepareRequest(long amount) {
        request = baseRequest.copy();
        request.setStackSize(amount);
    }

    void startAmountProbe() {
        amountProbe = Ae2GridAccess.startCraftingAmountProbe(owner.getPlayer(), baseRequest, shortageAmount);
        probedAmount = 0;
    }

    boolean tickAmountProbe() {
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

    long getProbedAmount() {
        return probedAmount;
    }

    void cancelAmountProbe() {
        if (amountProbe != null) {
            amountProbe.cancel();
            amountProbe = null;
        }
    }

    void markOffered(Ae2GridAccess.CraftingGuiRequest request, long now) {
        this.guiRequest = request;
        this.node = request.getNode();
        this.offered = true;
        this.offeredTick = now;
    }

    void markSubmitted(ICraftingLink link, Ae2GridAccess.CraftingGuiRequest request, long now) {
        this.link = link;
        this.guiRequest = request;
        this.node = request == null ? null : request.getNode();
        this.submitted = true;
        this.submittedTick = now;
        this.craftedRemaining = this.request == null ? 0 : this.request.getStackSize();
    }

    void markSkipped() {
        cancelAmountProbe();
        this.skipped = true;
        this.guiRequest = null;
        this.request = null;
    }

    void markDone(long now) {
        this.done = true;
        this.doneTick = now;
        this.guiRequest = null;
    }

    void markCancelled(long now) {
        this.cancelled = true;
        this.guiRequest = null;
    }

    void cancelLink() {
        if (link != null && !link.isDone() && !link.isCanceled()) {
            link.cancel();
        }
    }

    boolean shouldReserveCandidate() {
        return !skipped && !cancelled && (canOfferGui() || owner.isActiveCraftEntry(this)
                || amountProbe != null || submitted || directRemaining > 0 || craftedRemaining > 0);
    }

    boolean matches(ItemStack required) {
        return !item.isEmpty() && StructureIngredients.areItemStacksEqual(item, required);
    }

    boolean matches(FluidStack required) {
        return fluid != null && StructureIngredients.areFluidsEqual(fluid, required);
    }

    boolean matches(BlockPos relativePos, ItemStack required) {
        return positions.contains(relativePos) && matches(required);
    }

    boolean matches(BlockPos relativePos, FluidStack required) {
        return positions.contains(relativePos) && matches(required);
    }

    CraftReservation reserveCrafted(long amount) {
        if (!isCraftOutputReady() || amount <= 0 || craftedRemaining < amount) {
            return null;
        }
        craftedRemaining -= amount;
        return new CraftReservation(0, amount);
    }

    CraftReservation reserveDirect(long amount) {
        if (amount <= 0 || directRemaining < amount) {
            return null;
        }
        directRemaining -= amount;
        return new CraftReservation(amount, 0);
    }

    void restoreAvailable(CraftReservation reservation) {
        if (reservation == null) {
            return;
        }
        directRemaining += reservation.directAmount;
        craftedRemaining += reservation.craftedAmount;
    }

    boolean shouldWait(long now) {
        if (canOfferGui() || owner.isActiveCraftEntry(this) || amountProbe != null) {
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

    boolean isCraftOutputReady() {
        return done || link != null && link.isDone();
    }

    boolean shouldThrottle() {
        if (owner.isActiveCraftEntry(this) || amountProbe != null) {
            return true;
        }
        return submitted && !cancelled && (!done || craftedRemaining > 0);
    }

    boolean isRequestLost(long now) {
        if (guiRequest == null || now - submittedTick <= MachineAssemblyTask.craftRequestLostGraceTicks()) {
            return false;
        }
        try {
            return !guiRequest.isRequesting();
        } catch (Exception ignored) {
            return true;
        }
    }

    void updateLinklessRequestState(long now) {
        if (!submitted || guiRequest == null) {
            return;
        }
        if (isGuiRequesting()) {
            return;
        }
        markDone(now);
    }

    boolean isGuiRequesting() {
        try {
            return guiRequest != null && guiRequest.isRequesting();
        } catch (Exception ignored) {
            return false;
        }
    }

    long insertCraftedOutput(IAEItemStack stack) {
        long amount = stack.getStackSize();
        long leftover;
        if (isFluid()) {
            FluidStack toInsert = fluid.copy();
            toInsert.amount = (int) Math.min(Integer.MAX_VALUE, amount);
            FluidStack remaining = Ae2GridAccess.insertCraftedFluid(owner.getPlayer(), toInsert);
            leftover = remaining == null ? 0 : remaining.amount;
        } else {
            ItemStack toInsert = item.copy();
            toInsert.setCount((int) Math.min(Integer.MAX_VALUE, amount));
            ItemStack remaining = Ae2GridAccess.insertCraftedItem(owner.getPlayer(), toInsert);
            leftover = remaining.isEmpty() ? 0 : remaining.getCount();
        }
        long inserted = amount - leftover;
        outputReceived += inserted;
        return leftover;
    }

    /**
     * A reservation against the amounts an entry may still consume. Direct amounts come from the
     * player inventory / AE storage, crafted amounts from an accepted crafting job.
     */
    static final class CraftReservation {
        final long directAmount;
        final long craftedAmount;

        CraftReservation(long directAmount, long craftedAmount) {
            this.directAmount = directAmount;
            this.craftedAmount = craftedAmount;
        }
    }
}
