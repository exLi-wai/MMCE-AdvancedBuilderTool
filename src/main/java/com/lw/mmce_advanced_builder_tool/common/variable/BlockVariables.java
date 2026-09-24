package com.lw.mmce_advanced_builder_tool.common.variable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import hellfirepvp.modularmachinery.common.util.IBlockStateDescriptor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Loader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The {@code *.var.json} alias lists Modular Machinery lets a machine pattern reference by name, for
 * example {@code "casings_all": [...]}.
 *
 * <p>MMCE expands an alias into a plain candidate list while parsing the machine, so the alias name
 * is gone once a pattern can be inspected. This class rebuilds the name-to-blocks mapping from the
 * json files and matches it back onto pattern positions, which is what allows the builder to offer
 * "use the block I pick for every position that referenced {@code casings_all}".
 *
 * <p>Config files win over the copies shipped inside the Modular Machinery jar, matching how MMCE
 * resolves them.
 */
public final class BlockVariables {

    public static final String ALL_VARIABLES = "all";

    private static final String ASSET_ROOT = "assets/modularmachinery/default_variables";
    private static final String DEFAULT_FILE = "casings.var.json";
    private static final String VAR_SUFFIX = ".var.json";
    private static final String[] CONFIG_DIRS = {
            "modularmachinery/machinery/variables",
            "modularmachinery/machinery",
            "modularmachinery/variables",
    };

    private final Map<String, List<VariableBlockSpec>> variables;

    private BlockVariables(Map<String, List<VariableBlockSpec>> variables) {
        this.variables = variables;
    }

    public static BlockVariables load() {
        Map<String, List<VariableBlockSpec>> collected = new LinkedHashMap<>();
        loadBundledDefaults(collected);
        loadConfigFiles(collected);
        return new BlockVariables(collected);
    }

    public List<String> names() {
        return new ArrayList<>(new TreeMap<>(variables).keySet());
    }

    public List<VariableBlockSpec> blocks(String variable) {
        List<VariableBlockSpec> specs = variable == null ? null : variables.get(variable);
        return specs == null ? Collections.<VariableBlockSpec>emptyList() : Collections.unmodifiableList(specs);
    }

