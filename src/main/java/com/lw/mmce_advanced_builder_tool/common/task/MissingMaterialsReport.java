package com.lw.mmce_advanced_builder_tool.common.task;

import com.lw.mmce_advanced_builder_tool.common.util.StructureIngredients;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the materials a build could not place and reports them to the player as one summary.
 *
 * <p>Entries are aggregated by type, so a machine needing 400 casings that are all missing is
 * reported once with the total instead of 400 times. {@link #markSkipped()} records that at least
 * one position was left unbuilt for a different reason; that keeps the summary meaningful even if
 * the build later reports itself as "completed" because every remaining position was consumed.
 */
final class MissingMaterialsReport {

    private final List<MissingItemEntry> missingItems = new ArrayList<>();
    private final List<MissingFluidEntry> missingFluids = new ArrayList<>();
    private boolean skipped;

    void markSkipped() {
        this.skipped = true;
    }

    boolean report(EntityPlayer player, boolean buildCompleted) {
        if ((buildCompleted && !skipped) || (missingItems.isEmpty() && missingFluids.isEmpty())) {
            return false;
        }
        StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.missing_summary_header");
        for (MissingItemEntry entry : missingItems) {
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.missing_item_entry",
                    entry.amount, entry.stack.getDisplayName());
        }
        for (MissingFluidEntry entry : missingFluids) {
            StructureIngredients.sendTranslation(player, "message.mmce_advanced_builder_tool.missing_fluid_entry",
                    entry.amount, entry.fluid.getLocalizedName());
        }
        return true;
    }

    void addItem(ItemStack required) {
        if (required.isEmpty()) {
            return;
        }
        addItem(required, required.getCount());
    }

    void addItem(ItemStack required, long amount) {
        if (required.isEmpty() || amount <= 0) {
            return;
        }
        for (MissingItemEntry entry : missingItems) {
            if (StructureIngredients.areItemStacksEqual(entry.stack, required)) {
                entry.amount += amount;
                return;
            }
        }
        missingItems.add(new MissingItemEntry(required, amount));
    }

    void addFluid(FluidStack required) {
        if (required == null) {
            return;
        }
        addFluid(required, required.amount);
    }

    void addFluid(FluidStack required, long amount) {
        if (required == null || required.amount <= 0 || amount <= 0) {
            return;
        }
        for (MissingFluidEntry entry : missingFluids) {
            if (StructureIngredients.areFluidsEqual(entry.fluid, required)) {
                entry.amount += amount;
                return;
            }
        }
        missingFluids.add(new MissingFluidEntry(required, amount));
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
}
