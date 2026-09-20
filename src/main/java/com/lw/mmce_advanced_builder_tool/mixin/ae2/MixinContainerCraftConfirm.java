package com.lw.mmce_advanced_builder_tool.mixin.ae2;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.container.implementations.ContainerCraftConfirm;
import com.lw.mmce_advanced_builder_tool.common.integration.mmce.AdvancedBuilderCraftingConfirmBridge;
import com.lw.mmce_advanced_builder_tool.common.integration.mmce.AdvancedBuilderCraftingRequester;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ContainerCraftConfirm.class)
public abstract class MixinContainerCraftConfirm implements AdvancedBuilderCraftingConfirmBridge {

    @Unique
    private AdvancedBuilderCraftingRequester abtRequester;

    @Override
    public void abt$setRequester(AdvancedBuilderCraftingRequester requester) {
        this.abtRequester = requester;
    }

    @Override
    public AdvancedBuilderCraftingRequester abt$getRequester() {
        return abtRequester;
    }

    @Redirect(
            method = "startJob",
            at = @At(value = "INVOKE", target = "Lappeng/api/networking/crafting/ICraftingGrid;submitJob(Lappeng/api/networking/crafting/ICraftingJob;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/IActionSource;)Lappeng/api/networking/crafting/ICraftingLink;"),
            remap = false
    )
    private ICraftingLink abtSubmitBuilderJob(ICraftingGrid craftingGrid, ICraftingJob job, ICraftingRequester originalRequester, ICraftingCPU cpu, boolean prioritizePower, IActionSource source) {
        ICraftingLink link = craftingGrid.submitJob(job, abtRequester == null ? originalRequester : abtRequester, cpu, prioritizePower, source);
        if (abtRequester != null) {
            abtRequester.abt$setCraftingLink(link);
        }
        return link;
    }
}
