package com.lw.mmce_advanced_builder_tool.common.integration.mmcecomplement;

import com.lw.mmce_advanced_builder_tool.MMCEAdvancedBuilderTool;
import com.lw.mmce_advanced_builder_tool.common.util.Mods;
import hellfirepvp.modularmachinery.common.machine.DynamicMachine;
import hellfirepvp.modularmachinery.common.machine.TaggedPositionBlockArray;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public final class AttachmentModuleCompat {

    public static final String MOD_ID = "mmce_complement";

    private static final String ATTACHMENT_MACHINE_CLASS =
            "net.edwin.mmcecomplement.attachment.AttachmentMachine";
    private static final String GET_MODULES_METHOD = "mmceComplement$getAttachmentModules";
    private static final String GET_EFFECTIVE_PATTERN_METHOD = "getEffectivePattern";
    private static boolean resolved;
    private static Class<?> attachmentMachineClass;
    private static Method getModulesMethod;

    private AttachmentModuleCompat() {
    }
    public static boolean isAvailable() {
        return Loader.isModLoaded(MOD_ID);
    }

    public static TaggedPositionBlockArray findPattern(DynamicMachine machine, String moduleId) {
        if (machine == null || moduleId == null || moduleId.trim().isEmpty() || !Mods.MMCE_COMPLEMENT.isLoading()) {
            return null;
        }
        if (!resolve() || !attachmentMachineClass.isInstance(machine)) {
            return null;
        }

        try {
            Object moduleMap = getModulesMethod.invoke(machine);
            if (!(moduleMap instanceof Map)) {
                return null;
            }

            Map<?, ?> modules = (Map<?, ?>) moduleMap;
            Object module = modules.get(moduleId.trim());
            if (module == null) {
                return null;
            }

            Method getEffectivePattern = module.getClass().getMethod(GET_EFFECTIVE_PATTERN_METHOD,
                    TaggedPositionBlockArray.class, Map.class);
            Object pattern = getEffectivePattern.invoke(module, machine.getPattern(), modules);
            return pattern instanceof TaggedPositionBlockArray
                    ? (TaggedPositionBlockArray) pattern
                    : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | LinkageError e) {
            MMCEAdvancedBuilderTool.LOGGER.warn("Failed to resolve MMCE attachment module '{}'; falling back to main",
                    moduleId, e);
            return null;
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return attachmentMachineClass != null;
        }
        resolved = true;
        try {
            attachmentMachineClass = Class.forName(ATTACHMENT_MACHINE_CLASS, false,
                    AttachmentModuleCompat.class.getClassLoader());
            getModulesMethod = attachmentMachineClass.getMethod(GET_MODULES_METHOD);
        } catch (ClassNotFoundException e) {
            MMCEAdvancedBuilderTool.LOGGER.warn(
                    "MMCE Complement is loaded, but {} is missing; attachment modules are disabled",
                    ATTACHMENT_MACHINE_CLASS);
            attachmentMachineClass = null;
        } catch (NoSuchMethodException | LinkageError e) {
            MMCEAdvancedBuilderTool.LOGGER.warn(
                    "MMCE Complement is loaded, but its attachment-module API could not be resolved; "
                            + "attachment modules are disabled", e);
            attachmentMachineClass = null;
        }
        return attachmentMachineClass != null;
    }
}
