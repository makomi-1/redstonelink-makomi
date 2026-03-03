package com.makomi.api.v1.model;

import com.makomi.data.LinkNodeType;

/**
 * RedstoneLink 对外暴露的节点类型。
 */
public enum ApiNodeType {
	CORE,
	BUTTON;

	/**
	 * 转换为内部使用的节点类型。
	 */
	public LinkNodeType toInternal() {
		return this == BUTTON ? LinkNodeType.BUTTON : LinkNodeType.CORE;
	}

	/**
	 * 将内部节点类型转换为对外节点类型。
	 */
	public static ApiNodeType fromInternal(LinkNodeType internalType) {
		return internalType == LinkNodeType.BUTTON ? BUTTON : CORE;
	}

	/**
	 * 获取当前节点允许链接的目标节点类型。
	 */
	public ApiNodeType targetTypeForLink() {
		return this == BUTTON ? CORE : BUTTON;
	}
}

