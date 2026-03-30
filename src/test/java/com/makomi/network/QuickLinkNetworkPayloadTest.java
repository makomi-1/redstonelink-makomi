package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 快速连接工具网络 Payload 稳定契约测试。
 */
@Tag("stable-core")
class QuickLinkNetworkPayloadTest {
	/**
	 * 编辑器打开包编解码往返应保持缓存快照字段一致。
	 */
	@Test
	void openEditorPayloadCodecRoundTripShouldPreserveSnapshot() {
		QuickLinkToolData.Snapshot snapshot = new QuickLinkToolData.Snapshot(
			QuickLinkToolData.Mode.SERIAL,
			LinkNodeType.CORE,
			"1:5/7",
			"alpha"
		);
		QuickLinkNetwork.OpenQuickLinkEditorPayload original = new QuickLinkNetwork.OpenQuickLinkEditorPayload(snapshot);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.OpenQuickLinkEditorPayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.OpenQuickLinkEditorPayload decoded = QuickLinkNetwork.OpenQuickLinkEditorPayload.CODEC.decode(buffer);

		assertEquals(original.snapshot(), decoded.snapshot());
		assertEquals(QuickLinkNetwork.OpenQuickLinkEditorPayload.TYPE, decoded.type());
	}

	/**
	 * 应用结果回执应保留成功状态、翻译键和参数列表。
	 */
	@Test
	void applyResultPayloadCodecRoundTripShouldPreserveFields() {
		QuickLinkNetwork.QuickLinkApplyResultPayload original = new QuickLinkNetwork.QuickLinkApplyResultPayload(
			true,
			"message.redstonelink.quick_link.apply.done.core",
			List.of("3", "42")
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.QuickLinkApplyResultPayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.QuickLinkApplyResultPayload decoded = QuickLinkNetwork.QuickLinkApplyResultPayload.CODEC.decode(buffer);

		assertEquals(original.success(), decoded.success());
		assertEquals(original.messageKey(), decoded.messageKey());
		assertEquals(original.messageArgs(), decoded.messageArgs());
	}
}
