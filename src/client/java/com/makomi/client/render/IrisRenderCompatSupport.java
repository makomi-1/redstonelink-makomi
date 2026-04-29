package com.makomi.client.render;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Iris 渲染兼容判定支撑。
 * <p>
 * 当前仅按“是否加载了 Iris 模组”切换兼容分支，
 * 避免把已经稳定的 vanilla 路径一起改动。
 * </p>
 */
public final class IrisRenderCompatSupport {
	private IrisRenderCompatSupport() {
	}

	/**
	 * 判断当前客户端是否需要启用 Iris 兼容渲染分支。
	 */
	public static boolean shouldUseCompatibilityBranch() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}
}
