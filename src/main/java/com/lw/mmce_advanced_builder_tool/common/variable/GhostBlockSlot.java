package com.lw.mmce_advanced_builder_tool.common.variable;

import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerGhostIngredientSlot;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.function.Consumer;

/**
 * One virtual cell that accepts a block dragged out of the recipe viewer, or the block the player is
 * carrying.
 *
 * <p>Deliberately not a {@code PhantomItemSlot}: that widget demands a {@code PhantomItemSlotSH}, which
 * reaches for a {@code PanelSyncManager} that this client only screen does not own. The recipe viewer
 * integration itself only needs {@link RecipeViewerGhostIngredientSlot}, and ModularUI turns any widget
 * implementing it into a drop target once the widget is registered through
 * {@code RecipeViewerSettings#addGhostIngredientSlot} (see {@link #registerRecipeViewerTarget()}).
 *
 * <p>The dropped block is remembered as the player's choice and reported once through
 * {@code onBlockChosen}; nothing is ever placed into a real inventory. Left clicking takes the block
 * the player is carrying, right clicking clears the cell.
 */
final class GhostBlockSlot extends ButtonWidget<GhostBlockSlot> implements RecipeViewerGhostIngredientSlot<ItemStack> {

    private final int cellSize;
    private final Consumer<ItemStack> onBlockChosen;
    private ItemStack stored = ItemStack.EMPTY;

    GhostBlockSlot(int cellSize, Consumer<ItemStack> onBlockChosen) {
        this.cellSize = cellSize;
        this.onBlockChosen = onBlockChosen;
        size(cellSize, cellSize);
        onMouseTapped(mouseButton -> {
            if (mouseButton == 1) {
                clear();
                return true;
            }
            return mouseButton == 0 && takeOfferedBlock();
        });
    }

    void showPreview(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stored = stack.copy();
        stored.setCount(1);
        overlay(new ItemDrawable(stored).asIcon().size(cellSize, cellSize));
    }

    private boolean registrationAttempted;

    void registerRecipeViewerTarget() {
        if (registrationAttempted) {
            return;
        }
        registrationAttempted = true;
        try {
            getContext().getRecipeViewerSettings().addGhostIngredientSlot(this);
        } catch (RuntimeException e) {
            MMCEAdvancedBuilderTool.LOGGER.warn("Block alias picker: the block cell could not be offered to the "
                    + "recipe viewer, so dragging a block into it will not work", e);
        }
    }

    boolean takeOfferedBlock() {
        ItemStack offered = offeredStack();
        if (offered.isEmpty()) {
            return false;
        }
        setGhostIngredient(offered);
        return true;
    }

    private static ItemStack offeredStack() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack carried = player.inventory.getItemStack();
        if (blockSpecOf(carried) != null) {
            return carried;
        }
        ItemStack mainHand = player.getHeldItemMainhand();
        if (blockSpecOf(mainHand) != null) {
            return mainHand;
        }
        ItemStack offHand = player.getHeldItemOffhand();
        return blockSpecOf(offHand) == null ? ItemStack.EMPTY : offHand;
    }

    void clear() {
        stored = ItemStack.EMPTY;
        overlay();
        if (onBlockChosen != null) {
            onBlockChosen.accept(ItemStack.EMPTY);
        }
    }

    @Override
    public void setGhostIngredient(ItemStack ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }
        showPreview(ingredient);
        if (onBlockChosen != null) {
            onBlockChosen.accept(stored);
        }
    }

    @Override
    public ItemStack castGhostIngredientIfValid(Object ingredient) {
        if (!isEnabled() || !(ingredient instanceof ItemStack)) {
            return null;
        }
        ItemStack stack = (ItemStack) ingredient;
        return blockSpecOf(stack) == null ? null : stack;
    }

    static String blockSpecOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) {
            return null;
        }
        Item item = stack.getItem();
        ResourceLocation name = item.getRegistryName();
        if (name == null) {
            return null;
        }
        int meta = stack.getMetadata();
        return meta == 0 ? name.toString() : name + "@" + meta;
    }
}
