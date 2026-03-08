package com.makomi.mixin.plugin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin 条件加载插件：
 * 仅在检测到 lithium 时启用 lithium 兼容注入，避免对无锂环境引入额外耦合。
 */
public class RedstoneLinkMixinPlugin implements IMixinConfigPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger("RedstoneLink/MixinRoute");
	private static final String VANILLA_MIXIN = "com.makomi.mixin.RedStoneWireBlockMixin";
	private static final String LITHIUM_MIXIN = "com.makomi.mixin.RedStoneWireBlockLithiumMixin";
	private static boolean routeLogged;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!VANILLA_MIXIN.equals(mixinClassName) && !LITHIUM_MIXIN.equals(mixinClassName)) {
			return true;
		}

		boolean hasLithium = FabricLoader.getInstance().isModLoaded("lithium");
		logRouteOnce(hasLithium);
		if (VANILLA_MIXIN.equals(mixinClassName)) {
			return !hasLithium;
		}
		if (LITHIUM_MIXIN.equals(mixinClassName)) {
			return hasLithium;
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	/**
	 * 启动阶段只打印一次路由选择，便于外部快速确认“有锂/无锂”注入路径。
	 */
	private static void logRouteOnce(boolean hasLithium) {
		if (routeLogged) {
			return;
		}
		routeLogged = true;
		LOGGER.info(
			"[RedstoneLink/MixinRoute] lithiumLoaded={}, applyVanillaMixin={}, applyLithiumMixin={}",
			hasLithium,
			!hasLithium,
			hasLithium
		);
	}
}
