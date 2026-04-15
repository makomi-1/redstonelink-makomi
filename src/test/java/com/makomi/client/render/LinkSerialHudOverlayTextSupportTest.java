package com.makomi.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkConnectionMode;
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
}
