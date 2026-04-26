package com.makomi.data;

/**
 * 智能节点容器当前选定的放置类型。
 * <p>
 * 该类型同时驱动：
 * </p>
 * <ul>
 *   <li>右键放置时优先弹出的节点类别；</li>
 *   <li>容器非空时的侧面外显状态；</li>
 *   <li>中键循环切换顺序。</li>
 * </ul>
 */
public enum SmartNodeContainerPlacementType {
	CORE("core", "screen.redstonelink.smart_node_container.type.core", 1, 0),
	TRIGGER_SOURCE("triggerSource", "screen.redstonelink.smart_node_container.type.trigger_source", 2, 1),
	REPEATER("repeater", "screen.redstonelink.smart_node_container.type.repeater", 3, 2);

	private final String token;
	private final String translationKey;
	private final int modelDataValue;
	private final int sortOrder;

	SmartNodeContainerPlacementType(String token, String translationKey, int modelDataValue, int sortOrder) {
		this.token = token;
		this.translationKey = translationKey;
		this.modelDataValue = modelDataValue;
		this.sortOrder = sortOrder;
	}

	/**
	 * @return 用于持久化的稳定标识
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 客户端显示用翻译键
	 */
	public String translationKey() {
		return translationKey;
	}

	/**
	 * @return 容器非空时对应的模型状态值
	 */
	public int modelDataValue() {
		return modelDataValue;
	}

	/**
	 * @return 自动排序时的类型顺序值
	 */
	public int sortOrder() {
		return sortOrder;
	}

	/**
	 * 按固定顺序循环到下一个放置类型。
	 */
	public SmartNodeContainerPlacementType next() {
		return switch (this) {
			case CORE -> TRIGGER_SOURCE;
			case TRIGGER_SOURCE -> REPEATER;
			case REPEATER -> CORE;
		};
	}

	/**
	 * 解析持久化 token；未知值统一回退为 core。
	 */
	public static SmartNodeContainerPlacementType parse(String token) {
		if (token == null || token.isBlank()) {
			return CORE;
		}
		for (SmartNodeContainerPlacementType value : values()) {
			if (value.token.equals(token)) {
				return value;
			}
		}
		return CORE;
	}
}
