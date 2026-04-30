package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器网络 Payload 的稳定契约测试。
 */
@Tag("stable-core")
class RepeaterNetworkPayloadTest {

	/**
	 * 打开编辑器回包应按输入/输出表达式补齐缺失展示文本。
	 */
	@Test
	void openEditorPayloadShouldNormalizeDisplayTextsAgainstExpressions() {
		RepeaterNetwork.OpenRepeaterEditorPayload payload = new RepeaterNetwork.OpenRepeaterEditorPayload(
			LinkFilterEditorTargetKind.BLOCK_ENTITY,
			"minecraft:overworld",
			18L,
			-1,
			42L,
			"中继A",
			new RepeaterConfigSnapshot("1/3", "7", RepeaterDelay.ofTicks(5)),
			List.of("门厅(#1)"),
			List.of(),
			9L,
			11L
		);

		assertEquals(List.of("#1", "#3"), payload.inputDisplayTexts());
		assertEquals(List.of("#7"), payload.outputDisplayTexts());
	}

	/**
	 * 打开编辑器回包编解码往返应保留展示文本列表。
	 */
	@Test
	void openEditorPayloadCodecRoundTripShouldPreserveDisplayTexts() {
		RepeaterNetwork.OpenRepeaterEditorPayload original = new RepeaterNetwork.OpenRepeaterEditorPayload(
			LinkFilterEditorTargetKind.BLOCK_ENTITY,
			"minecraft:overworld",
			99L,
			-1,
			73L,
			"转发器总站",
			new RepeaterConfigSnapshot("2/4", "8/9", RepeaterDelay.ONE_TICK),
			List.of("门A(#2)", "#4"),
			List.of("核心甲(#8)", "核心乙(#9)"),
			15L,
			16L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		RepeaterNetwork.OpenRepeaterEditorPayload.CODEC.encode(buffer, original);
		RepeaterNetwork.OpenRepeaterEditorPayload decoded = RepeaterNetwork.OpenRepeaterEditorPayload.CODEC.decode(buffer);

		assertEquals(original.targetKind(), decoded.targetKind());
		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.selectedSlot(), decoded.selectedSlot());
		assertEquals(original.serial(), decoded.serial());
		assertEquals(original.displayAlias(), decoded.displayAlias());
		assertEquals(original.configSnapshot(), decoded.configSnapshot());
		assertEquals(original.inputDisplayTexts(), decoded.inputDisplayTexts());
		assertEquals(original.outputDisplayTexts(), decoded.outputDisplayTexts());
		assertEquals(original.expectedCoreRevision(), decoded.expectedCoreRevision());
		assertEquals(original.expectedSourceRevision(), decoded.expectedSourceRevision());
	}

	/**
	 * 保存转发器回包编解码往返仍应保持原有字段稳定。
	 */
	@Test
	void saveRepeaterPayloadCodecRoundTripShouldPreserveFields() {
		RepeaterNetwork.SaveRepeaterPayload original = new RepeaterNetwork.SaveRepeaterPayload(
			LinkFilterEditorTargetKind.HELD_MAIN_HAND,
			"",
			0L,
			2,
			91L,
			"背包转发器",
			new RepeaterConfigSnapshot("5", "11/12", RepeaterDelay.ofTicks(7)),
			21L,
			22L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		RepeaterNetwork.SaveRepeaterPayload.CODEC.encode(buffer, original);
		RepeaterNetwork.SaveRepeaterPayload decoded = RepeaterNetwork.SaveRepeaterPayload.CODEC.decode(buffer);

		assertEquals(original.targetKind(), decoded.targetKind());
		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.selectedSlot(), decoded.selectedSlot());
		assertEquals(original.serial(), decoded.serial());
		assertEquals(original.displayAlias(), decoded.displayAlias());
		assertEquals(original.configSnapshot(), decoded.configSnapshot());
		assertEquals(original.expectedCoreRevision(), decoded.expectedCoreRevision());
		assertEquals(original.expectedSourceRevision(), decoded.expectedSourceRevision());
	}
}
