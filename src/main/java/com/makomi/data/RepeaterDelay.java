package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 转发器固定延迟档位。
 * <p>
 * 首版仅支持 `1/2 tick` 两档，避免引入实验性的 `0 tick` 语义。
 * </p>
 */
public enum RepeaterDelay {
	ONE_TICK("1tick", 1, "screen.redstonelink.repeater.delay.one_tick"),
	TWO_TICKS("2tick", 2, "screen.redstonelink.repeater.delay.two_ticks");

	private final String token;
	private final int delayTicks;
	private final String translationKey;

	RepeaterDelay(String token, int delayTicks, String translationKey) {
		this.token = token;
		this.delayTicks = Math.max(1, delayTicks);
		this.translationKey = translationKey == null ? "" : translationKey;
	}

	/**
	 * @return 稳定持久化 token
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 实际延迟 tick 数
	 */
	public int delayTicks() {
		return delayTicks;
	}

	/**
	 * @return GUI/tooltip 使用的翻译键
	 */
	public String translationKey() {
		return translationKey;
	}

	/**
	 * 解析延迟 token。
	 */
	public static Optional<RepeaterDelay> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (RepeaterDelay delay : values()) {
			if (delay.token.equals(normalized)) {
				return Optional.of(delay);
			}
		}
		return Optional.empty();
	}

	/**
	 * 解析延迟 token；未知值回退为 `1 tick`。
	 */
	public static RepeaterDelay fromToken(String rawToken) {
		return tryParseToken(rawToken).orElse(ONE_TICK);
	}
}
