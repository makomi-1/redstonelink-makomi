package com.makomi.data;

import com.makomi.util.SerialDisplayFormatUtil;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.CustomData;

/**
 * 快速连接工具缓存数据读写工具。
 * <p>
 * 该工具只负责维护物品上的本地缓存，不直接承担真实连接写入逻辑。
 * 第一阶段固定保留两类模式：
 * </p>
 * <br/>1) `serial`：可真正用于快速应用；
 * <br/>2) `channel`：仅保存输入，暂不执行真实建链。
 */
public final class QuickLinkToolData {
	private static final String KEY_MODE = "rl_quick_link_mode";
	private static final String KEY_SERIAL_CACHE_TYPE = "rl_quick_link_serial_cache_type";
	private static final String KEY_SERIAL_CACHE_EXPRESSION = "rl_quick_link_serial_cache_expression";
	private static final String KEY_CHANNEL_CACHE = "rl_quick_link_channel_cache";
	private static final String KEY_APPLY_EDIT_MODE = "rl_quick_link_apply_edit_mode";
	private static final int QUICK_LINK_TOOL_CHANNEL_MODEL = 1;

	private QuickLinkToolData() {
	}

	/**
	 * 读取工具缓存快照。
	 */
	public static Snapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		Mode mode = Mode.fromToken(tag.getString(KEY_MODE));
		LinkNodeType serialCacheType = LinkNodeSemantics.tryParseCanonicalType(tag.getString(KEY_SERIAL_CACHE_TYPE))
			.orElse(LinkNodeType.CORE);
		return new Snapshot(
			mode,
			normalizeSerialCacheType(serialCacheType),
			normalizeText(tag.getString(KEY_SERIAL_CACHE_EXPRESSION)),
			normalizeText(tag.getString(KEY_CHANNEL_CACHE)),
			ApplyEditMode.fromToken(tag.getString(KEY_APPLY_EDIT_MODE))
		);
	}

	/**
	 * 覆盖写入工具缓存快照。
	 */
	public static void write(ItemStack stack, Snapshot snapshot) {
		Snapshot normalized = normalize(snapshot);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putString(KEY_MODE, normalized.mode().token());
			tag.putString(KEY_SERIAL_CACHE_TYPE, LinkNodeSemantics.toSemanticName(normalized.serialCacheType()));
			writeStringOrRemove(tag, KEY_SERIAL_CACHE_EXPRESSION, normalized.serialCacheExpression());
			writeStringOrRemove(tag, KEY_CHANNEL_CACHE, normalized.channelCache());
			tag.putString(KEY_APPLY_EDIT_MODE, normalized.applyEditMode().token());
		});
		syncQuickLinkToolModelState(stack);
	}

	/**
	 * 将当前模式循环切换为下一个模式并回写。
	 *
	 * @return 回写后的新快照
	 */
	public static Snapshot cycleMode(ItemStack stack) {
		Snapshot current = read(stack);
		Snapshot next = current.withMode(current.mode().next());
		write(stack, next);
		return next;
	}

	/**
	 * 将当前应用编辑模式循环切换为下一个模式并回写。
	 *
	 * @return 回写后的新快照
	 */
	public static Snapshot cycleApplyEditMode(ItemStack stack) {
		Snapshot current = read(stack);
		Snapshot next = current.withApplyEditMode(current.applyEditMode().next());
		write(stack, next);
		return next;
	}

	/**
	 * 采集单个序号节点到工具缓存。
	 * <p>
	 * 仅当当前模式仍为 `serial` 且缓存类型一致时才执行增量去重；否则重建序号缓存。
	 * </p>
	 */
	public static SerialCollectOutcome collectSerial(ItemStack stack, LinkNodeType serialCacheType, long collectedSerial) {
		LinkNodeType normalizedType = normalizeSerialCacheType(serialCacheType);
		Snapshot current = read(stack);
		List<Long> mergedSerials = new ArrayList<>();
		SerialCollectAction action = SerialCollectAction.REPLACED;

		if (
			current.mode() == Mode.SERIAL &&
			current.serialCacheType() == normalizedType &&
			!current.serialCacheExpression().isBlank()
		) {
			SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(current.serialCacheExpression(), 0);
			if (!parseResult.exceedLimit() && parseResult.invalidEntries().isEmpty()) {
				mergedSerials.addAll(parseResult.orderedTargets());
				action = SerialCollectAction.APPENDED;
			}
		}

		if (mergedSerials.contains(collectedSerial)) {
			action = SerialCollectAction.DUPLICATE;
		} else {
			mergedSerials.add(collectedSerial);
		}

		String nextExpression = SerialDisplayFormatUtil.buildExpression(mergedSerials).joinAll();
		Snapshot next = new Snapshot(
			Mode.SERIAL,
			normalizedType,
			nextExpression,
			current.channelCache(),
			current.applyEditMode()
		);
		write(stack, next);
		return new SerialCollectOutcome(action, normalizedType, collectedSerial, mergedSerials.size(), next);
	}

	/**
	 * 清空当前工具缓存，但保留模式与序号缓存类型。
	 *
	 * @return 清空后的新快照
	 */
	public static Snapshot clearCaches(ItemStack stack) {
		Snapshot current = read(stack);
		Snapshot cleared = new Snapshot(
			current.mode(),
			current.serialCacheType(),
			"",
			"",
			current.applyEditMode()
		);
		write(stack, cleared);
		return cleared;
	}

	/**
	 * 按当前 quick-link 模式同步物品贴图镜像。
	 * <p>
	 * `serial` 使用默认 `_sd` 贴图；`channel` 通过 `CustomModelData=1` 切到 `_cp`。
	 * </p>
	 */
	public static void syncQuickLinkToolModelState(ItemStack stack) {
		if (stack == null) {
			return;
		}
		if (stack.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_MODEL_DATA);
			return;
		}
		if (read(stack).mode() == Mode.CHANNEL) {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(QUICK_LINK_TOOL_CHANNEL_MODEL));
			return;
		}
		stack.remove(DataComponents.CUSTOM_MODEL_DATA);
	}

	/**
	 * 规范化任意来源构造的快照，避免非法值写回物品。
	 */
	public static Snapshot normalize(Snapshot snapshot) {
		if (snapshot == null) {
			return Snapshot.EMPTY;
		}
		return new Snapshot(
			snapshot.mode(),
			snapshot.serialCacheType(),
			snapshot.serialCacheExpression(),
			snapshot.channelCache(),
			snapshot.applyEditMode()
		);
	}

	/**
	 * 从网络/界面提交的原始 token 构建规范化快照。
	 */
	public static Snapshot fromTokens(
		String modeToken,
		String serialCacheTypeToken,
		String serialCacheExpression,
		String channelCache,
		String applyEditModeToken
	) {
		Mode mode = Mode.fromToken(modeToken);
		LinkNodeType serialCacheType = LinkNodeSemantics.tryParseCanonicalType(serialCacheTypeToken)
			.orElse(LinkNodeType.CORE);
		return new Snapshot(mode, serialCacheType, serialCacheExpression, channelCache, ApplyEditMode.fromToken(applyEditModeToken));
	}

	/**
	 * 读取物品自定义数据标签。
	 */
	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	/**
	 * 仅允许 `triggerSource/core` 两类缓存类型。
	 */
	private static LinkNodeType normalizeSerialCacheType(LinkNodeType serialCacheType) {
		return serialCacheType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
	}

	/**
	 * 去除前后空白并规整 null。
	 */
	private static String normalizeText(String text) {
		return text == null ? "" : text.trim();
	}

	/**
	 * 非空字符串写入，为空则移除对应字段。
	 */
	private static void writeStringOrRemove(CompoundTag tag, String key, String value) {
		if (value == null || value.isBlank()) {
			tag.remove(key);
			return;
		}
		tag.putString(key, value.trim());
	}

	/**
	 * 快速连接工具模式。
	 */
	public enum Mode {
		SERIAL("serial", "message.redstonelink.quick_link.mode.serial"),
		CHANNEL("channel", "message.redstonelink.quick_link.mode.channel");

		private final String token;
		private final String translationKey;

		Mode(String token, String translationKey) {
			this.token = token;
			this.translationKey = translationKey;
		}

		/**
		 * 从存储 token 读取模式，非法值回退为 `serial`。
		 */
		public static Mode fromToken(String token) {
			if (CHANNEL.token.equalsIgnoreCase(token)) {
				return CHANNEL;
			}
			return SERIAL;
		}

		/**
		 * @return 适合持久化/网络传输的模式 token
		 */
		public String token() {
			return token;
		}

		/**
		 * @return 模式名称翻译键
		 */
		public String translationKey() {
			return translationKey;
		}

		/**
		 * @return 下一个循环模式
		 */
		public Mode next() {
			return this == SERIAL ? CHANNEL : SERIAL;
		}
	}

	/**
	 * 快速连接工具的完整缓存快照。
	 */
	public record Snapshot(
		Mode mode,
		LinkNodeType serialCacheType,
		String serialCacheExpression,
		String channelCache,
		ApplyEditMode applyEditMode
	) {
		public static final Snapshot EMPTY = new Snapshot(Mode.SERIAL, LinkNodeType.CORE, "", "", ApplyEditMode.REPLACE);

		public Snapshot {
			mode = mode == null ? Mode.SERIAL : mode;
			serialCacheType = normalizeSerialCacheType(serialCacheType);
			serialCacheExpression = normalizeText(serialCacheExpression);
			channelCache = normalizeText(channelCache);
			applyEditMode = applyEditMode == null ? ApplyEditMode.REPLACE : applyEditMode;
		}

		/**
		 * 返回切换模式后的新快照。
		 */
		public Snapshot withMode(Mode nextMode) {
			return new Snapshot(nextMode, serialCacheType, serialCacheExpression, channelCache, applyEditMode);
		}

		/**
		 * 返回切换应用编辑模式后的新快照。
		 */
		public Snapshot withApplyEditMode(ApplyEditMode nextApplyEditMode) {
			return new Snapshot(mode, serialCacheType, serialCacheExpression, channelCache, nextApplyEditMode);
		}

		/**
		 * 返回频道缓存值语义包装。
		 */
		public ChannelCacheValue channelCacheValue() {
			return new ChannelCacheValue(channelCache);
		}
	}

	/**
	 * 频道缓存值语义包装。
	 * <p>
	 * 当前仅预留接口，不对其进行数值级解析；后续可平滑切换为 `long` 等强类型实现。
	 * </p>
	 */
	public record ChannelCacheValue(String rawValue) {
		public ChannelCacheValue {
			rawValue = normalizeText(rawValue);
		}
	}

	/**
	 * quick-link 应用编辑模式。
	 */
	public enum ApplyEditMode {
		REPLACE("replace", "message.redstonelink.quick_link.apply_edit_mode.replace"),
		APPEND("append", "message.redstonelink.quick_link.apply_edit_mode.append"),
		REMOVE("remove", "message.redstonelink.quick_link.apply_edit_mode.remove");

		private final String token;
		private final String translationKey;

		ApplyEditMode(String token, String translationKey) {
			this.token = token;
			this.translationKey = translationKey;
		}

		/**
		 * 从存储 token 读取应用编辑模式，非法值回退为 `replace`。
		 */
		public static ApplyEditMode fromToken(String token) {
			if (APPEND.token.equalsIgnoreCase(token)) {
				return APPEND;
			}
			if (REMOVE.token.equalsIgnoreCase(token)) {
				return REMOVE;
			}
			return REPLACE;
		}

		/**
		 * @return 适合持久化/网络传输的模式 token
		 */
		public String token() {
			return token;
		}

		/**
		 * @return 模式名称翻译键
		 */
		public String translationKey() {
			return translationKey;
		}

		/**
		 * @return 下一个循环模式
		 */
		public ApplyEditMode next() {
			return switch (this) {
				case REPLACE -> APPEND;
				case APPEND -> REMOVE;
				case REMOVE -> REPLACE;
			};
		}
	}

	/**
	 * 序号采集动作类型。
	 */
	public enum SerialCollectAction {
		REPLACED,
		APPENDED,
		DUPLICATE
	}

	/**
	 * 序号采集结果。
	 */
	public record SerialCollectOutcome(
		SerialCollectAction action,
		LinkNodeType serialCacheType,
		long collectedSerial,
		int serialCount,
		Snapshot snapshot
	) {
	}
}
