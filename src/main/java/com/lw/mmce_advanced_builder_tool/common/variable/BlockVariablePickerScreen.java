package com.lw.mmce_advanced_builder_tool.common.variable;

import com.cleanroommc.modularui.api.MCHelper;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.lw.mmce_advanced_builder_tool.common.variable.BlockVariables.ALL_VARIABLES;

/**
 * Picker for the {@code *.var.json} aliases a machine pattern can reference.
 *
 * <p>One screen, two states: the alias list, and the block list of the alias currently opened. Every
 * row is built once and switched on or off through {@code setEnabledIf}, which ModularUI honours for
 * both layout and input. Rebuilding the panel or swapping screens is deliberately avoided - replacing
 * the screen that is currently dispatching the click did not work reliably.
 *
 * <p>Client only, which comes from {@link CustomModularScreen} itself. This class must not repeat the
 * {@code @SideOnly} annotation: the parent already carries it on the class and on
 * {@link CustomModularScreen#buildUI}, and the duplication makes the override look inaccessible.
 *
 * <p>The json files are read on both sides, so the client can list the aliases without a round trip;
 * a click reports the choice through
 * {@link com.lw.mmce_advanced_builder_tool.common.network.VariableSelectionPacket}.
 */
public final class BlockVariablePickerScreen extends CustomModularScreen {

    private static final String OWNER = "mmce_advanced_builder_tool";
    /** Match the builder settings screen dimensions. */
    private static final int PANEL_WIDTH = 176;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 1;
    private static final int HEADER_HEIGHT = 14;
    private static final int ALIAS_ROWS = 5;
    private static final int CELL_SIZE = 18;
    // Eight 18px cells fit inside the 176px panel with the 6px side margins.
    private static final int GRID_COLUMNS = 8;
    private static final int GRID_ROWS = 6;
    private static final int LIST_MARGIN = 6;
    private static final int PANEL_HEIGHT = 190;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - LIST_MARGIN * 2;

    private final SelectionHandler handler;
    private final Consumer<Boolean> skipExistingHandler;
    private SelectionState state;
    private GhostBlockSlot allCell;
    private BlockVariables aliasCache;

    private BlockVariablePickerScreen(String initial, boolean skipExistingBlocks,
                                      SelectionHandler handler, Consumer<Boolean> skipExistingHandler) {
        super(OWNER);
        this.handler = handler;
        this.skipExistingHandler = skipExistingHandler;
        state().expanded = initial;
        state().skipExistingBlocks = skipExistingBlocks;
    }

    private SelectionState state() {
        if (state == null) {
            state = new SelectionState();
        }
        return state;
    }

    private BlockVariables aliases() {
        if (aliasCache == null) {
            aliasCache = BlockVariables.load();
        }
        return aliasCache;
    }

    public static void open(String initial, Map<String, String> stored, boolean skipExistingBlocks,
                            SelectionHandler handler, Consumer<Boolean> skipExistingHandler) {
        try {
            BlockVariablePickerScreen screen = new BlockVariablePickerScreen(initial, skipExistingBlocks,
                    handler, skipExistingHandler);
            screen.showStored(stored);
            UISettings settings = new UISettings();
            settings.customContainer(ModularContainer::new);
            settings.getRecipeViewerSettings().enable();
            screen.getContext().setSettings(settings);
            ModularContainer container = settings.createContainer();
            container.constructClientOnly();
            MCHelper.displayScreen(new GuiContainerWrapper(container, screen));
        } catch (RuntimeException | LinkageError e) {
            MMCEAdvancedBuilderTool.LOGGER.error("Failed to open the block alias picker", e);
        }
    }

    private void showStored(Map<String, String> selections) {
        if (selections != null) {
            state().stored.putAll(selections);
        }
        if (allCell != null) {
            showStoredAllPick(allCell);
        }
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        ModularPanel panel = ModularPanel.defaultPanel("mmce_advanced_builder_tool_variables", PANEL_WIDTH, PANEL_HEIGHT);
        panel.getArea().setSize(PANEL_WIDTH, PANEL_HEIGHT);
        Flow column = Flow.column().margin(LIST_MARGIN).widthRel(1f).heightRel(1f).collapseDisabledChild()
                .child(new TextWidget<>(IKey.dynamic(() -> state().expanded == null
                        ? IKey.lang("gui.mmce_advanced_builder_tool.variables.title").getFormatted()
                        : IKey.lang("gui.mmce_advanced_builder_tool.variables.blocks").getFormatted()))
                        .height(HEADER_HEIGHT).widthRel(1f).alignment(Alignment.Center));
        buildAllRow(column);
        buildSkipExistingRow(column);
        buildAliasRows(column);
        buildBlockRows(column);
        panel.child(column);
        return panel;
    }

