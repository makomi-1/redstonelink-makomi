package com.makomi.command.bench;

import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.argument.KeyValueTokenArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * bench/internal 结构化批量建链命令注册器。
 */
public final class BenchLinkCommandRegistry {
	private BenchLinkCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench link` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("link")
			.then(
				Commands
					.literal("apply")
					.then(
						Commands
							.literal("triggerSource")
							.then(
								Commands.argument("source_serials", SerialBatchArgumentType.serialBatch()).then(
									Commands
										.literal("core")
										.then(
											Commands.argument("target_serials", SerialBatchArgumentType.serialBatch())
												.then(
													Commands.literal("broadcast_all")
														.executes(
															context -> BenchLinkMappingApplySupport.executeApply(
																context,
																BenchLinkMappingApplySupport.MappingSpec.broadcastAll()
															)
														)
												)
												.then(
													Commands.literal("fan_in_first")
														.executes(
															context -> BenchLinkMappingApplySupport.executeApply(
																context,
																BenchLinkMappingApplySupport.MappingSpec.fanInFirst()
															)
														)
												)
												.then(
													Commands
														.literal("banded")
														.then(
															Commands.argument("fanout_spec", KeyValueTokenArgumentType.keyValueToken())
																.then(
																	Commands.argument("stride_spec", KeyValueTokenArgumentType.keyValueToken())
																		.then(
																			Commands.argument("offset_spec", KeyValueTokenArgumentType.keyValueToken())
																				.then(
																					Commands.argument("wrap_spec", KeyValueTokenArgumentType.keyValueToken())
																						.executes(BenchLinkCommandRegistry::executeBandedApply)
																				)
																		)
																)
														)
												)
										)
								)
							)
					)
			);
	}

	/**
	 * 执行带参数的 banded 结构化建链。
	 */
	private static int executeBandedApply(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Integer fanout = parseNamedIntSpec(source, StringArgumentType.getString(context, "fanout_spec"), "fanout");
		Integer stride = parseNamedIntSpec(source, StringArgumentType.getString(context, "stride_spec"), "stride");
		Integer offset = parseNamedIntSpec(source, StringArgumentType.getString(context, "offset_spec"), "offset");
		Boolean wrap = parseNamedBooleanSpec(source, StringArgumentType.getString(context, "wrap_spec"), "wrap");
		if (fanout == null || stride == null || offset == null || wrap == null) {
			return 0;
		}
		return BenchLinkMappingApplySupport.executeApply(
			context,
			BenchLinkMappingApplySupport.MappingSpec.banded(fanout, stride, offset, wrap)
		);
	}

	/**
	 * 解析 `key=value` 形态的整型参数。
	 */
	private static Integer parseNamedIntSpec(CommandSourceStack source, String rawSpec, String expectedKey) {
		String valueText = parseNamedSpecPrefix(source, rawSpec, expectedKey);
		if (valueText == null) {
			return null;
		}
		try {
			return Integer.parseInt(valueText);
		} catch (NumberFormatException exception) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid mapping spec: expected " + expectedKey + "=<int>, got " + rawSpec
				)
			);
			return null;
		}
	}

	/**
	 * 解析 `key=value` 形态的布尔参数。
	 */
	private static Boolean parseNamedBooleanSpec(CommandSourceStack source, String rawSpec, String expectedKey) {
		String valueText = parseNamedSpecPrefix(source, rawSpec, expectedKey);
		if (valueText == null) {
			return null;
		}
		String normalized = valueText.toLowerCase(Locale.ROOT);
		if ("true".equals(normalized)) {
			return true;
		}
		if ("false".equals(normalized)) {
			return false;
		}
		source.sendFailure(
			Component.literal(
				"[RedstoneLink/Bench] Invalid mapping spec: expected " + expectedKey + "=true|false, got " + rawSpec
			)
		);
		return null;
	}

	/**
	 * 校验 `key=value` 形态的前缀。
	 */
	private static String parseNamedSpecPrefix(CommandSourceStack source, String rawSpec, String expectedKey) {
		String prefix = expectedKey + "=";
		if (rawSpec != null && rawSpec.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return rawSpec.substring(prefix.length());
		}
		source.sendFailure(
			Component.literal(
				"[RedstoneLink/Bench] Invalid mapping spec order: expected " + prefix + "..., got " + rawSpec
			)
		);
		return null;
	}
}
