package com.makomi.data;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 转发器延迟值对象。
 * <p>
 * 当前仅允许大于 `0` 的正整数 tick；
 * `1/2 tick` 仍保留为稳定常量，`>2 tick` 视为实验性配置。
 * </p>
 */
public record RepeaterDelay(int delayTicks) {
	private static final String ONE_TICK_TRANSLATION_KEY = "screen.redstonelink.repeater.delay.one_tick";
	private static final String TWO_TICKS_TRANSLATION_KEY = "screen.redstonelink.repeater.delay.two_ticks";
	private static final String CUSTOM_TICKS_TRANSLATION_KEY = "screen.redstonelink.repeater.delay.custom_ticks";
	private static final Pattern TOKEN_PATTERN = Pattern.compile("([1-9]\\d*)tick");

	public static final RepeaterDelay ONE_TICK = new RepeaterDelay(1);
	public static final RepeaterDelay TWO_TICKS = new RepeaterDelay(2);

	public RepeaterDelay {
		if (delayTicks <= 0) {
			throw new IllegalArgumentException("repeater delay must be a positive integer tick count");
		}
	}

	/**
	 * @return 稳定持久化 token，例如 `1tick`、`5tick`
	 */
	public String token() {
		return delayTicks + "tick";
	}

	/**
	 * @return GUI/tooltip/HUD 使用的翻译键
	 */
	public String displayTranslationKey() {
		return switch (delayTicks) {
			case 1 -> ONE_TICK_TRANSLATION_KEY;
			case 2 -> TWO_TICKS_TRANSLATION_KEY;
			default -> CUSTOM_TICKS_TRANSLATION_KEY;
		};
	}

	/**
	 * @return 当前显示是否需要把 tick 数作为翻译参数传入
	 */
	public boolean displayTranslationNeedsTickArgument() {
		return delayTicks > 2;
	}

	/**
	 * @return 当前延迟是否属于实验性档位
	 */
	public boolean experimental() {
		return delayTicks > 2;
	}

	/**
	 * 按 tick 数创建延迟值；`1/2 tick` 会复用稳定常量。
	 */
	public static RepeaterDelay ofTicks(int ticks) {
		if (ticks == 1) {
			return ONE_TICK;
		}
		if (ticks == 2) {
			return TWO_TICKS;
		}
		return new RepeaterDelay(ticks);
	}

	/**
	 * 解析延迟 token。
	 */
	public static Optional<RepeaterDelay> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		Matcher matcher = TOKEN_PATTERN.matcher(normalized);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		try {
			return Optional.of(ofTicks(Integer.parseInt(matcher.group(1))));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	/**
	 * 解析延迟 token；未知值回退为 `1 tick`。
	 */
	public static RepeaterDelay fromToken(String rawToken) {
		return tryParseToken(rawToken).orElse(ONE_TICK);
	}
}
