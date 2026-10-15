package com.lw.mmce_advanced_builder_tool.common.variable;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerGhostIngredientSlot;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One virtual cell that accepts a block dragged out of the recipe viewer, or the block the player is
 * carrying.
 *
 * <p>Deliberately not a {@code PhantomItemSlot}: that widget demands a {@code PhantomItemSlotSH} and a
 * slot of its own, while this cell only ever holds a client side choice. The recipe viewer integration
 * itself only needs {@link RecipeViewerGhostIngredientSlot}, and ModularUI turns any widget
 * implementing it into a drop target once the widget is registered through
 * {@code RecipeViewerSettings#addGhostIngredientSlot}. Registering here in {@link #onInit()} instead of
 * from a screen keeps the cell usable in the builder tool panel, which is an ordinary synced GUI.
 *
 * <p>The picked block leaves as an alias spec ({@code mod:block@meta}), which is what the builder
 * stores; an empty spec reports that the cell was cleared, so the tool falls back to the pattern's own
 * blocks. Left clicking takes the block the player is carrying and right clicking clears the cell.
 */
public final class GhostBlockSlot extends ButtonWidget<GhostBlockSlot> implements RecipeViewerGhostIngredientSlot<ItemStack> {

    private final int iconSize;
    private final Consumer<String> onSpecChosen;
    private Supplier<String> storedSpec;
    private ItemStack stored = ItemStack.EMPTY;
    private boolean registrationAttempted;

    public GhostBlockSlot(int width, int height, Consumer<String> onSpecChosen) {
        this.iconSize = Math.max(1, height - 2);
        this.onSpecChosen = onSpecChosen;
        size(width, height);
        onMouseTapped(mouseButton -> {
            if (mouseButton == 1) {
                clear();
                return true;
            }
            return mouseButton == 0 && takeOfferedBlock();
        });
    }

    /**
     * Shows the block a spec that is already stored on the tool refers to, so reopening the GUI still
     * tells the player what the cell currently stands for.
     *
     * <p>The supplier is read in {@link #onInit()}, never while the panel is being built: only the
     * client builds an item icon, and only the client reads the live tool stack.
     */
    public GhostBlockSlot showStored(Supplier<String> specSupplier) {
        this.storedSpec = specSupplier;
        return this;
    }

    @Override
    public void onInit() {
        registerRecipeViewerTarget();
        if (storedSpec != null) {
            VariableBlockSpec parsed = VariableBlockSpec.parse(storedSpec.get());
            if (parsed != null) {
                showPreview(parsed.displayStack());
            }
        }
    }

    private void registerRecipeViewerTarget() {
        if (registrationAttempted) {
            return;
        }
        registrationAttempted = true;
        try {
            getContext().getRecipeViewerSettings().addGhostIngredientSlot(this);
        } catch (RuntimeException e) {
        }
    }

    private boolean takeOfferedBlock() {
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

    private void clear() {
        stored = ItemStack.EMPTY;
        overlay();
        report("");
    }

    @Override
    public void setGhostIngredient(ItemStack ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }
        showPreview(ingredient);
        report(blockSpecOf(stored));
    }

    @Override
    public ItemStack castGhostIngredientIfValid(Object ingredient) {
        if (!isEnabled() || !(ingredient instanceof ItemStack)) {
            return null;
        }
        ItemStack stack = (ItemStack) ingredient;
        return blockSpecOf(stack) == null ? null : stack;
    }

    /**
     * Icons ignore the size they are handed and draw from their own origin, so the block has to be
     * centred here rather than through {@code overlay()} alone.
     */
    @Override
    public void drawOverlay(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        IDrawable overlay = getCurrentOverlay(widgetTheme);
        if (overlay == null) {
            return;
        }
        overlay.draw(context, (getArea().width - iconSize) / 2, (getArea().height - iconSize) / 2,
                iconSize, iconSize, getActiveWidgetTheme(widgetTheme, isHovering()));
    }

    private void showPreview(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stored = stack.copy();
        stored.setCount(1);
        overlay(new ItemDrawable(stored).asIcon().size(iconSize, iconSize));
    }

    private void report(String spec) {
        if (onSpecChosen != null) {
            onSpecChosen.accept(spec == null ? "" : spec);
        }
    }

    private static String blockSpecOf(ItemStack stack) {
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
