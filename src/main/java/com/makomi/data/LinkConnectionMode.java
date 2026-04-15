package com.makomi.data;

/**
 * 节点连接模式。
 * <p>
 * `serial` 表示节点直接维护普通 `triggerSource -> core` 边；
 * `channel` 表示节点保存频道配置，并在频道写入或模式切换时同步普通 `triggerSource -> core` 边。
 * </p>
 */
public enum LinkConnectionMode {
	SERIAL("serial", "message.redstonelink.link_connection_mode.serial"),
	CHANNEL("channel", "message.redstonelink.link_connection_mode.channel");

	private final String token;
	private final String translationKey;

	LinkConnectionMode(String token, String translationKey) {
		this.token = token;
		this.translationKey = translationKey;
	}

	/**
	 * 从传输/持久化 token 解析连接模式。
	 */
	public static LinkConnectionMode fromToken(String token) {
		return CHANNEL.token.equalsIgnoreCase(token) ? CHANNEL : SERIAL;
	}

	/**
	 * @return 稳定传输 token
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 本地化名称 key
	 */
	public String translationKey() {
		return translationKey;
	}

	/**
	 * @return 下一个循环模式
	 */
	public LinkConnectionMode next() {
		return this == SERIAL ? CHANNEL : SERIAL;
	}
}
