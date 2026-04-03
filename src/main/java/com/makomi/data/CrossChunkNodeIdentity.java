package com.makomi.data;

import java.util.Locale;

/**
 * 近外显展示用的跨区块身份。
 * <p>
 * 该枚举仅表达“持久化数据 + 配置”视角下的节点跨区块身份，
 * 不代表运行时票据当前是否已实际挂载。
 * </p>
 */
public enum CrossChunkNodeIdentity {
	/**
	 * 未命中任何强加载或常驻规则。
	 */
	NORMAL("normal"),
	/**
	 * 命中强加载规则，但未命中 resident。
	 */
	FORCE_LOAD("force_load"),
	/**
	 * 命中 resident 常驻规则。
	 */
	RESIDENT("resident");

	private final String payloadToken;

	CrossChunkNodeIdentity(String payloadToken) {
		this.payloadToken = payloadToken;
	}

	/**
	 * 返回网络传输使用的稳定 token。
	 */
	public String payloadToken() {
		return payloadToken;
	}

	/**
	 * 从网络 token 解析跨区块身份；未知值回退到 `NORMAL`。
	 */
	public static CrossChunkNodeIdentity fromPayloadToken(String rawToken) {
		if (rawToken == null) {
			return NORMAL;
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (CrossChunkNodeIdentity identity : values()) {
			if (identity.payloadToken.equals(normalized)) {
				return identity;
			}
		}
		return NORMAL;
	}
}
