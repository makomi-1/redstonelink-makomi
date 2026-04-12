package com.makomi.client.screen;

import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import net.minecraft.network.chat.Component;

/**
 * GUI 头部文案与图标上下文解析支持。
 * <p>
 * 负责把稳定 token 或运行态类型映射为当前语言下的具体标题和图标语义，
 * 让各个 screen 只关心“当前上下文是什么”。
 * </p>
 */
final class GuiHeaderContextSupport {
	private GuiHeaderContextSupport() {
	}

	/**
	 * 构建 pairing 头部规格。
	 */
	static GuiHeaderRenderSupport.HeaderSpec pairingHeader(
		String displayContextToken,
		LinkNodeType sourceType,
		Component subtitle,
		int subtitleColor
	) {
		String normalizedToken = LinkGuiDisplayContext.normalizePairingContextToken(displayContextToken, sourceType);
		return new GuiHeaderRenderSupport.HeaderSpec(
			resolvePairingTitle(normalizedToken, sourceType),
			subtitle,
			subtitleColor,
			pairingContextIcon(normalizedToken)
		);
	}

	/**
	 * 构建快速连接工具头部规格。
	 */
	static GuiHeaderRenderSupport.HeaderSpec quickLinkHeader(QuickLinkToolData.Mode mode, LinkNodeType serialCacheType) {
		QuickLinkToolData.Mode normalizedMode = mode == null ? QuickLinkToolData.Mode.SERIAL : mode;
		Component subtitle = Component.translatable(
			"screen.redstonelink.quick_link.mode_line",
			Component.translatable(normalizedMode.translationKey())
		);
		return new GuiHeaderRenderSupport.HeaderSpec(
			Component.translatable("screen.redstonelink.quick_link.title"),
			subtitle,
			quickLinkSubtitleColor(normalizedMode),
			null
		);
	}

	/**
	 * quick-link 头部副标题颜色随模式主题切换。
	 */
	private static int quickLinkSubtitleColor(QuickLinkToolData.Mode mode) {
		return mode == QuickLinkToolData.Mode.CHANNEL ? 0xFFD7E7FF : 0xFFFFD5D5;
	}

	/**
	 * 构建过滤器头部规格。
	 */
	static GuiHeaderRenderSupport.HeaderSpec filterHeader(LinkFilterKind filterKind, int subtitleColor) {
		LinkFilterKind normalizedKind = filterKind == null ? LinkFilterKind.SEND : filterKind;
		String titleKey = normalizedKind == LinkFilterKind.RECEIVE
			? "screen.redstonelink.link_filter.receive.title"
			: "screen.redstonelink.link_filter.send.title";
		return new GuiHeaderRenderSupport.HeaderSpec(
			Component.translatable(titleKey),
			Component.translatable(
				"screen.redstonelink.link_filter.service_line",
				LinkNodeSemantics.toSemanticName(normalizedKind.servicedNodeType())
			),
			subtitleColor,
			new GuiHeaderRenderSupport.HeaderIcon(GuiHeaderRenderSupport.IconKind.FILTER, -15)
		);
	}

	/**
	 * 构建状态面板头部规格。
	 */
	static GuiHeaderRenderSupport.HeaderSpec statePanelHeader(LinkNodeType currentType) {
		return new GuiHeaderRenderSupport.HeaderSpec(
			Component.translatable("screen.redstonelink.state_panel.title"),
			Component.empty(),
			0xFFFFFFFF,
			null
		);
	}

