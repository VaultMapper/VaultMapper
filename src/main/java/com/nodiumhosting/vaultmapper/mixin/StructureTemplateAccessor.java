package com.nodiumhosting.vaultmapper.mixin;

import iskallia.vault.core.world.data.entity.PartialEntity;
import iskallia.vault.core.world.data.tile.PartialTile;
import iskallia.vault.core.world.data.tile.TilePredicate;
import iskallia.vault.core.world.template.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Mixin(value = StructureTemplate.class, remap = false)
public interface StructureTemplateAccessor {
    @Accessor
    static ExecutorService getLAZY_LOADING_EXECUTOR() {
        throw new IllegalStateException("Mixin failed to apply");
    }

    @Accessor
    Map<TilePredicate, List<PartialTile>> getTiles();

    @Accessor
    Map<TilePredicate, List<PartialEntity>> getEntities();
}
