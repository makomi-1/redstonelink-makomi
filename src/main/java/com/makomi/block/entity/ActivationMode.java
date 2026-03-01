package com.makomi.block.entity;

public enum ActivationMode {
	TOGGLE,
	PULSE;

	public static ActivationMode fromName(String name) {
		for (ActivationMode value : values()) {
			if (value.name().equalsIgnoreCase(name)) {
				return value;
			}
		}
		return TOGGLE;
	}
}