    private void buildAliasRows(Flow column) {
        List<String> names = aliases().names();
        if (names.isEmpty()) {
            column.child(new TextWidget<>(IKey.lang("gui.mmce_advanced_builder_tool.variables.none"))
                    .height(ROW_HEIGHT).widthRel(1f).alignment(Alignment.Center));
            return;
        }
        int pages = (names.size() + ALIAS_ROWS - 1) / ALIAS_ROWS;
        int page = Math.min(state().aliasPage, pages - 1);
        state().aliasPage = page;
        for (int currentPage = 0; currentPage < pages; currentPage++) {
            int start = currentPage * ALIAS_ROWS;
            int shown = Math.min(ALIAS_ROWS, names.size() - start);
            for (int i = 0; i < shown; i++) {
                String variable = names.get(start + i);
                int pageForRow = currentPage;
                column.child(button(variable + "  (" + aliases().blocks(variable).size() + ")")
                        .tooltip(tooltip -> {
                            String stored = state().stored.get(variable);
                            if (stored != null && !stored.isEmpty()) {
                                tooltip.addLine(IKey.str(stored));
                            }
                        })
                        .setEnabledIf(widget -> state().expanded == null && state().aliasPage == pageForRow)
                        .onMouseTapped(mouseButton -> {
                            state().expanded = variable;
                            return true;
                        }));
            }
        }
        if (pages > 1) {
            Flow pager = Flow.row().widthRel(1f).height(ROW_HEIGHT).collapseDisabledChild();
            pager.child(button("<")
                    .width(ROW_HEIGHT).setEnabledIf(widget -> state().expanded == null && state().aliasPage > 0)
                    .onMouseTapped(mouseButton -> {
                        state().aliasPage--;
                        return true;
                    }));
            pager.child(new TextWidget<>(IKey.dynamic(() -> (state().aliasPage + 1) + "/" + pages))
                    .width(CONTENT_WIDTH - ROW_HEIGHT * 2).height(ROW_HEIGHT).alignment(Alignment.Center));
            pager.child(button(">")
                    .width(ROW_HEIGHT).setEnabledIf(widget -> state().expanded == null && state().aliasPage + 1 < pages)
                    .onMouseTapped(mouseButton -> {
                        state().aliasPage++;
                        return true;
                    }));
            column.child(pager.setEnabledIf(widget -> state().expanded == null));
        }
    }

    private void buildBlockRows(Flow column) {
        for (String variable : aliases().names()) {
            List<VariableBlockSpec> blocks = aliases().blocks(variable);
            int shown = Math.min(blocks.size(), GRID_COLUMNS * GRID_ROWS);
            for (int row = 0; row * GRID_COLUMNS < shown; row++) {
                Flow rowFlow = Flow.row().widthRel(1f).height(CELL_SIZE).margin(0, 0, 0, ROW_GAP)
                        .collapseDisabledChild();
                for (int columnIndex = 0; columnIndex < GRID_COLUMNS; columnIndex++) {
                    int index = row * GRID_COLUMNS + columnIndex;
                    if (index >= shown) {
                        rowFlow.child(Flow.row().size(CELL_SIZE, CELL_SIZE));
                        continue;
                    }
                    VariableBlockSpec spec = blocks.get(index);
                    rowFlow.child(blockCell(spec)
                            .onMouseTapped(mouseButton -> {
                                select(variable, spec.describe());
                                return true;
                            }));
                }
                column.child(rowFlow.setEnabledIf(widget -> variable.equals(state().expanded)));
            }
        }

        if (!aliases().names().isEmpty()) {
            column.child(actionCell(GuiTextures.CLOSE,
                    "gui.mmce_advanced_builder_tool.variables.pattern_default", 0)
                    .setEnabledIf(widget -> state().expanded != null)
                    .onMouseTapped(mouseButton -> {
                        select(state().expanded, "");
                        return true;
                    }));
            column.child(actionCell(GuiTextures.MOVE_LEFT,
                    "gui.mmce_advanced_builder_tool.variables.back", CELL_SIZE + ROW_GAP)
                    .setEnabledIf(widget -> state().expanded != null)
                    .onMouseTapped(mouseButton -> {
                        state().expanded = null;
                        return true;
                    }));
        }
    }

