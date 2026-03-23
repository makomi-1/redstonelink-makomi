package com.makomi.data.input;

/**
 * 输入播放端点类型。
 * <p>
 * 首版仅覆盖：
 * 1. `triggerSource` 发射器输入
 * 2. `core` 的 `sync` 直输
 * </p>
 */
public enum InputEndpointKind {
	TRIGGER_SOURCE_INPUT("triggerSource_input"),
	CORE_SYNC_DIRECT("core_sync");

	private final String commandName;

	InputEndpointKind(String commandName) {
		this.commandName = commandName;
	}

	/**
	 * 命令与日志统一使用的稳定名称。
	 */
	public String commandName() {
		return commandName;
	}
}
