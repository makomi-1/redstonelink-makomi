package com.makomi.util;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 服务端序号有效性校验工具。
 */
public final class ServerSerialValidationUtil {
	private ServerSerialValidationUtil() {
	}

	/**
	 * 校验源序列号处于已分配且未退役状态。
	 *
	 * @param source 命令源
	 * @param savedData 数据快照
	 * @param sourceType 节点类型
	 * @param sourceSerial 序列号
	 * @return 是否通过校验
	 */
	public static boolean validateSourceSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		if (sourceSerial <= 0L || !savedData.isSerialAllocated(sourceType, sourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.source_serial_unallocated", sourceSerial));
			return false;
		}
		if (savedData.isSerialRetired(sourceType, sourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.source_serial_retired", sourceSerial));
			return false;
		}
		return true;
	}

	/**
	 * 校验目标序列号处于已分配且未退役状态。
	 *
	 * @param source 命令源
	 * @param savedData 数据快照
	 * @param targetType 节点类型
	 * @param targetSerial 序列号
	 * @return 是否通过校验
	 */
	public static boolean validateTargetSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType targetType,
		long targetSerial
	) {
		if (targetSerial <= 0L || !savedData.isSerialAllocated(targetType, targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.target_serial_unallocated", targetSerial));
			return false;
		}
		if (savedData.isSerialRetired(targetType, targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.target_serial_retired", targetSerial));
			return false;
		}
		return true;
	}
}
