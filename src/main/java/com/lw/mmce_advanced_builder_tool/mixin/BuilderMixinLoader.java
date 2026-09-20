package com.lw.mmce_advanced_builder_tool.mixin;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Arrays;
import java.util.List;

public class BuilderMixinLoader implements ILateMixinLoader {

    private static final List<String> MIXIN_CONFIGS = Arrays.asList("mixins.mmce_advanced_builder_tool.json");

    @Override
    public List<String> getMixinConfigs() {
        return MIXIN_CONFIGS;
    }
}