    private void buildAllRow(Flow column) {
        GhostBlockSlot cell = new GhostBlockSlot(CELL_SIZE, stack -> {
            // An empty stack is how the cell reports "cleared", so both it and an unusable stack fall
            // back to the pattern's own blocks.
            String spec = GhostBlockSlot.blockSpecOf(stack);
            select(ALL_VARIABLES, spec == null ? "" : spec);
        });
        allCell = cell;
        column.child(Flow.row().widthRel(1f).height(CELL_SIZE).margin(0, 0, ROW_GAP, ROW_GAP)
                .collapseDisabledChild()
                .child(cell.setEnabledIf(widget -> state().expanded == null)
                        .tooltip(tooltip -> tooltip.addLine(
                                IKey.lang("gui.mmce_advanced_builder_tool.variables.all.hint"))))
                .child(new TextWidget<>(IKey.lang("gui.mmce_advanced_builder_tool.variables.all"))
                        .width(CONTENT_WIDTH - CELL_SIZE).height(CELL_SIZE)
                        .alignment(Alignment.Center)
                        .setEnabledIf(widget -> state().expanded == null)));
    }

    private void buildSkipExistingRow(Flow column) {
        column.child(Flow.row().widthRel(1f).height(ROW_HEIGHT).margin(0, 0, ROW_GAP, ROW_GAP)
                .collapseDisabledChild()
                .child(new TextWidget<>(IKey.lang("gui.mmce_advanced_builder_tool.variables.skip_existing"))
                        .width(CONTENT_WIDTH - 38).height(ROW_HEIGHT)
                        .alignment(Alignment.Center)
                        .setEnabledIf(widget -> state().expanded == null))
                .child(new ButtonWidget<>()
                        .size(38, ROW_HEIGHT)
                        .child(new TextWidget<>(IKey.dynamic(() -> state().skipExistingBlocks
                                ? IKey.lang("gui.mmce_advanced_builder_tool.on").getFormatted()
                                : IKey.lang("gui.mmce_advanced_builder_tool.off").getFormatted()))
                                .size(38, ROW_HEIGHT).alignment(Alignment.Center))
                        .setEnabledIf(widget -> state().expanded == null)
                        .onMouseTapped(mouseButton -> {
                            state().skipExistingBlocks = !state().skipExistingBlocks;
                            skipExistingHandler.accept(state().skipExistingBlocks);
                            return true;
                        })));
    }

    @Override
    public void onOpen() {
        super.onOpen();
        if (allCell != null) {
            allCell.registerRecipeViewerTarget();
        }
    }

    private void showStoredAllPick(GhostBlockSlot cell) {
        String spec = state().stored.get(ALL_VARIABLES);
        if (spec == null || spec.isEmpty()) {
            return;
        }
        VariableBlockSpec parsed = VariableBlockSpec.parse(spec);
        if (parsed != null) {
            cell.showPreview(parsed.displayStack());
        }
    }

    private ButtonWidget<?> button(String label) {
        return new ButtonWidget<>()
                .widthRel(1f).height(ROW_HEIGHT)
                .child(new TextWidget<>(label)
                        .widthRel(1f).height(ROW_HEIGHT)
                        .alignment(Alignment.Center));
    }

    private ButtonWidget<?> actionCell(IDrawable icon, String tooltipKey, int leftOffset) {
        return new ButtonWidget<>()
                .size(CELL_SIZE, CELL_SIZE)
                .left(leftOffset).bottom(0)
                .overlay(icon)
                .tooltip(tooltip -> tooltip.addLine(IKey.lang(tooltipKey)));
    }

    private ButtonWidget<?> blockCell(VariableBlockSpec spec) {
        ItemStack stack = spec.displayStack();
        return new ButtonWidget<>()
                .size(CELL_SIZE, CELL_SIZE)
                .overlay(new ItemDrawable(stack).asIcon().size(CELL_SIZE, CELL_SIZE))
                .tooltip(tooltip -> tooltip.addFromItem(stack));
    }

    private void select(String variable, String spec) {
        if (variable == null || variable.isEmpty()) {
            return;
        }
        state().stored.put(variable, spec);
        handler.onSelect(variable, spec);
        state().expanded = null;
    }

    private static final class SelectionState {
        private String expanded;
        private int aliasPage;
        private boolean skipExistingBlocks;
        private final Map<String, String> stored = new HashMap<>();
    }

    public interface SelectionHandler {
        void onSelect(String variable, String spec);
    }
}
