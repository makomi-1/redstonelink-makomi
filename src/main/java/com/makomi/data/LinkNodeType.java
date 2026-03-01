package com.makomi.data;

public enum LinkNodeType {
	CORE,
	BUTTON;

	public static LinkNodeType fromName(String name) {
		for (LinkNodeType value : values()) {
			if (value.name().equalsIgnoreCase(name)) {
				return value;
			}
		}
		return CORE;
	}
}
