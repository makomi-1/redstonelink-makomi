package com.makomi.api.v1.model;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 节点快照。
 */
public record NodeSnapshot(long serial, ApiNodeType nodeType, ResourceLocation dimensionId, BlockPos pos) {
	public NodeSnapshot {
		nodeType = Objects.requireNonNull(nodeType, "nodeType");
		dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
		pos = Objects.requireNonNull(pos, "pos").immutable();
	}
}

