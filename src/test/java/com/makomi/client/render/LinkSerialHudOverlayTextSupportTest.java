package com.makomi.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterTargetMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 近外显文案映射契约测试。
 */
@Tag("stable-core")
class LinkSerialHudOverlayTextSupportTest {
	/**
	 * 跨区块身份应稳定映射到对应翻译键。
	 */
	@Test
	void resolveCrossChunkIdentityMessageKeyShouldMapAllStates() {
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_normal",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity.NORMAL)
		);
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_force_load",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity.FORCE_LOAD)
		);
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_resident",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity.RESIDENT)
		);
	}

	/**
	 * 空身份应回退到普通身份翻译键。
	 */
	@Test
	void resolveCrossChunkIdentityMessageKeyShouldFallbackToNormalWhenNull() {
		assertEquals(
			"hud.redstonelink.near_overlay.crosschunk_normal",
			LinkSerialHudOverlayTextSupport.resolveCrossChunkIdentityMessageKey(null)
		);
	}

	/**
	 * 频道模式快照应触发近外显频道行。
	 */
	@Test
	void shouldRenderChannelLineShouldFollowConnectionMode() {
		assertTrue(
			LinkSerialHudOverlayTextSupport.shouldRenderChannelLine(
				new LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot(
					100L,
					java.util.List.of(1L, 2L),
					java.util.List.of("#1", "#2"),
					LinkConnectionMode.CHANNEL,
					9L,
					CrossChunkNodeIdentity.NORMAL
				)
			)
		);
		assertFalse(
			LinkSerialHudOverlayTextSupport.shouldRenderChannelLine(
				new LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot(
					100L,
					java.util.List.of(1L, 2L),
					java.util.List.of("#1", "#2"),
					LinkConnectionMode.SERIAL,
					0L,
					CrossChunkNodeIdentity.NORMAL
				)
			)
		);
	}

	/**
	 * 频道值文本应对非法频道回退到占位符。
	 */
	@Test
	void resolveChannelValueTextShouldFallbackForInvalidChannel() {
		assertEquals("12", LinkSerialHudOverlayTextSupport.resolveChannelValueText(12L));
		assertEquals("-", LinkSerialHudOverlayTextSupport.resolveChannelValueText(0L));
	}

	/**
	 * 过滤器近外显标题应在存在别名时附加别名，空别名时保持标题本身。
	 */
	@Test
	void composeFilterTitleTextShouldAppendAliasWhenPresent() {
		assertEquals("发送过滤器 门厅A", LinkSerialHudOverlayTextSupport.composeFilterTitleText("发送过滤器", " 门厅A "));
		assertEquals("发送过滤器", LinkSerialHudOverlayTextSupport.composeFilterTitleText("发送过滤器", "   "));
	}

	/**
	 * 过滤器频道模式应使用频道行标签，序号模式应继续使用节点集标签。
	 */
	@Test
	void resolveFilterTargetLineTranslationKeyShouldFollowTargetMode() {
		assertEquals(
			"hud.redstonelink.near_overlay.channel_line",
			LinkSerialHudOverlayTextSupport.resolveFilterTargetLineTranslationKey(
				new LinkFilterConfigSnapshot("", LinkFilterTargetMode.CHANNEL, 12L, null, null, 15, null)
			)
		);
		assertEquals(
			"hud.redstonelink.near_overlay.filter_node_set_line",
			LinkSerialHudOverlayTextSupport.resolveFilterTargetLineTranslationKey(
				new LinkFilterConfigSnapshot("1/3", LinkFilterTargetMode.SERIAL, 0L, null, null, 15, null)
			)
		);
	}

	/**
	 * 过滤器/区块激活器节点集 HUD 应优先使用服务端同步的别名展示文本。
	 */
	@Test
	void buildCurrentLinksTextShouldPreferSyncedDisplayTexts() {
		assertEquals(
			"中控A(#3)/#7",
			LinkSerialHudOverlayTextSupport.buildCurrentLinksText(
				java.util.List.of(3L, 7L),
				java.util.List.of("中控A(#3)", "#7"),
				64,
				String::length
			)
		);
	}

	/**
	 * 展示文本缺失或数量不匹配时仍应回退到 `#序号`，兼容旧同步状态。
	 */
	@Test
	void buildCurrentLinksTextShouldFallbackToSerialsWhenDisplayTextsMissing() {
		assertEquals(
			"#3/#7",
			LinkSerialHudOverlayTextSupport.buildCurrentLinksText(
				java.util.List.of(3L, 7L),
				java.util.List.of("中控A(#3)"),
				64,
				String::length
			)
		);
	}

	/**
	 * 第三形态连线 HUD 应优先显示规范化后的展示文本，并在缺失时回退到序号。
	 */
	@Test
	void buildVisualizedHoverOverlayLinesShouldNormalizeDisplayText() {
		assertEquals(
			java.util.List.of("[中控A(#18)]"),
			LinkSerialHudOverlayTextSupport.buildVisualizedHoverOverlayLines(" 中控A(#18) ", 18L)
		);
		assertEquals(
			java.util.List.of("[#18]"),
			LinkSerialHudOverlayTextSupport.buildVisualizedHoverOverlayLines("   ", 18L)
		);
	}
}
