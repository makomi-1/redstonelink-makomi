package com.makomi.data.input;

/**
 * 输入波形类型。
 */
public enum InputWaveformKind {
	SQUARE("square"),
	CUSTOM_SEQUENCE("custom_sequence");

	private final String commandName;

	InputWaveformKind(String commandName) {
		this.commandName = commandName;
	}

	/**
	 * 命令与输出使用的稳定名称。
	 */
	public String commandName() {
		return commandName;
	}
}
