package com.makomi.command.input;

import com.makomi.command.CommandTreeSupport;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.input.InputEndpointKind;
import com.makomi.data.input.InputWaveformSpec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 输入命令公共解析与校验支持。
 * <p>
 * 供普通 `input` 命令与 bench/internal 输入命令共用，避免重复维护：
 * 1. 目标序号解析与在线性校验；
 * 2. 自定义序列语法校验；
 * 3. 端点到节点类型的语义映射。
 * </p>
 */
public final class InputCommandSupport {
	private InputCommandSupport() {
	}

	/**
	 * 解析并校验输入 job 的目标序列号集合。
	 */
	public static List<Long> parseAndValidateTargetSerials(
		CommandSourceStack source,
		String rawSerials,
		InputEndpointKind endpointKind,
		int maxTargets
	) {
		var parseResult = com.makomi.util.SerialParseUtil.parseTargets(rawSerials, maxTargets);
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_tokens",
					String.join(", ", parseResult.invalidEntries())
				)
			);
			return null;
		}
		if (parseResult.exceedLimit()) {
			source.sendFailure(Component.translatable("message.redstonelink.input.too_many_targets", maxTargets));
			return null;
		}
		if (parseResult.targets().isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.input.empty_targets"));
			return null;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.batch_serials_deduped",
					CommandTreeSupport.formatSerialCollection(parseResult.duplicateEntries())
				),
				false
			);
		}
		LinkNodeType targetType = resolveInputTargetNodeType(endpointKind);
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		List<Long> invalidSerials = new ArrayList<>();
		List<Long> sortedTargets = parseResult.targets().stream().sorted().toList();
		for (Long serial : sortedTargets) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			boolean active = savedData.isSerialAllocated(targetType, serial) && !savedData.isSerialRetired(targetType, serial);
			if (!active) {
				invalidSerials.add(serial);
			}
		}
		if (!invalidSerials.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.input.invalid_serials",
					CommandTreeSupport.typeCommandName(targetType),
					CommandTreeSupport.formatSerialCollection(invalidSerials)
				)
			);
			return null;
		}
		return List.copyOf(sortedTargets);
	}

	/**
	 * 解析并校验自定义序列文本。
	 */
	public static List<Integer> parseAndValidateCustomSequence(
		CommandSourceStack source,
		String rawSequence,
		int maxSequenceRawLength
	) {
		if (rawSequence != null && rawSequence.length() > maxSequenceRawLength) {
			source.sendFailure(Component.translatable("message.redstonelink.input.invalid_sequence", rawSequence));
			return null;
		}
		try {
			return InputWaveformSpec.parseCustomSequence(rawSequence);
		} catch (IllegalArgumentException ex) {
			source.sendFailure(Component.translatable("message.redstonelink.input.invalid_sequence", rawSequence));
			return null;
		}
	}

	/**
	 * 解析输入端点所对应的节点类型。
	 */
	public static LinkNodeType resolveInputTargetNodeType(InputEndpointKind endpointKind) {
		return endpointKind == InputEndpointKind.CORE_SYNC_DIRECT ? LinkNodeType.CORE : LinkNodeType.TRIGGER_SOURCE;
	}
}
