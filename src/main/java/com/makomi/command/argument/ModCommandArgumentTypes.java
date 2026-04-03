package com.makomi.command.argument;

import com.makomi.RedstoneLink;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.resources.ResourceLocation;

/**
 * RedstoneLink 自定义命令参数类型注册入口。
 */
public final class ModCommandArgumentTypes {
	private static boolean registered;

	private ModCommandArgumentTypes() {
	}

	/**
	 * 注册本模组使用的自定义命令参数类型。
	 * <p>
	 * 命令树会同步到客户端，因此必须在命令注册前完成注册。
	 * </p>
	 */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ArgumentTypeRegistry.registerArgumentType(
			id("serial_batch"),
			SerialBatchArgumentType.class,
			SingletonArgumentInfo.contextFree(SerialBatchArgumentType::serialBatch)
		);
		ArgumentTypeRegistry.registerArgumentType(
			id("signal_sequence"),
			SignalSequenceArgumentType.class,
			SingletonArgumentInfo.contextFree(SignalSequenceArgumentType::signalSequence)
		);
		ArgumentTypeRegistry.registerArgumentType(
			id("key_value_token"),
			KeyValueTokenArgumentType.class,
			SingletonArgumentInfo.contextFree(KeyValueTokenArgumentType::keyValueToken)
		);
		ArgumentTypeRegistry.registerArgumentType(
			id("serial_sequence_batch"),
			SerialSequenceBatchArgumentType.class,
			SingletonArgumentInfo.contextFree(SerialSequenceBatchArgumentType::serialSequenceBatch)
		);
	}

	/**
	 * 构造参数类型注册 id。
	 */
	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}
}
