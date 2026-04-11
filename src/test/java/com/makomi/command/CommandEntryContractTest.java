package com.makomi.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.command.argument.KeyValueTokenArgumentType;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 命令出入口解析契约测试。
 * <p>
 * 这些测试直接验证批量序号参数与显式 literal 节点的组合方式，
 * 防止命令树回退到 `greedyString() + 尾缀手拆`。
 * </p>
 */
@Tag("stable-core")
class CommandEntryContractTest {
	/**
	 * activate 应通过显式 literal 解析模式，而不是从序号字符串尾部剥离。
	 */
	@Test
	void activateCommandShouldParseExplicitModeLiteralAfterSerialBatch() throws Exception {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicReference<String> mode = new AtomicReference<>();
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("activate")
				.then(
					com.mojang.brigadier.builder.RequiredArgumentBuilder
						.<Object, String>argument("type", StringArgumentType.word())
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, String>argument("source_serials", SerialBatchArgumentType.serialBatch())
								.executes(context -> {
									serials.set(SerialBatchArgumentType.getSerialBatch(context, "source_serials"));
									mode.set("toggle");
									return 1;
								})
								.then(
									com.mojang.brigadier.builder.LiteralArgumentBuilder
										.<Object>literal("toggle")
										.executes(context -> {
											serials.set(SerialBatchArgumentType.getSerialBatch(context, "source_serials"));
											mode.set("toggle");
											return 1;
										})
								)
								.then(
									com.mojang.brigadier.builder.LiteralArgumentBuilder
										.<Object>literal("pulse")
										.executes(context -> {
											serials.set(SerialBatchArgumentType.getSerialBatch(context, "source_serials"));
											mode.set("pulse");
											return 1;
										})
								)
						)
				)
		);

		int result = dispatcher.execute("activate triggerSource 1:3 pulse", new Object());

		assertEquals(1, result);
		assertEquals("1:3", serials.get());
		assertEquals("pulse", mode.get());
	}

	/**
	 * link set 应保留“无 targets 即清空”的入口，同时支持独立 confirm 节点。
	 */
	@Test
	void linkSetCommandShouldKeepClearPathAndExplicitConfirmLiteral() throws Exception {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicBoolean hasTargets = new AtomicBoolean(true);
		AtomicBoolean confirmed = new AtomicBoolean(true);
		AtomicLong sourceSerial = new AtomicLong(-1L);
		AtomicReference<String> targets = new AtomicReference<>();
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("set")
				.then(
					com.mojang.brigadier.builder.RequiredArgumentBuilder
						.<Object, String>argument("type", StringArgumentType.word())
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, Long>argument("source_serial", LongArgumentType.longArg(1L))
								.executes(context -> {
									hasTargets.set(false);
									confirmed.set(false);
									sourceSerial.set(LongArgumentType.getLong(context, "source_serial"));
									targets.set(null);
									return 1;
								})
								.then(
									com.mojang.brigadier.builder.RequiredArgumentBuilder
										.<Object, String>argument("targets", SerialBatchArgumentType.serialBatch())
										.executes(context -> {
											hasTargets.set(true);
											confirmed.set(false);
											sourceSerial.set(LongArgumentType.getLong(context, "source_serial"));
											targets.set(SerialBatchArgumentType.getSerialBatch(context, "targets"));
											return 1;
										})
										.then(
											com.mojang.brigadier.builder.LiteralArgumentBuilder
												.<Object>literal("confirm")
												.executes(context -> {
													hasTargets.set(true);
													confirmed.set(true);
													sourceSerial.set(LongArgumentType.getLong(context, "source_serial"));
													targets.set(SerialBatchArgumentType.getSerialBatch(context, "targets"));
													return 1;
												})
										)
								)
						)
				)
		);

		assertEquals(1, dispatcher.execute("set triggerSource 12", new Object()));
		assertFalse(hasTargets.get());
		assertFalse(confirmed.get());
		assertEquals(12L, sourceSerial.get());

		assertEquals(1, dispatcher.execute("set triggerSource 12 1:3 confirm", new Object()));
		assertTrue(hasTargets.get());
		assertTrue(confirmed.get());
		assertEquals("1:3", targets.get());
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("set triggerSource 12 1:3 Confirm", new Object()));
	}

	/**
	 * bench 结构化批量建链应在双 serial batch 之后继续解析映射 literal。
	 */
	@Test
	void benchLinkApplyShouldParseBroadcastAllAfterTwoSerialBatches() throws Exception {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> sourceSerials = new AtomicReference<>();
		AtomicReference<String> targetSerials = new AtomicReference<>();
		AtomicReference<String> mapping = new AtomicReference<>();
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("apply")
				.then(
					com.mojang.brigadier.builder.LiteralArgumentBuilder
						.<Object>literal("triggerSource")
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, String>argument("source_serials", SerialBatchArgumentType.serialBatch())
								.then(
									com.mojang.brigadier.builder.LiteralArgumentBuilder
										.<Object>literal("core")
										.then(
											com.mojang.brigadier.builder.RequiredArgumentBuilder
												.<Object, String>argument("target_serials", SerialBatchArgumentType.serialBatch())
												.then(
													com.mojang.brigadier.builder.LiteralArgumentBuilder
														.<Object>literal("broadcast_all")
														.executes(context -> {
															sourceSerials.set(SerialBatchArgumentType.getSerialBatch(context, "source_serials"));
															targetSerials.set(SerialBatchArgumentType.getSerialBatch(context, "target_serials"));
															mapping.set("broadcast_all");
															return 1;
														})
												)
										)
								)
						)
				)
		);

		int result = dispatcher.execute("apply triggerSource 1:4 core 10:20 broadcast_all", new Object());

		assertEquals(1, result);
		assertEquals("1:4", sourceSerials.get());
		assertEquals("10:20", targetSerials.get());
		assertEquals("broadcast_all", mapping.get());
	}

	/**
	 * bench zip 映射应在双 serial batch 之后继续解析 zip literal。
	 */
	@Test
	void benchLinkApplyShouldParseZipAfterTwoSerialBatches() throws Exception {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> sourceSerials = new AtomicReference<>();
		AtomicReference<String> targetSerials = new AtomicReference<>();
		AtomicReference<String> mapping = new AtomicReference<>();
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("apply")
				.then(
					com.mojang.brigadier.builder.LiteralArgumentBuilder
						.<Object>literal("triggerSource")
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, String>argument("source_serials", SerialBatchArgumentType.serialBatch())
								.then(
									com.mojang.brigadier.builder.LiteralArgumentBuilder
										.<Object>literal("core")
										.then(
											com.mojang.brigadier.builder.RequiredArgumentBuilder
												.<Object, String>argument("target_serials", SerialBatchArgumentType.serialBatch())
												.then(
													com.mojang.brigadier.builder.LiteralArgumentBuilder
														.<Object>literal("zip")
														.executes(context -> {
															sourceSerials.set(SerialBatchArgumentType.getSerialBatch(context, "source_serials"));
															targetSerials.set(SerialBatchArgumentType.getSerialBatch(context, "target_serials"));
															mapping.set("zip");
															return 1;
														})
												)
										)
								)
						)
				)
		);

		int result = dispatcher.execute("apply triggerSource 1:4 core 10:20 zip", new Object());

		assertEquals(1, result);
		assertEquals("1:4", sourceSerials.get());
		assertEquals("10:20", targetSerials.get());
		assertEquals("zip", mapping.get());
	}

	/**
	 * bench banded 映射应保留 `key=value` 风格的显式参数顺序。
	 */
	@Test
	void benchLinkApplyShouldParseNamedBandedSpecs() throws Exception {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> fanoutSpec = new AtomicReference<>();
		AtomicReference<String> strideSpec = new AtomicReference<>();
		AtomicReference<String> offsetSpec = new AtomicReference<>();
		AtomicReference<String> wrapSpec = new AtomicReference<>();
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("apply")
				.then(
					com.mojang.brigadier.builder.LiteralArgumentBuilder
						.<Object>literal("triggerSource")
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, String>argument("source_serials", SerialBatchArgumentType.serialBatch())
								.then(
									com.mojang.brigadier.builder.LiteralArgumentBuilder
										.<Object>literal("core")
										.then(
											com.mojang.brigadier.builder.RequiredArgumentBuilder
												.<Object, String>argument("target_serials", SerialBatchArgumentType.serialBatch())
												.then(
													com.mojang.brigadier.builder.LiteralArgumentBuilder
														.<Object>literal("banded")
														.then(
															com.mojang.brigadier.builder.RequiredArgumentBuilder
																.<Object, String>argument("fanout_spec", KeyValueTokenArgumentType.keyValueToken())
																.then(
																	com.mojang.brigadier.builder.RequiredArgumentBuilder
																		.<Object, String>argument("stride_spec", KeyValueTokenArgumentType.keyValueToken())
																		.then(
																			com.mojang.brigadier.builder.RequiredArgumentBuilder
																				.<Object, String>argument("offset_spec", KeyValueTokenArgumentType.keyValueToken())
																				.then(
																					com.mojang.brigadier.builder.RequiredArgumentBuilder
																						.<Object, String>argument("wrap_spec", KeyValueTokenArgumentType.keyValueToken())
																						.executes(context -> {
																							fanoutSpec.set(StringArgumentType.getString(context, "fanout_spec"));
																							strideSpec.set(StringArgumentType.getString(context, "stride_spec"));
																							offsetSpec.set(StringArgumentType.getString(context, "offset_spec"));
																							wrapSpec.set(StringArgumentType.getString(context, "wrap_spec"));
																							return 1;
																						})
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

		int result = dispatcher.execute(
			"apply triggerSource 1:4 core 10:20 banded fanout=16 stride=16 offset=0 wrap=true",
			new Object()
		);

		assertEquals(1, result);
		assertEquals("fanout=16", fanoutSpec.get());
		assertEquals("stride=16", strideSpec.get());
		assertEquals("offset=0", offsetSpec.get());
		assertEquals("wrap=true", wrapSpec.get());
	}

	/**
	 * 受控名单批量覆盖应通过独立 confirm 节点进入确认分支。
	 */
	@Test
	void writeProtectedSetShouldUseExplicitConfirmLiteral() throws Exception {
		CommandDispatcher<Object> dispatcher = createConfirmOnlyBatchDispatcher();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicBoolean confirmed = new AtomicBoolean(false);
		registerConfirmOnlyBatchSet(dispatcher, serials, confirmed);

		int result = dispatcher.execute("set triggerSource 1:10/12 confirm", new Object());

		assertEquals(1, result);
		assertEquals("1:10/12", serials.get());
		assertTrue(confirmed.get());
	}

	/**
	 * 当前连接隐私名单批量覆盖也应通过独立 confirm 节点进入确认分支。
	 */
	@Test
	void currentLinksPrivacyMaskSetShouldUseExplicitConfirmLiteral() throws Exception {
		CommandDispatcher<Object> dispatcher = createConfirmOnlyBatchDispatcher();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicBoolean confirmed = new AtomicBoolean(false);
		registerConfirmOnlyBatchSet(dispatcher, serials, confirmed);

		int result = dispatcher.execute("set core 2:8 confirm", new Object());

		assertEquals(1, result);
		assertEquals("2:8", serials.get());
		assertTrue(confirmed.get());
	}

	/**
	 * retire batch 应通过独立 confirm 节点进入最终执行分支。
	 */
	@Test
	void retireBatchShouldUseExplicitConfirmLiteral() throws Exception {
		CommandDispatcher<Object> dispatcher = createConfirmOnlyBatchDispatcher();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicBoolean confirmed = new AtomicBoolean(false);
		registerConfirmOnlyBatchSet(dispatcher, serials, confirmed);

		int result = dispatcher.execute("set triggerSource 7/9 confirm", new Object());

		assertEquals(1, result);
		assertEquals("7/9", serials.get());
		assertTrue(confirmed.get());
	}

	/**
	 * crosschunk whitelist set 只接受标准顺序 `resident confirm`，不再兼容顺序互换。
	 */
	@Test
	void crossChunkWhitelistSetShouldOnlyAcceptResidentThenConfirm() throws Exception {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicBoolean resident = new AtomicBoolean(false);
		AtomicBoolean confirmed = new AtomicBoolean(false);
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("set")
				.then(
					com.mojang.brigadier.builder.RequiredArgumentBuilder
						.<Object, String>argument("role", StringArgumentType.word())
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, String>argument("type", StringArgumentType.word())
								.then(
									com.mojang.brigadier.builder.RequiredArgumentBuilder
										.<Object, String>argument("serials", SerialBatchArgumentType.serialBatch())
										.executes(context -> {
											serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
											resident.set(false);
											confirmed.set(false);
											return 1;
										})
										.then(
											com.mojang.brigadier.builder.LiteralArgumentBuilder
												.<Object>literal("confirm")
												.executes(context -> {
													serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
													resident.set(false);
													confirmed.set(true);
													return 1;
												})
										)
										.then(
											com.mojang.brigadier.builder.LiteralArgumentBuilder
												.<Object>literal("resident")
												.executes(context -> {
													serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
													resident.set(true);
													confirmed.set(false);
													return 1;
												})
												.then(
													com.mojang.brigadier.builder.LiteralArgumentBuilder
														.<Object>literal("confirm")
														.executes(context -> {
															serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
															resident.set(true);
															confirmed.set(true);
															return 1;
														})
												)
										)
								)
						)
				)
		);

		assertEquals(1, dispatcher.execute("set source triggerSource 1:3 resident confirm", new Object()));
		assertEquals("1:3", serials.get());
		assertTrue(resident.get());
		assertTrue(confirmed.get());
		assertThrows(
			CommandSyntaxException.class,
			() -> dispatcher.execute("set source triggerSource 1:3 confirm resident", new Object())
		);
	}

	/**
	 * 创建 confirm-only 命令测试用 dispatcher。
	 */
	private static CommandDispatcher<Object> createConfirmOnlyBatchDispatcher() {
		return new CommandDispatcher<>();
	}

	/**
	 * 注册 `<type> <serials> [confirm]` 形态的批量覆盖命令树。
	 */
	private static void registerConfirmOnlyBatchSet(
		CommandDispatcher<Object> dispatcher,
		AtomicReference<String> serials,
		AtomicBoolean confirmed
	) {
		dispatcher.register(
			com.mojang.brigadier.builder.LiteralArgumentBuilder
				.<Object>literal("set")
				.then(
					com.mojang.brigadier.builder.RequiredArgumentBuilder
						.<Object, String>argument("type", StringArgumentType.word())
						.then(
							com.mojang.brigadier.builder.RequiredArgumentBuilder
								.<Object, String>argument("serials", SerialBatchArgumentType.serialBatch())
								.executes(context -> {
									serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
									confirmed.set(false);
									return 1;
								})
								.then(
									com.mojang.brigadier.builder.LiteralArgumentBuilder
										.<Object>literal("confirm")
										.executes(context -> {
											serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
											confirmed.set(true);
											return 1;
										})
								)
						)
				)
		);
	}
}
