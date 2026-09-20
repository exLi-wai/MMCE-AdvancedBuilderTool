package com.lw.mmce_advanced_builder_tool.common.integration.mmce;

import com.lw.mmce_advanced_builder_tool.common.util.AdvancedBuilderUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class AdvancedBuilderConfig {

    private static final String TAG_USE_AE_ITEMS = "mmce_abt_use_ae_items";
    private static final String TAG_USE_AE_FLUIDS = "mmce_abt_use_ae_fluids";
    private static final String TAG_CRAFT_MISSING = "mmce_abt_craft_missing";
    private static final String TAG_DISASSEMBLE_MODE = "mmce_abt_disassemble_mode";
    private static final String TAG_LEGACY_CRAFT_MISSING_ITEMS = "mmce_abt_craft_missing_items";
    private static final String TAG_LEGACY_CRAFT_MISSING_FLUIDS = "mmce_abt_craft_missing_fluids";
    private static final String TAG_DYNAMIC_LENGTH = "mmce_abt_dynamic_length";
    private static final String TAG_ATTACHMENT_MODULE = "mmce_abt_attachment_module";
    private static final int MAX_ATTACHMENT_MODULE_LENGTH = 256;
    private static final int DEFAULT_DYNAMIC_LENGTH = 1;
    private static final int MAX_DYNAMIC_LENGTH = 4096;
    public static final int TICK_INTERVAL = 1;
    public static final int OPERATIONS_PER_TICK = 64;

    private AdvancedBuilderConfig() {
    }

    public static boolean useAeItems(ItemStack stack) {
        return getTag(stack).getBoolean(TAG_USE_AE_ITEMS);
    }

    public static void setUseAeItems(ItemStack stack, boolean value) {
        getTag(stack).setBoolean(TAG_USE_AE_ITEMS, value);
    }

    public static boolean useAeFluids(ItemStack stack) {
        return getTag(stack).getBoolean(TAG_USE_AE_FLUIDS);
    }

    public static void setUseAeFluids(ItemStack stack, boolean value) {
        getTag(stack).setBoolean(TAG_USE_AE_FLUIDS, value);
    }

    public static boolean craftMissing(ItemStack stack) {
        NBTTagCompound tag = getTag(stack);
        if (tag.hasKey(TAG_CRAFT_MISSING)) {
            return tag.getBoolean(TAG_CRAFT_MISSING);
        }
        return tag.getBoolean(TAG_LEGACY_CRAFT_MISSING_ITEMS) || tag.getBoolean(TAG_LEGACY_CRAFT_MISSING_FLUIDS);
    }

    public static void setCraftMissing(ItemStack stack, boolean value) {
        NBTTagCompound tag = getTag(stack);
        tag.setBoolean(TAG_CRAFT_MISSING, value);
        tag.removeTag(TAG_LEGACY_CRAFT_MISSING_ITEMS);
        tag.removeTag(TAG_LEGACY_CRAFT_MISSING_FLUIDS);
    }

    public static boolean disassembleMode(ItemStack stack) {
        return getTag(stack).getBoolean(TAG_DISASSEMBLE_MODE);
    }

    public static void setDisassembleMode(ItemStack stack, boolean value) {
        getTag(stack).setBoolean(TAG_DISASSEMBLE_MODE, value);
    }

    public static int dynamicLength(ItemStack stack) {
        NBTTagCompound tag = getTag(stack);
        if (!tag.hasKey(TAG_DYNAMIC_LENGTH)) {
            tag.setInteger(TAG_DYNAMIC_LENGTH, DEFAULT_DYNAMIC_LENGTH);
        }
        return clampDynamicLength(tag.getInteger(TAG_DYNAMIC_LENGTH));
    }

    public static void setDynamicLength(ItemStack stack, int value) {
        getTag(stack).setInteger(TAG_DYNAMIC_LENGTH, clampDynamicLength(value));
    }

    public static String attachmentModule(ItemStack stack) {
        return normalizeAttachmentModule(getTag(stack).getString(TAG_ATTACHMENT_MODULE));
    }

    public static void setAttachmentModule(ItemStack stack, String value) {
        NBTTagCompound tag = getTag(stack);
        String normalized = normalizeAttachmentModule(value);
        if (normalized.isEmpty()) {
            tag.removeTag(TAG_ATTACHMENT_MODULE);
        } else {
            tag.setString(TAG_ATTACHMENT_MODULE, normalized);
        }
    }

    public static int clampDynamicLength(int value) {
        return AdvancedBuilderUtils.clamp(value, 0, MAX_DYNAMIC_LENGTH);
    }

    private static String normalizeAttachmentModule(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= MAX_ATTACHMENT_MODULE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ATTACHMENT_MODULE_LENGTH);
    }

    private static NBTTagCompound getTag(ItemStack stack) {
        return AdvancedBuilderUtils.getOrCreateTag(stack);
    }
}
