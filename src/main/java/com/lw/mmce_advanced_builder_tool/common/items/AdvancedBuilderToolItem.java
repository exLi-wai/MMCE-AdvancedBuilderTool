package com.lw.mmce_advanced_builder_tool.common.items;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.GuiFactories;
import com.cleanroommc.modularui.factory.PlayerInventoryGuiData;
import com.cleanroommc.modularui.factory.inventory.InventoryTypes;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.SyncHandlers;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import com.lw.mmce_advanced_builder_tool.Tags;
import com.lw.mmce_advanced_builder_tool.common.integration.mmce.AdvancedBuilderConfig;
import com.lw.mmce_advanced_builder_tool.common.integration.mmce.AdvancedBuilderService;
import com.lw.mmce_advanced_builder_tool.common.integration.mmce.AdvancedBuilderTaskManager;
import com.lw.mmce_advanced_builder_tool.common.network.AdvancedBuilderNetwork;
import com.lw.mmce_advanced_builder_tool.common.network.PacketBuilderConfig;
import com.lw.mmce_advanced_builder_tool.common.util.AdvancedBuilderUtils;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

public class AdvancedBuilderToolItem extends Item implements IGuiHolder<GuiData> {

    public static final String REGISTRY_NAME = "advanced_builder_tool";

    public AdvancedBuilderToolItem() {
        setTranslationKey(Tags.MOD_ID + "." + REGISTRY_NAME);
        setRegistryName(REGISTRY_NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
    }

    @Override
    public @NotNull ActionResult<ItemStack> onItemRightClick(World world, @NotNull EntityPlayer player, @NotNull EnumHand hand) {
        if (!world.isRemote && player.isSneaking()) {
            AdvancedBuilderTaskManager.cancelPlayerTask(player);
        }
        if (world.isRemote && !player.isSneaking()) {
            GuiFactories.playerInventory().openFromHandClient(hand);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }

    @Override
    public @NotNull EnumActionResult onItemUse(@NotNull EntityPlayer player, @NotNull World world, BlockPos pos, @NotNull EnumHand hand, @NotNull EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking()) {
            return EnumActionResult.PASS;
        }
        if (!world.isRemote && player instanceof EntityPlayerMP) {
            if (AdvancedBuilderTaskManager.cancelPlayerTask(player)) {
                return EnumActionResult.SUCCESS;
            }
            ItemStack stack = player.getHeldItem(hand);
            try {
                AdvancedBuilderService.start((EntityPlayerMP) player, pos, AdvancedBuilderConfig.useAeItems(stack),
                        AdvancedBuilderConfig.useAeFluids(stack), AdvancedBuilderConfig.craftMissing(stack), AdvancedBuilderConfig.disassembleMode(stack),
                        AdvancedBuilderConfig.dynamicLength(stack), AdvancedBuilderConfig.attachmentModule(stack),
                        AdvancedBuilderConfig.TICK_INTERVAL, AdvancedBuilderConfig.OPERATIONS_PER_TICK);
            } catch (RuntimeException | LinkageError error) {
                MMCEAdvancedBuilderTool.LOGGER.error("Failed to run the MMCE structure builder", error);
                AdvancedBuilderUtils.sendTranslation(player, "message.mmce_advanced_builder_tool.failed");
            }
        }
        return EnumActionResult.SUCCESS;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public ModularScreen createScreen(GuiData data, ModularPanel mainPanel) {
        return new ModularScreen(Tags.MOD_ID, mainPanel);
    }

    @Override
    public ModularPanel buildUI(GuiData data, PanelSyncManager syncManager, UISettings settings) {
        if (!(data instanceof PlayerInventoryGuiData)) {
            return ModularPanel.defaultPanel("mmce_advanced_builder_tool", 176, 162);
        }
        PlayerInventoryGuiData inventoryData = (PlayerInventoryGuiData) data;
        if (inventoryData.getInventoryType() == InventoryTypes.PLAYER) {
            syncManager.bindPlayerInventory(inventoryData.getPlayer(), (inv, index) -> index == inventoryData.getSlotIndex()
                    ? new ModularSlot(inv, index).accessibility(false, false)
                    : new ModularSlot(inv, index));
        }

        ModularPanel panel = ModularPanel.defaultPanel("mmce_advanced_builder_tool", 176, 162);
        panel.child(Flow.column().margin(7).widthRel(1f).heightRel(1f)
                .child(new TextWidget<>(IKey.lang("gui.mmce_advanced_builder_tool.title")).height(12).widthRel(1f))
                .child(row("gui.mmce_advanced_builder_tool.disassemble_mode", new ToggleButton()
                        .value(SyncHandlers.bool(() -> AdvancedBuilderConfig.disassembleMode(currentStack(inventoryData)), val -> {
                            ItemStack stack = currentStack(inventoryData);
                            AdvancedBuilderConfig.setDisassembleMode(stack, val);
                            syncConfigToServer(inventoryData, stack);
                        }))
                        .size(18, 18)
                        .overlay(false, IKey.lang("gui.mmce_advanced_builder_tool.off"))
                        .overlay(true, IKey.lang("gui.mmce_advanced_builder_tool.on"))))
                .child(row("gui.mmce_advanced_builder_tool.use_ae_items", new ToggleButton()
                        .value(SyncHandlers.bool(() -> AdvancedBuilderConfig.useAeItems(currentStack(inventoryData)), val -> {
                            ItemStack stack = currentStack(inventoryData);
                            AdvancedBuilderConfig.setUseAeItems(stack, val);
                            syncConfigToServer(inventoryData, stack);
                        }))
                        .size(18, 18)
                        .overlay(false, IKey.lang("gui.mmce_advanced_builder_tool.off"))
                        .overlay(true, IKey.lang("gui.mmce_advanced_builder_tool.on"))))
                .child(row("gui.mmce_advanced_builder_tool.use_ae_fluids", new ToggleButton()
                        .value(SyncHandlers.bool(() -> AdvancedBuilderConfig.useAeFluids(currentStack(inventoryData)), val -> {
                            ItemStack stack = currentStack(inventoryData);
                            AdvancedBuilderConfig.setUseAeFluids(stack, val);
                            syncConfigToServer(inventoryData, stack);
                        }))
                        .size(18, 18)
                        .overlay(false, IKey.lang("gui.mmce_advanced_builder_tool.off"))
                        .overlay(true, IKey.lang("gui.mmce_advanced_builder_tool.on"))))
                .child(row("gui.mmce_advanced_builder_tool.craft_missing", new ToggleButton()
                        .value(SyncHandlers.bool(() -> AdvancedBuilderConfig.craftMissing(currentStack(inventoryData)), val -> {
                            ItemStack stack = currentStack(inventoryData);
                            AdvancedBuilderConfig.setCraftMissing(stack, val);
                            syncConfigToServer(inventoryData, stack);
                        }))
                        .size(18, 18)
                        .overlay(false, IKey.lang("gui.mmce_advanced_builder_tool.off"))
                        .overlay(true, IKey.lang("gui.mmce_advanced_builder_tool.on"))))
                .child(row("gui.mmce_advanced_builder_tool.dynamic_length", new TextFieldWidget()
                        .value(SyncHandlers.string(() -> String.valueOf(AdvancedBuilderConfig.dynamicLength(currentStack(inventoryData))), val -> {
                            ItemStack stack = currentStack(inventoryData);
                            try {
                                AdvancedBuilderConfig.setDynamicLength(stack, Integer.parseInt(val));
                            } catch (NumberFormatException ignored) {
                                AdvancedBuilderConfig.setDynamicLength(stack, 1);
                            }
                            syncConfigToServer(inventoryData, stack);
                        }))
                        .setNumbers(0, 4096)
                        .background(GuiTextures.DISPLAY_SMALL)
                        .width(50).height(18)))
                .childIf(Mods.MMCE_COMPLEMENT.isLoading(),
                        () -> row("gui.mmce_advanced_builder_tool.attachment_module", new TextFieldWidget()
                                .value(SyncHandlers.string(() -> AdvancedBuilderConfig.attachmentModule(currentStack(inventoryData)), val -> {
                                    ItemStack stack = currentStack(inventoryData);
                                    AdvancedBuilderConfig.setAttachmentModule(stack, val);
                                    syncConfigToServer(inventoryData, stack);
                                }))
                                .background(GuiTextures.DISPLAY_SMALL)
                                .width(50).height(18))));
        return panel;
    }

    /**
     * Resolves the tool from the slot the GUI was opened on, instead of caching the stack captured
     * when the panel was built. The server rejects config packets whose slot no longer holds a
     * builder, so reading the live slot keeps both sides on the same authoritative NBT: a moved or
     * dropped tool makes the controls fall back to defaults rather than writing to a stale copy.
     */
    @SideOnly(Side.CLIENT)
    private static ItemStack currentStack(PlayerInventoryGuiData data) {
        EntityPlayer player = data.getPlayer();
        int slot = data.getSlotIndex();
        if (player == null || slot < 0 || slot >= player.inventory.mainInventory.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = player.inventory.mainInventory.get(slot);
        return stack.getItem() instanceof AdvancedBuilderToolItem ? stack : ItemStack.EMPTY;
    }

    private Widget<?> row(String labelKey, Widget<?> control) {
        return Flow.row().widthRel(1f).height(22)
                .child(new TextWidget<>(IKey.lang(labelKey)).width(112).height(18).alignment(Alignment.CenterLeft))
                .child(control);
    }

    private void syncConfigToServer(PlayerInventoryGuiData data, ItemStack stack) {
        if (!data.getPlayer().world.isRemote) {
            return;
        }
        AdvancedBuilderNetwork.CHANNEL.sendToServer(new PacketBuilderConfig(data.getSlotIndex(),
                AdvancedBuilderConfig.useAeItems(stack),
                AdvancedBuilderConfig.useAeFluids(stack),
                AdvancedBuilderConfig.craftMissing(stack),
                AdvancedBuilderConfig.disassembleMode(stack),
                AdvancedBuilderConfig.dynamicLength(stack),
                AdvancedBuilderConfig.attachmentModule(stack)));
    }

    @Override
    public void addInformation(@NotNull ItemStack stack, @Nullable World worldIn, List<String> tooltip, @NotNull ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.mmce_advanced_builder_tool.1"));
        tooltip.add(I18n.translateToLocalFormatted("tooltip.mmce_advanced_builder_tool.2", AdvancedBuilderConfig.useAeItems(stack) ? "True" : "False"));
        tooltip.add(I18n.translateToLocalFormatted("tooltip.mmce_advanced_builder_tool.3", AdvancedBuilderConfig.useAeFluids(stack) ? "True" : "False"));
        tooltip.add(I18n.translateToLocalFormatted("tooltip.mmce_advanced_builder_tool.4", AdvancedBuilderConfig.dynamicLength(stack)));
        tooltip.add(I18n.translateToLocalFormatted("tooltip.mmce_advanced_builder_tool.5", AdvancedBuilderConfig.disassembleMode(stack) ? "True" : "False"));
        if (Mods.MMCE_COMPLEMENT.isLoading() && !AdvancedBuilderConfig.attachmentModule(stack).isEmpty()) {
            tooltip.add(I18n.translateToLocalFormatted("tooltip.mmce_advanced_builder_tool.7",
                    AdvancedBuilderConfig.attachmentModule(stack)));
        }
        tooltip.add(I18n.translateToLocal("tooltip.mmce_advanced_builder_tool.6"));
    }
}