    public VariableBlockSpec spec(String variable, String specText) {
        for (VariableBlockSpec candidate : blocks(variable)) {
            if (candidate.describe().equals(specText)) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return variables.isEmpty();
    }

    /**
     * Finds the alias a pattern position stands for.
     *
     * <p>The position must have exactly the alias's candidate states. A subset could also be an
     * ordinary block list authored directly in the machine pattern.
     *
     * @return the alias name, or {@code null} when the position is a plain block list
     */
    public String matchVariable(BlockArray.BlockInformation information) {
        List<long[]> positionKeys = stateKeys(information);
        if (positionKeys.isEmpty()) {
            return null;
        }
        String best = null;
        int bestSize = Integer.MAX_VALUE;
        for (Map.Entry<String, List<VariableBlockSpec>> entry : variables.entrySet()) {
            List<VariableBlockSpec> specs = entry.getValue();
            if (specs.size() >= bestSize) {
                continue;
            }
            if (matchesExactly(specs, positionKeys)) {
                best = entry.getKey();
                bestSize = specs.size();
            }
        }
        return best;
    }

    private static boolean acceptsAll(List<VariableBlockSpec> specs, List<long[]> positionKeys) {
        for (long[] key : positionKeys) {
            Block block = Block.getBlockById((int) key[0]);
            int meta = (int) key[1];
            boolean accepted = false;
            for (VariableBlockSpec spec : specs) {
                if (spec.accepts(block, meta)) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the player's picks to every pattern position that stands for an alias.
     *
     * <p>Resolution order per alias: a valid {@link #ALL_VARIABLES} pick, its own pick, then the
     * pattern's candidates. An explicit pick replaces the candidates completely so assembly cannot
     * prefer an original mechanical casing over the selected block.
     *
     * @param selections alias name to alias entry, as stored by
     *                   {@link com.lw.mmce_advanced_builder_tool.common.BuilderToolSettings#variables}
     * @return how many positions were rewritten
     */
    public int applySelections(BlockArray pattern, Map<String, String> selections) {
        if (pattern == null || selections == null || selections.isEmpty()) {
            return 0;
        }
        VariableBlockSpec global = VariableBlockSpec.parse(selections.get(ALL_VARIABLES));
        int rewritten = 0;
        for (Map.Entry<BlockPos, BlockArray.BlockInformation> entry : pattern.getPattern().entrySet()) {
            String alias = matchVariable(entry.getValue());
            if (alias == null) {
                continue;
            }
            VariableBlockSpec chosen = global != null ? global : find(blocks(alias), selections.get(alias));
            if (chosen != null) {
                entry.setValue(replaceCandidates(entry.getValue(), chosen));
                rewritten++;
            }
        }
        if (rewritten > 0) {
            pattern.flushTileBlocksCache();
        }
        return rewritten;
    }

    private static VariableBlockSpec find(List<VariableBlockSpec> specs, String description) {
        if (description == null) {
            return null;
        }
        for (VariableBlockSpec spec : specs) {
            if (spec.describe().equals(description.trim())) {
                return spec;
            }
        }
        return null;
    }

    private static boolean matchesExactly(List<VariableBlockSpec> specs, List<long[]> positionKeys) {
        if (!acceptsAll(specs, positionKeys)) {
            return false;
        }
        for (VariableBlockSpec spec : specs) {
            long blockId = Block.getIdFromBlock(spec.getBlock());
            for (int meta : spec.getAcceptedMetas()) {
                boolean present = false;
                for (long[] key : positionKeys) {
                    if (key[0] == blockId && key[1] == meta) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BlockArray.BlockInformation replaceCandidates(BlockArray.BlockInformation original,
                                                               VariableBlockSpec chosen) {
        // BlockInformation caches ingredient states separately from matchingStates. Constructing a
        // fresh instance resets both lists, otherwise the original casing remains a build candidate.
        BlockArray.BlockInformation replacement = new BlockArray.BlockInformation(
                Collections.singletonList(IBlockStateDescriptor.of(chosen.state())));
        replacement.setMatchingTag(original.getMatchingTag());
        replacement.setPreviewTag(original.getPreviewTag());
        replacement.setNBTChecker(original.getNBTChecker());
        return replacement;
    }

    private static List<long[]> stateKeys(BlockArray.BlockInformation information) {
        List<long[]> keys = new ArrayList<>();
        for (IBlockState state : applicableStates(information)) {
            long id = Block.getIdFromBlock(state.getBlock());
            int meta = state.getBlock().getMetaFromState(state);
            boolean duplicate = false;
            for (long[] existing : keys) {
                if (existing[0] == id && existing[1] == meta) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                keys.add(new long[]{id, meta});
            }
        }
        return keys;
    }

    private static List<IBlockState> applicableStates(BlockArray.BlockInformation information) {
        List<IBlockState> states = new ArrayList<>();
        for (IBlockStateDescriptor descriptor : information.getMatchingStates()) {
            for (IBlockState state : descriptor.getApplicable()) {
                if (state != null) {
                    states.add(state);
                }
            }
        }
        if (states.isEmpty() && information.getSampleState() != null) {
            states.add(information.getSampleState());
        }
        return states;
    }

    private static void loadBundledDefaults(Map<String, List<VariableBlockSpec>> target) {
        URL url = BlockVariables.class.getClassLoader().getResource(ASSET_ROOT + "/" + DEFAULT_FILE);
        if (url == null) {
            return;
        }
        try (Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            parseInto(target, new JsonParser().parse(reader));
        } catch (IOException | RuntimeException e) {
            MMCEAdvancedBuilderTool.LOGGER.warn("Failed to read the bundled {}", DEFAULT_FILE, e);
        }
    }

    private static void loadConfigFiles(Map<String, List<VariableBlockSpec>> target) {
        Path configRoot = Loader.instance().getConfigDir().toPath();
        for (String relative : CONFIG_DIRS) {
            Path dir = configRoot.resolve(relative);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path file : (Iterable<Path>) stream.filter(BlockVariables::isVariableFile)::iterator) {
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        parseInto(target, new JsonParser().parse(reader));
                    } catch (IOException | RuntimeException e) {
                        MMCEAdvancedBuilderTool.LOGGER.warn("Failed to read variable file {}", file, e);
                    }
                }
            } catch (IOException e) {
                MMCEAdvancedBuilderTool.LOGGER.warn("Failed to list variable directory {}", dir, e);
            }
        }
    }

    private static boolean isVariableFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(VAR_SUFFIX);
    }

    private static void parseInto(Map<String, List<VariableBlockSpec>> target, JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonObject object = root.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            List<VariableBlockSpec> specs = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                VariableBlockSpec spec = VariableBlockSpec.parse(element.getAsString());
                if (spec != null) {
                    specs.add(spec);
                }
            }
            if (!specs.isEmpty()) {
                target.put(entry.getKey(), specs);
            }
        }
    }
}