	/**
	 * 解析 pairing 标题；具体节点复用现有物品国际化键，兜底仍回退为通用 pairing 标题。
	 */
	private static Component resolvePairingTitle(String normalizedToken, LinkNodeType sourceType) {
		if (normalizedToken == null || normalizedToken.isBlank()) {
			return genericPairingTitle(sourceType);
		}
		return switch (normalizedToken) {
			case LinkGuiDisplayContext.TRIGGER_SOURCE, LinkGuiDisplayContext.CORE -> genericPairingTitle(sourceType);
			case LinkGuiDisplayContext.LINK_REDSTONE_CORE_TRANSPARENT -> Component.translatable(
				"screen.redstonelink.pairing.variant.link_redstone_core_transparent_short"
			);
			case LinkGuiDisplayContext.LINK_REDSTONE_DUST_CORE_TRANSPARENT -> Component.translatable(
				"screen.redstonelink.pairing.variant.link_redstone_dust_core_transparent_short"
			);
			default -> Component.translatable("item.redstonelink." + normalizedToken);
		};
	}

	/**
	 * 解析通用 pairing 标题。
	 */
	private static Component genericPairingTitle(LinkNodeType sourceType) {
		return Component.translatable(
			sourceType == LinkNodeType.CORE
				? "screen.redstonelink.core_pairing.title"
				: "screen.redstonelink.trigger_source_pairing.title"
		);
	}

	/**
	 * 按具体显示上下文决定是否存在可复用图标；无对应左右图标时不渲染。
	 */
	private static GuiHeaderRenderSupport.HeaderIcon pairingContextIcon(String normalizedToken) {
		if (isCorePairingContextToken(normalizedToken)) {
			return nodeTypeIcon(LinkNodeType.CORE);
		}
		if (isTriggerSourcePairingContextToken(normalizedToken)) {
			return nodeTypeIcon(LinkNodeType.TRIGGER_SOURCE);
		}
		return null;
	}

	/**
	 * 判断当前配对上下文是否属于 core 图标组。
	 */
	private static boolean isCorePairingContextToken(String normalizedToken) {
		if (normalizedToken == null || normalizedToken.isBlank()) {
			return false;
		}
		return switch (normalizedToken) {
			case LinkGuiDisplayContext.CORE,
				LinkGuiDisplayContext.LINK_REDSTONE_CORE,
				LinkGuiDisplayContext.LINK_REDSTONE_CORE_TRANSPARENT,
				LinkGuiDisplayContext.LINK_REDSTONE_DUST_CORE,
				LinkGuiDisplayContext.LINK_REDSTONE_DUST_CORE_TRANSPARENT -> true;
			default -> false;
		};
	}

	/**
	 * 判断当前配对上下文是否属于 triggerSource 图标组。
	 */
	private static boolean isTriggerSourcePairingContextToken(String normalizedToken) {
		if (normalizedToken == null || normalizedToken.isBlank()) {
			return false;
		}
		return switch (normalizedToken) {
			case LinkGuiDisplayContext.TRIGGER_SOURCE,
				LinkGuiDisplayContext.LINK_TOGGLE_BUTTON,
				LinkGuiDisplayContext.LINK_PUSH_BUTTON,
				LinkGuiDisplayContext.LINK_SYNC_LEVER,
				LinkGuiDisplayContext.LINK_TOGGLE_EMITTER,
				LinkGuiDisplayContext.LINK_PULSE_EMITTER,
				LinkGuiDisplayContext.LINK_SYNC_EMITTER,
				LinkGuiDisplayContext.REDSTONELINK_TOGGLE_LINKER,
				LinkGuiDisplayContext.REDSTONELINK_PULSE_LINKER,
				LinkGuiDisplayContext.REDSTONELINK_SYNC_LINKER -> true;
			default -> false;
		};
	}

	/**
	 * 按节点类型选择头部图标。
	 */
	private static GuiHeaderRenderSupport.HeaderIcon nodeTypeIcon(LinkNodeType nodeType) {
		if (nodeType == LinkNodeType.CORE) {
			return new GuiHeaderRenderSupport.HeaderIcon(GuiHeaderRenderSupport.IconKind.CORE);
		}
		return new GuiHeaderRenderSupport.HeaderIcon(GuiHeaderRenderSupport.IconKind.TRIGGER_SOURCE);
	}
}
