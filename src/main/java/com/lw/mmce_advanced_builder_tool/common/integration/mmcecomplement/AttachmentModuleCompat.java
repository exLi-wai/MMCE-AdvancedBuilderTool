package com.lw.mmce_advanced_builder_tool.common.integration.mmcecomplement;

import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.TaggedPositionBlockArray;
import net.edwin.mmcecomplement.attachment.AttachmentMachine;
import net.edwin.mmcecomplement.attachment.AttachmentModule;

import java.util.Map;

/**
 * Bridge to MMCE Complement's attachment-module API.
 *
 * <p>The mod is a {@code compileOnly} dependency, so every entry point of this class
 * is guarded by {@link Mods#MMCE_COMPLEMENT}. Nothing here may run without that guard:
 * {@link #findPattern} touches {@link AttachmentMachine} types directly. The
 * MMCE Complement only handler lives in the nested class below so that loading this
 * class never drags those types in.
 */
public final class AttachmentModuleCompat {

    /**
     * Resolves the selected module-only pattern.
     *
     * @return the attachment pattern, or {@code null} when the id is empty, MMCE
     *         Complement is absent, or the machine has no such module
     */
    public static TaggedPositionBlockArray findPattern(DynamicMachine machine, String moduleId) {
        if (machine == null || moduleId == null || moduleId.trim().isEmpty()
                || !Mods.MMCE_COMPLEMENT.isLoading()) {
            return null;
        }
        return Handler.findPattern(machine, moduleId.trim());
    }

    private static final class Handler {

        private Handler() {
        }

        private static TaggedPositionBlockArray findPattern(DynamicMachine machine, String moduleId) {
            try {
                if (!(machine instanceof AttachmentMachine)) {
                    return null;
                }

                Map<String, AttachmentModule> modules =
                        ((AttachmentMachine) machine).mmceComplement$getAttachmentModules();
                if (modules == null) {
                    return null;
                }

                AttachmentModule module = modules.get(moduleId);
                if (module == null) {
                    return null;
                }

                TaggedPositionBlockArray pattern =
                        module.getEffectivePattern(machine.getPattern(), modules);
                return pattern == null ? null : new TaggedPositionBlockArray(pattern);
            } catch (LinkageError error) {
                MMCEAdvancedBuilderTool.LOGGER.warn(
                        "MMCE Complement attachment-module API is incompatible with this build; "
                                + "falling back to the main pattern", error);
                return null;
            }
        }
    }
}
