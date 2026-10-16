package com.lw.mmce_advanced_builder_tool.common.variable;

import com.cleanroommc.modularui.api.MCHelper;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.DynamicDrawable;
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
import net.minecraft.util.text.translation.I18n;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picker for the {@code *.var.json} aliases a machine pattern can reference.
 *
 * <p>One screen, two states: the alias list, and the block list of the alias currently opened. Every
 * row is built once and switched on or off through {@code setEnabledIf}, which ModularUI honours for
 * both layout and input. Rebuilding the panel or swapping screens is deliberately avoided - replacing
 * the screen that is currently dispatching the click did not work reliably.
 *
 * <p>Neither the replace-all cell nor the skip-existing toggle is part of this screen: both are
 * settings of the builder tool and live in its panel, next to the button that opens this picker.
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
    private static final int GRID_COLUMNS = 8;
    private static final int GRID_ROWS = 6;
    private static final int LIST_MARGIN = 6;
    private static final int PANEL_HEIGHT = 160;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - LIST_MARGIN * 2;
    /** An alias row shows the picked block in the left fifth and the name in the right three fifths. */
    private static final int ALIAS_NAME_WIDTH = CONTENT_WIDTH * 3 / 5;
    /** Nudge of the alias name off the left edge of its button. */
    private static final int ALIAS_NAME_OFFSET = 2;
    /** Reserved for the {@code (n)} block count, which is pinned to the right edge of the button. */
    private static final int ALIAS_COUNT_WIDTH = 22;
    private static final int BLOCK_PAGE_TEXT_WIDTH = 40;
    private static final int BLOCK_PAGER_WIDTH = CELL_SIZE * 2 + BLOCK_PAGE_TEXT_WIDTH;
    /** Alias names are localised by the alias itself: {@code gui.mmce_advanced_builder_tool.Variable.<alias>}. */
    private static final String ALIAS_NAME_KEY = "gui.mmce_advanced_builder_tool.Variable.";
    private static final int BUTTON_TEXT_COLOR = 0xFFFFFFFF;

    private final SelectionHandler handler;
    private final Map<String, ItemStack> pickedStacks = new HashMap<>();
    private SelectionState state;
    private BlockVariables aliasCache;

    private BlockVariablePickerScreen(String initial, SelectionHandler handler) {
        super(OWNER);
        this.handler = handler;
        state().expanded = initial;
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

    public static void open(String initial, Map<String, String> stored, SelectionHandler handler) {
        try {
            BlockVariablePickerScreen screen = new BlockVariablePickerScreen(initial, handler);
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
                column.child(aliasRow(variable)
                        .setEnabledIf(widget -> state().expanded == null && state().aliasPage == pageForRow));
            }
        }
        if (pages > 1) {
            Flow pager = Flow.row().widthRel(1f).height(ROW_HEIGHT).collapseDisabledChild()
                    .setEnabledIf(widget -> state().expanded == null);
            pager.child(button("<")
                    .width(ROW_HEIGHT).setEnabledIf(widget -> state().expanded == null && state().aliasPage > 0)
                    .onMouseTapped(mouseButton -> {
                        state().aliasPage--;
                        return true;
                    }));
            pager.child(new TextWidget<>(IKey.dynamic(() -> (state().aliasPage + 1) + "/" + pages))
                    .width(CONTENT_WIDTH - ROW_HEIGHT * 2).height(ROW_HEIGHT).alignment(Alignment.Center)
                    .color(BUTTON_TEXT_COLOR).shadow(true));
            pager.child(button(">")
                    .width(ROW_HEIGHT).setEnabledIf(widget -> state().expanded == null && state().aliasPage + 1 < pages)
                    .onMouseTapped(mouseButton -> {
                        state().aliasPage++;
                        return true;
                    }));
            column.child(pager);
        }
    }

    private void buildBlockRows(Flow column) {
        int cellsPerPage = GRID_COLUMNS * GRID_ROWS;
        for (String variable : aliases().names()) {
            List<VariableBlockSpec> blocks = aliases().blocks(variable);
            int pages = Math.max(1, (blocks.size() + cellsPerPage - 1) / cellsPerPage);
            for (int page = 0; page < pages; page++) {
                int pageStart = page * cellsPerPage;
                int shown = Math.min(cellsPerPage, blocks.size() - pageStart);
                for (int row = 0; row * GRID_COLUMNS < shown; row++) {
                    Flow rowFlow = Flow.row().widthRel(1f).height(CELL_SIZE).margin(0, 0, 0, ROW_GAP)
                            .collapseDisabledChild();
                    for (int columnIndex = 0; columnIndex < GRID_COLUMNS; columnIndex++) {
                        int cell = row * GRID_COLUMNS + columnIndex;
                        if (cell >= shown) {
                            rowFlow.child(Flow.row().size(CELL_SIZE, CELL_SIZE));
                            continue;
                        }
                        VariableBlockSpec spec = blocks.get(pageStart + cell);
                        rowFlow.child(blockCell(spec)
                                .onMouseTapped(mouseButton -> {
                                    select(variable, spec.describe());
                                    return true;
                                }));
                    }
                    int pageForRow = page;
                    column.child(rowFlow.setEnabledIf(widget -> variable.equals(state().expanded)
                            && state().blockPage == pageForRow));
                }
            }
            if (pages > 1) {
                buildBlockPager(column, variable, pages);
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

    private void buildBlockPager(Flow column, String variable, int pages) {
        column.child(button("<")
                .width(CELL_SIZE)
                .left(CONTENT_WIDTH - BLOCK_PAGER_WIDTH).bottom(0)
                .setEnabledIf(widget -> variable.equals(state().expanded) && state().blockPage > 0)
                .onMouseTapped(mouseButton -> {
                    state().blockPage--;
                    return true;
                }));
        column.child(new TextWidget<>(IKey.dynamic(() -> (state().blockPage + 1) + "/" + pages))
                .width(BLOCK_PAGE_TEXT_WIDTH).height(ROW_HEIGHT)
                .alignment(Alignment.Center)
                .color(BUTTON_TEXT_COLOR).shadow(true)
                .left(CONTENT_WIDTH - CELL_SIZE - BLOCK_PAGE_TEXT_WIDTH).bottom(0)
                .setEnabledIf(widget -> variable.equals(state().expanded)));
        column.child(button(">")
                .width(CELL_SIZE)
                .left(CONTENT_WIDTH - CELL_SIZE).bottom(0)
                .setEnabledIf(widget -> variable.equals(state().expanded) && state().blockPage + 1 < pages)
                .onMouseTapped(mouseButton -> {
                    state().blockPage++;
                    return true;
                }));
    }

    /**
     * One alias row: a cell in the left fifth, an empty fifth, then the name button in the right three
     * fifths. The cell and the button are separate: the cell shows the block the alias resolves to,
     * while the name button is the one that opens the alias.
     */
    private Flow aliasRow(String variable) {
        return Flow.row().widthRel(1f).height(CELL_SIZE)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .child(aliasCell(variable))
                .child(aliasNameButton(variable));
    }

    private Flow aliasCell(String variable) {
        return Flow.row().size(CELL_SIZE, CELL_SIZE).padding(1)
                .background(GuiTextures.SLOT_ITEM)
                .overlay(new DynamicDrawable(() -> {
                    ItemStack stack = pickedStack(variable);
                    return stack.isEmpty() ? null : new ItemDrawable(stack).asIcon().size(CELL_SIZE - 2, CELL_SIZE - 2);
                }))
                .tooltipDynamic(tooltip -> {
                    ItemStack stack = pickedStack(variable);
                    if (!stack.isEmpty()) {
                        tooltip.addFromItem(stack);
                    }
                })
                .tooltipAutoUpdate(true);
    }

    private ButtonWidget<?> aliasNameButton(String variable) {
        String key = ALIAS_NAME_KEY + variable;
        String localized = I18n.translateToLocal(key);
        boolean hasLocalization = !localized.equals(key);
        String name = hasLocalization ? localized : variable;
        return new ButtonWidget<>()
                .width(ALIAS_NAME_WIDTH).height(CELL_SIZE)
                .child(Flow.row().widthRel(1f).height(CELL_SIZE)
                        .child(new TextWidget<>(name)
                                .margin(ALIAS_NAME_OFFSET, 0, 0, 0)
                                .width(ALIAS_NAME_WIDTH - ALIAS_NAME_OFFSET - ALIAS_COUNT_WIDTH)
                                .height(CELL_SIZE)
                                .alignment(Alignment.CenterLeft)
                                .color(BUTTON_TEXT_COLOR).shadow(true))
                        .child(new TextWidget<>("(" + aliases().blocks(variable).size() + ")")
                                .width(ALIAS_COUNT_WIDTH).height(CELL_SIZE)
                                .alignment(Alignment.Center)
                                .color(BUTTON_TEXT_COLOR).shadow(true)))
                .tooltip(tooltip -> {
                    if (hasLocalization) {
                        tooltip.addLine(IKey.str(name));
                    } else {
                        tooltip.addLine(IKey.lang("gui.mmce_advanced_builder_tool.variables.not_localized", key));
                    }
                })
                .onMouseTapped(mouseButton -> {
                    state().expanded = variable;
                    state().blockPage = 0;
                    return true;
                });
    }

    /**
     * The spec the builder would use for this alias: an explicit replace-all pick wins over the alias's
     * own pick, the same way {@link BlockVariables#applySelections} resolves it.
     */
    private String pickedSpec(String variable) {
        String global = state().stored.get(BlockVariables.ALL_VARIABLES);
        if (global != null && !global.isEmpty()) {
            return global;
        }
        return state().stored.get(variable);
    }

    /** Parsed once per distinct spec, because the cell asks for it on every frame. */
    private ItemStack pickedStack(String variable) {
        String spec = pickedSpec(variable);
        if (spec == null || spec.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack cached = pickedStacks.get(spec);
        if (cached != null) {
            return cached;
        }
        VariableBlockSpec parsed = VariableBlockSpec.parse(spec);
        ItemStack stack = parsed == null ? ItemStack.EMPTY : parsed.displayStack();
        pickedStacks.put(spec, stack);
        return stack;
    }

    private ButtonWidget<?> button(String label) {
        return new ButtonWidget<>()
                .widthRel(1f).height(ROW_HEIGHT)
                .overlay(IKey.str(label));
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
        private int blockPage;
        private final Map<String, String> stored = new HashMap<>();
    }

    public interface SelectionHandler {
        void onSelect(String variable, String spec);
    }
}
