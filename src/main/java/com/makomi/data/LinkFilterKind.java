package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 过滤器运行时种类。
 * <p>
 * `SEND` 只服务 `triggerSource`，`RECEIVE` 只服务 `core`，
 * 保持过滤能力与 `triggerSource -> core` 单向派发方向一致。
 * </p>
 */
public enum LinkFilterKind {
	SEND("send", LinkNodeType.TRIGGER_SOURCE),
	RECEIVE("receive", LinkNodeType.CORE);

	private final String token;
	private final LinkNodeType servicedNodeType;

	LinkFilterKind(String token, LinkNodeType servicedNodeType) {
		this.token = token;
		this.servicedNodeType = servicedNodeType;
	}

	/**
	 * @return 网络与持久化统一使用的稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 当前过滤器服务的节点类型
	 */
	public LinkNodeType servicedNodeType() {
		return servicedNodeType;
	}

	/**
	 * 解析过滤器种类 token。
	 *
	 * @param rawToken 原始 token
	 * @return 解析结果
	 */
	public static Optional<LinkFilterKind> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (LinkFilterKind kind : values()) {
			if (kind.token.equals(normalized)) {
				return Optional.of(kind);
			}
		}
		return Optional.empty();
	}
}
