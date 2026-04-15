package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterSignalMode;
import com.makomi.data.LinkFilterSignalThresholdSource;
import com.makomi.data.LinkFilterTargetMode;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 过滤器网络 Payload 稳定契约测试。
 */
@Tag("stable-core")
class LinkFilterNetworkPayloadTest {
	/**
	 * 打开编辑器回包编解码往返应保留位置、种类与配置字段。
	 */
	@Test
	void openEditorPayloadCodecRoundTripShouldPreserveFields() {
		LinkFilterNetwork.OpenFilterEditorPayload original = new LinkFilterNetwork.OpenFilterEditorPayload(
			LinkFilterEditorTargetKind.BLOCK_ENTITY,
			"minecraft:overworld",
			42L,
			-1,
			LinkFilterKind.RECEIVE,
			"南厅过滤器",
			new LinkFilterConfigSnapshot(
				"1:5/9",
				LinkFilterTargetMode.SERIAL,
				0L,
				LinkFilterNodeSetMode.WHITELIST,
				LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
				7,
				LinkFilterSignalMode.UPPER_BOUND
			)
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		LinkFilterNetwork.OpenFilterEditorPayload.CODEC.encode(buffer, original);
		LinkFilterNetwork.OpenFilterEditorPayload decoded = LinkFilterNetwork.OpenFilterEditorPayload.CODEC.decode(buffer);

		assertEquals(original.targetKind(), decoded.targetKind());
		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.selectedSlot(), decoded.selectedSlot());
		assertEquals(original.filterKind(), decoded.filterKind());
		assertEquals(original.displayAlias(), decoded.displayAlias());
		assertEquals(original.configSnapshot(), decoded.configSnapshot());
	}

	/**
	 * 保存请求编解码往返应保留过滤器配置。
	 */
	@Test
	void savePayloadCodecRoundTripShouldPreserveFields() {
		LinkFilterNetwork.SaveFilterPayload original = new LinkFilterNetwork.SaveFilterPayload(
			LinkFilterEditorTargetKind.HELD_MAIN_HAND,
			"",
			0L,
			3,
			LinkFilterKind.SEND,
			"西厅过滤器",
			new LinkFilterConfigSnapshot(
				"3/7:9",
				LinkFilterTargetMode.CHANNEL,
				88L,
				LinkFilterNodeSetMode.BLOCKLIST,
				LinkFilterSignalThresholdSource.FIXED_INPUT,
				11,
				LinkFilterSignalMode.LOWER_BOUND
			)
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		LinkFilterNetwork.SaveFilterPayload.CODEC.encode(buffer, original);
		LinkFilterNetwork.SaveFilterPayload decoded = LinkFilterNetwork.SaveFilterPayload.CODEC.decode(buffer);

		assertEquals(original.targetKind(), decoded.targetKind());
		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.selectedSlot(), decoded.selectedSlot());
		assertEquals(original.filterKind(), decoded.filterKind());
		assertEquals(original.displayAlias(), decoded.displayAlias());
		assertEquals(original.configSnapshot(), decoded.configSnapshot());
	}

	/**
	 * 反馈回执编解码往返应保留成功态、翻译键与参数列表。
	 */
	@Test
	void feedbackPayloadCodecRoundTripShouldPreserveFields() {
		LinkFilterNetwork.FilterFeedbackPayload original = new LinkFilterNetwork.FilterFeedbackPayload(
			true,
			"message.redstonelink.link_filter.saved",
			List.of("a", "b")
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		LinkFilterNetwork.FilterFeedbackPayload.CODEC.encode(buffer, original);
		LinkFilterNetwork.FilterFeedbackPayload decoded = LinkFilterNetwork.FilterFeedbackPayload.CODEC.decode(buffer);

		assertEquals(original.success(), decoded.success());
		assertEquals(original.messageKey(), decoded.messageKey());
		assertEquals(original.messageArgs(), decoded.messageArgs());
	}

	/**
	 * 保存请求在解包阶段应拒绝超过显式上限的表达式。
	 */
	@Test
	void savePayloadCodecShouldRejectTooLongSerialExpression() {
		String tooLongExpression = "1".repeat(RedstoneLinkConfig.command().linkSetMaxInputLength() + 1);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeUtf("block_entity");
		buffer.writeUtf("minecraft:overworld");
		buffer.writeLong(42L);
		buffer.writeInt(-1);
		buffer.writeUtf("send");
		buffer.writeUtf("别名");
		buffer.writeUtf(tooLongExpression);
		buffer.writeUtf("serial");
		buffer.writeVarLong(0L);
		buffer.writeUtf("disabled");
		buffer.writeUtf("fixed_input");
		buffer.writeVarInt(15);
		buffer.writeUtf("disabled");

		assertThrows(RuntimeException.class, () -> LinkFilterNetwork.SaveFilterPayload.CODEC.decode(buffer));
	}
}
