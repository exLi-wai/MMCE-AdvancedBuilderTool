package com.lw.mmce_advanced_builder_tool.common.ae2;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.implementations.ContainerCraftConfirm;
import appeng.core.sync.GuiBridge;
import appeng.fluids.util.AEFluidStack;
import appeng.helpers.WirelessTerminalGuiObject;
import appeng.me.helpers.PlayerSource;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import baubles.api.BaublesApi;
import baubles.api.cap.IBaublesItemHandler;
import com.glodblock.github.common.item.fake.FakeFluids;
import com.lw.mmce_advanced_builder_tool.common.task.CraftingConfirmBridge;
import com.lw.mmce_advanced_builder_tool.common.task.CraftingRequester;
import com.lw.mmce_advanced_builder_tool.common.util.MessageKeys;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import com.lw.mmce_advanced_builder_tool.common.util.StructureIngredients;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only place that talks to Applied Energistics. Everything the builder needs from AE goes
 * through here: locating usable wireless terminals, checking grid permissions, extracting and
 * inserting items/fluids, reading stored amounts, probing whether a craft is possible, and driving
 * the crafting-confirm GUI.
 *
 * <p>Access model: a request is offered to one accessible terminal at a time and the first terminal
 * that can serve it wins; terminals that are out of range, inactive, belong to a network the player
 * has no permission on, or lack the storage/crafting cache are skipped silently. Nothing here sends
 * chat messages directly - diagnostics are throttled through {@link #sendDiagnostic} so a stalled
 * build cannot spam the player.
 *
 * <p>Two entry points exist per operation:
 * <ul>
 *   <li>{@code *Silently} / {@code *Crafted*} - used by the per-block build loop, where a failure is
 *       expected and is reported later as a missing-material summary;</li>
 *   <li>the same call without the suffix - identical work, kept as the descriptive form at call
 *       sites that are not part of a tight loop.</li>
 * </ul>
 */
public final class Ae2GridAccess {

    private static final int DIAGNOSTIC_INTERVAL_TICKS = 100;
    private static final int CRAFTING_JOB_TIMEOUT_MILLIS = 50;

    private Ae2GridAccess() {
    }

    public static boolean extractCraftedItem(EntityPlayer player, ItemStack required) {
        return extractItem(player, required);
    }

    public static boolean extractItemSilently(EntityPlayer player, ItemStack required) {
        return extractItem(player, required);
    }

    private static boolean extractItem(EntityPlayer player, ItemStack required) {
        if (required.isEmpty()) {
            return true;
        }
        IAEItemStack request = AEItemStack.fromItemStack(required);
        if (request == null) {
            return false;
        }
        request.setStackSize(required.getCount());
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        if (terminals.isEmpty()) {
            return false;
        }
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.EXTRACT)) {
                continue;
            }
            IGrid grid = node.getGrid();
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid == null) {
                continue;
            }
            IMEMonitor<IAEItemStack> monitor = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
            IAEItemStack simulated = Platform.poweredExtraction(terminal.guiObject, monitor, request.copy(), new PlayerSource(player, terminal.guiObject), Actionable.SIMULATE);
            if (simulated == null || simulated.getStackSize() < required.getCount()) {
                continue;
            }
            IAEItemStack extracted = Platform.poweredExtraction(terminal.guiObject, monitor, request.copy(), new PlayerSource(player, terminal.guiObject), Actionable.MODULATE);
            if (extracted != null && extracted.getStackSize() >= required.getCount()) {
                terminal.guiObject.saveChanges();
                return true;
            }
            if (extracted != null && extracted.getStackSize() > 0) {
                IAEItemStack leftover = Platform.poweredInsert(terminal.guiObject, monitor, extracted.copy(), new PlayerSource(player, terminal.guiObject), Actionable.MODULATE);
                if (leftover != null && leftover.getStackSize() > 0) {
                    StructureIngredients.giveOrDrop(player, leftover.createItemStack());
                }
                terminal.guiObject.saveChanges();
            }
        }
        return false;
    }

    public static boolean extractCraftedFluid(EntityPlayer player, FluidStack required) {
        return extractFluid(player, required);
    }

    public static boolean extractFluidSilently(EntityPlayer player, FluidStack required) {
        return extractFluid(player, required);
    }

    private static boolean extractFluid(EntityPlayer player, FluidStack required) {
        if (required == null || required.amount <= 0) {
            return true;
        }
        IAEFluidStack request = AEFluidStack.fromFluidStack(required.copy());
        if (request == null) {
            return false;
        }
        request.setStackSize(required.amount);
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        if (terminals.isEmpty()) {
            return false;
        }
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.EXTRACT)) {
                continue;
            }
            IGrid grid = node.getGrid();
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid == null) {
                continue;
            }
            IMEMonitor<IAEFluidStack> monitor = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
            IAEFluidStack simulated = Platform.poweredExtraction(terminal.guiObject, monitor, request.copy(), new PlayerSource(player, terminal.guiObject), Actionable.SIMULATE);
            if (simulated == null || simulated.getStackSize() < required.amount) {
                continue;
            }
            IAEFluidStack extracted = Platform.poweredExtraction(terminal.guiObject, monitor, request.copy(), new PlayerSource(player, terminal.guiObject), Actionable.MODULATE);
            if (extracted != null && extracted.getStackSize() >= required.amount) {
                terminal.guiObject.saveChanges();
                return true;
            }
            if (extracted != null && extracted.getStackSize() > 0) {
                IAEFluidStack leftover = Platform.poweredInsert(terminal.guiObject, monitor, extracted.copy(), new PlayerSource(player, terminal.guiObject), Actionable.MODULATE);
                if (leftover != null && leftover.getStackSize() > 0) {
                    FluidStack unreturned = insertFluid(player, leftover.getFluidStack(), Actionable.MODULATE, false);
                    if (unreturned == null || unreturned.amount > 0) {
                        sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_refund_lost");
                    }
                }
                terminal.guiObject.saveChanges();
            }
        }
        return false;
    }

    public static boolean canInsertItem(EntityPlayer player, ItemStack stack) {
        return insertItem(player, stack, Actionable.SIMULATE, false).isEmpty();
    }

    public static ItemStack insertItem(EntityPlayer player, ItemStack stack) {
        return insertItem(player, stack, Actionable.MODULATE, true);
    }

    public static ItemStack insertCraftedItem(EntityPlayer player, ItemStack stack) {
        return insertItem(player, stack, Actionable.MODULATE, false);
    }

    public static boolean canInsertFluid(EntityPlayer player, FluidStack stack) {
        return insertFluid(player, stack, Actionable.SIMULATE, false) == null;
    }

    public static FluidStack insertFluid(EntityPlayer player, FluidStack stack) {
        return insertFluid(player, stack, Actionable.MODULATE, true);
    }

    public static FluidStack insertCraftedFluid(EntityPlayer player, FluidStack stack) {
        return insertFluid(player, stack, Actionable.MODULATE, false);
    }

    private static ItemStack insertItem(EntityPlayer player, ItemStack stack, Actionable mode, boolean diagnose) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IAEItemStack toInsert = AEItemStack.fromItemStack(stack.copy());
        if (toInsert == null) {
            return stack.copy();
        }
        toInsert.setStackSize(stack.getCount());
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        if (terminals.isEmpty()) {
            if (diagnose) {
                sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_no_terminal");
            }
            return stack.copy();
        }
        boolean inaccessibleNetwork = false;
        boolean storageMissing = false;
        IAEItemStack remaining = toInsert.copy();
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.INJECT)) {
                inaccessibleNetwork = true;
                continue;
            }
            IGrid grid = node.getGrid();
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid == null) {
                storageMissing = true;
                continue;
            }
            IMEMonitor<IAEItemStack> monitor = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
            remaining = Platform.poweredInsert(terminal.guiObject, monitor, remaining.copy(), new PlayerSource(player, terminal.guiObject), mode);
            if (remaining == null || remaining.getStackSize() <= 0) {
                if (mode == Actionable.MODULATE) {
                    terminal.guiObject.saveChanges();
                }
                return ItemStack.EMPTY;
            }
            if (mode == Actionable.MODULATE) {
                terminal.guiObject.saveChanges();
            }
        }
        if (diagnose) {
            reportInsertionFailure(player, inaccessibleNetwork, storageMissing);
        }
        return remaining == null ? ItemStack.EMPTY : remaining.createItemStack();
    }

    private static FluidStack insertFluid(EntityPlayer player, FluidStack stack, Actionable mode, boolean diagnose) {
        if (stack == null || stack.amount <= 0) {
            return null;
        }
        IAEFluidStack toInsert = AEFluidStack.fromFluidStack(stack.copy());
        if (toInsert == null) {
            return stack.copy();
        }
        toInsert.setStackSize(stack.amount);
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        if (terminals.isEmpty()) {
            if (diagnose) {
                sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_no_terminal");
            }
            return stack.copy();
        }
        boolean inaccessibleNetwork = false;
        boolean storageMissing = false;
        IAEFluidStack remaining = toInsert.copy();
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.INJECT)) {
                inaccessibleNetwork = true;
                continue;
            }
            IGrid grid = node.getGrid();
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid == null) {
                storageMissing = true;
                continue;
            }
            IMEMonitor<IAEFluidStack> monitor = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
            remaining = Platform.poweredInsert(terminal.guiObject, monitor, remaining.copy(), new PlayerSource(player, terminal.guiObject), mode);
            if (remaining == null || remaining.getStackSize() <= 0) {
                if (mode == Actionable.MODULATE) {
                    terminal.guiObject.saveChanges();
                }
                return null;
            }
            if (mode == Actionable.MODULATE) {
                terminal.guiObject.saveChanges();
            }
        }
        if (diagnose) {
            reportInsertionFailure(player, inaccessibleNetwork, storageMissing);
        }
        return remaining == null ? null : remaining.getFluidStack();
    }

    public static IAEItemStack toAeItemRequest(ItemStack required, long amount) {
        if (required.isEmpty() || amount <= 0) {
            return null;
        }
        IAEItemStack request = AEItemStack.fromItemStack(required);
        if (request == null) {
            return null;
        }
        request.setStackSize(amount);
        return request;
    }

    public static IAEItemStack toAeFluidRequest(FluidStack required, long amount) {
        if (required == null || required.amount <= 0 || amount <= 0) {
            return null;
        }
        IAEItemStack request = FakeFluids.packFluid2AEDrops(required.copy());
        if (request == null) {
            return null;
        }
        request.setStackSize(amount);
        return request;
    }

    public static long getStoredItemAmount(EntityPlayer player, ItemStack required) {
        if (required.isEmpty()) {
            return 0;
        }
        IAEItemStack request = AEItemStack.fromItemStack(required);
        if (request == null) {
            return 0;
        }
        request.setStackSize(1);
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.EXTRACT)) {
                continue;
            }
            IGrid grid = node.getGrid();
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid == null) {
                continue;
            }
            IMEMonitor<IAEItemStack> monitor = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
            IAEItemStack stored = monitor.getStorageList().findPrecise(request);
            return stored == null ? 0 : stored.getStackSize();
        }
        return 0;
    }

    public static long getStoredFluidAmount(EntityPlayer player, FluidStack required) {
        if (required == null || required.amount <= 0) {
            return 0;
        }
        IAEFluidStack request = AEFluidStack.fromFluidStack(required.copy());
        if (request == null) {
            return 0;
        }
        request.setStackSize(1);
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.EXTRACT)) {
                continue;
            }
            IGrid grid = node.getGrid();
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            if (storageGrid == null) {
                continue;
            }
            IMEMonitor<IAEFluidStack> monitor = storageGrid.getInventory(AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
            IAEFluidStack stored = monitor.getStorageList().findPrecise(request);
            return stored == null ? 0 : stored.getStackSize();
        }
        return 0;
    }

    public static boolean canCraftAeItem(EntityPlayer player, IAEItemStack request) {
        if (request == null || request.getStackSize() <= 0) {
            return false;
        }
        IAEItemStack single = request.copy();
        single.setStackSize(1);
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.CRAFT)) {
                continue;
            }
            IGrid grid = node.getGrid();
            ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
            if (craftingGrid != null && !craftingGrid.getCraftingFor(single, null, 0, player.world).isEmpty()) {
                return true;
            }
            if (craftingGrid == null) {
                continue;
            }
            Future<ICraftingJob> futureJob = null;
            try {
                PlayerSource source = new PlayerSource(player, terminal.guiObject);
                futureJob = craftingGrid.beginCraftingJob(player.world, grid, source, request.copy(), null);
                ICraftingJob job = futureJob.get(CRAFTING_JOB_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (job != null && !job.isSimulation()) {
                    return true;
                }
            } catch (TimeoutException e) {
                if (futureJob != null) {
                    futureJob.cancel(true);
                }
                return true;
            } catch (Exception e) {
                if (futureJob != null) {
                    futureJob.cancel(true);
                }
            }
        }
        return false;
    }

    public static CraftingAmountProbe startCraftingAmountProbe(EntityPlayer player, IAEItemStack request, long maxAmount) {
        return new CraftingAmountProbe(player, request, maxAmount);
    }

    public static CraftingGuiRequest openCraftConfirmGui(EntityPlayer player, IAEItemStack request, CraftingRequester requester) {
        if (request == null || request.getStackSize() <= 0) {
            return null;
        }
        List<WirelessTerminalAccess> terminals = findWirelessTerminals(player);
        if (terminals.isEmpty()) {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_no_terminal");
            return null;
        }
        boolean inaccessibleNetwork = false;
        boolean craftingMissing = false;
        boolean craftingUnavailable = false;
        for (WirelessTerminalAccess terminal : terminals) {
            IGridNode node = terminal.guiObject.getActionableNode();
            if (!isAccessible(player, node, SecurityPermissions.CRAFT)) {
                inaccessibleNetwork = true;
                continue;
            }
            IGrid grid = node.getGrid();
            ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
            if (craftingGrid == null) {
                craftingMissing = true;
                continue;
            }
            Future<ICraftingJob> futureJob = null;
            try {
                PlayerSource source = new PlayerSource(player, terminal.guiObject);
                futureJob = craftingGrid.beginCraftingJob(player.world, grid, source, request.copy(), null);
                Platform.openGUI(player, terminal.guiObject.getInventorySlot(), GuiBridge.GUI_CRAFTING_CONFIRM, terminal.guiObject.isBaubleSlot());
                Container openContainer = player.openContainer;
                if (openContainer instanceof ContainerCraftConfirm) {
                    ContainerCraftConfirm confirm = (ContainerCraftConfirm) openContainer;
                    confirm.setAutoStart(false);
                    confirm.setJob(futureJob);
                    if (requester != null && confirm instanceof CraftingConfirmBridge) {
                        ((CraftingConfirmBridge) confirm).abt$setRequester(requester);
                    }
                    terminal.guiObject.saveChanges();
                    sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_craft_requested");
                    return new CraftingGuiRequest(craftingGrid, node, request.copy());
                }
                if (futureJob != null) {
                    futureJob.cancel(true);
                }
                craftingUnavailable = true;
            } catch (Exception e) {
                if (futureJob != null) {
                    futureJob.cancel(true);
                }
                craftingUnavailable = true;
            }
        }
        reportCraftingFailure(player, inaccessibleNetwork, craftingMissing, craftingUnavailable);
        return null;
    }

    private static List<WirelessTerminalAccess> findWirelessTerminals(EntityPlayer player) {
        List<WirelessTerminalAccess> terminals = new ArrayList<>();
        for (int i = 0; i < player.inventory.mainInventory.size(); i++) {
            addWirelessTerminal(player, terminals, player.inventory.mainInventory.get(i), i, false);
        }
        for (int i = 0; i < player.inventory.armorInventory.size(); i++) {
            addWirelessTerminal(player, terminals, player.inventory.armorInventory.get(i), i, false);
        }
        for (int i = 0; i < player.inventory.offHandInventory.size(); i++) {
            addWirelessTerminal(player, terminals, player.inventory.offHandInventory.get(i), i, false);
        }
        if (Mods.BAUBLES.isLoading()) {
            IBaublesItemHandler baubles = BaublesApi.getBaublesHandler(player);
            if (baubles != null) {
                for (int i = 0; i < baubles.getSlots(); i++) {
                    addWirelessTerminal(player, terminals, baubles.getStackInSlot(i), i, true);
                }
            }
        }
        return terminals;
    }

    private static void addWirelessTerminal(EntityPlayer player, List<WirelessTerminalAccess> terminals, ItemStack stack, int slot, boolean baubleSlot) {
        if (stack.isEmpty() || !AEApi.instance().registries().wireless().isWirelessTerminal(stack)) {
            return;
        }
        IWirelessTermHandler handler = AEApi.instance().registries().wireless().getWirelessTerminalHandler(stack);
        if (handler == null || !handler.canHandle(stack) || !handler.hasPower(player, 1.0D, stack)) {
            return;
        }
        WirelessTerminalGuiObject guiObject = new WirelessTerminalGuiObject(handler, stack, player, player.world, slot, baubleSlot ? 1 : 0, 0);
        if (guiObject.rangeCheck()) {
            terminals.add(new WirelessTerminalAccess(guiObject));
        }
    }

    private static boolean isAccessible(EntityPlayer player, IGridNode node, SecurityPermissions permission) {
        if (node == null || !node.isActive()) {
            return false;
        }
        IGrid grid = node.getGrid();
        ISecurityGrid securityGrid = grid.getCache(ISecurityGrid.class);
        return securityGrid == null || securityGrid.hasPermission(player, permission);
    }

    private static void reportCraftingFailure(EntityPlayer player, boolean inaccessibleNetwork, boolean craftingMissing, boolean craftingUnavailable) {
        if (inaccessibleNetwork) {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_craft_inaccessible");
        } else if (craftingMissing) {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_no_crafting");
        } else if (craftingUnavailable) {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_uncraftable");
        } else {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_craft_failed");
        }
    }

    private static void reportInsertionFailure(EntityPlayer player, boolean inaccessibleNetwork, boolean storageMissing) {
        if (inaccessibleNetwork) {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_insert_inaccessible");
        } else if (storageMissing) {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_no_storage");
        } else {
            sendDiagnostic(player, "message.mmce_advanced_builder_tool.ae_insert_failed");
        }
    }

    private static void sendDiagnostic(EntityPlayer player, String key) {
        long now = player.world.getTotalWorldTime();
        if (now - player.getEntityData().getLong(MessageKeys.LAST_AE_DIAGNOSTIC_TAG) < DIAGNOSTIC_INTERVAL_TICKS) {
            return;
        }
        player.getEntityData().setLong(MessageKeys.LAST_AE_DIAGNOSTIC_TAG, now);
        StructureIngredients.sendTranslation(player, key);
    }

    private static final class WirelessTerminalAccess {
        private final WirelessTerminalGuiObject guiObject;

        private WirelessTerminalAccess(WirelessTerminalGuiObject guiObject) {
            this.guiObject = guiObject;
        }
    }

    public static final class CraftingGuiRequest {
        private final ICraftingGrid craftingGrid;
        private final IGridNode node;
        private final IAEItemStack request;

        private CraftingGuiRequest(ICraftingGrid craftingGrid, IGridNode node, IAEItemStack request) {
            this.craftingGrid = craftingGrid;
            this.node = node;
            this.request = request;
        }

        public IGridNode getNode() {
            return node;
        }

        public boolean isRequesting() {
            return craftingGrid.isRequesting(request) || craftingGrid.requesting(request) > 0;
        }
    }

    public static final class CraftingAmountProbe {
        private final EntityPlayer player;
        private final IAEItemStack request;
        private final long maxAmount;
        private long low;
        private long high;
        private long amount;
        private long resultAmount;
        private List<WirelessTerminalAccess> terminals;
        private int terminalIndex;
        private Future<ICraftingJob> futureJob;
        private boolean fullProbeComplete;
        private boolean done;

        private CraftingAmountProbe(EntityPlayer player, IAEItemStack request, long maxAmount) {
            this.player = player;
            this.request = request == null ? null : request.copy();
            this.maxAmount = maxAmount;
            this.high = maxAmount;
            if (request == null || maxAmount <= 0) {
                done = true;
            }
        }

        public boolean tick() {
            if (done) {
                return true;
            }
            Boolean probeResult = pollCurrentProbe();
            if (probeResult == null) {
                return false;
            }
            if (!fullProbeComplete) {
                fullProbeComplete = true;
                if (probeResult) {
                    resultAmount = maxAmount;
                    done = true;
                    return true;
                }
                high = maxAmount - 1;
            } else if (probeResult) {
                low = amount;
            } else {
                high = amount - 1;
            }
            if (low >= high) {
                resultAmount = low;
                done = true;
                return true;
            }
            return startAmountProbe(low + ((high - low + 1) / 2));
        }

        public long getAmount() {
            return done ? resultAmount : 0;
        }

        public void cancel() {
            if (futureJob != null && !futureJob.isDone()) {
                futureJob.cancel(true);
            }
            futureJob = null;
            terminals = null;
            done = true;
        }

        private Boolean pollCurrentProbe() {
            if (terminals == null) {
                startAmountProbe(maxAmount);
                return null;
            }
            while (true) {
                if (futureJob == null && !startNextTerminalProbe()) {
                    clearCurrentProbe();
                    return false;
                }
                if (!futureJob.isDone()) {
                    return null;
                }
                if (isSuccessfulProbe(futureJob)) {
                    clearCurrentProbe();
                    return true;
                }
                futureJob = null;
                terminalIndex++;
            }
        }

        private boolean startAmountProbe(long amount) {
            if (amount <= 0) {
                resultAmount = 0;
                done = true;
                return true;
            }
            this.amount = amount;
            this.terminals = findWirelessTerminals(player);
            this.terminalIndex = 0;
            this.futureJob = null;
            return false;
        }

        private boolean startNextTerminalProbe() {
            IAEItemStack probe = request.copy();
            probe.setStackSize(amount);
            while (terminalIndex < terminals.size()) {
                WirelessTerminalAccess terminal = terminals.get(terminalIndex);
                IGridNode node = terminal.guiObject.getActionableNode();
                if (!isAccessible(player, node, SecurityPermissions.CRAFT)) {
                    terminalIndex++;
                    continue;
                }
                IGrid grid = node.getGrid();
                ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
                if (craftingGrid == null) {
                    terminalIndex++;
                    continue;
                }
                try {
                    PlayerSource source = new PlayerSource(player, terminal.guiObject);
                    futureJob = craftingGrid.beginCraftingJob(player.world, grid, source, probe.copy(), null);
                    return true;
                } catch (Exception ignored) {
                    if (futureJob != null) {
                        futureJob.cancel(true);
                    }
                    futureJob = null;
                    terminalIndex++;
                }
            }
            return false;
        }

        private boolean isSuccessfulProbe(Future<ICraftingJob> future) {
            try {
                ICraftingJob job = future.get();
                return job != null && !job.isSimulation();
            } catch (Exception ignored) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
                return false;
            }
        }

        private void clearCurrentProbe() {
            futureJob = null;
            terminals = null;
            terminalIndex = 0;
        }
    }
}
