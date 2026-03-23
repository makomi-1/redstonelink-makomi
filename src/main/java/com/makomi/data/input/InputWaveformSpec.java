package com.makomi.data.input;

import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 输入波形规格。
 * <p>
 * 统一按“每 tick 输出一个 `0~15` 功率样本”建模，供不同端点复用。
 * </p>
 */
public record InputWaveformSpec(
	InputWaveformKind kind,
	int periodTicks,
	int highTicks,
	int highPower,
	int lowPower,
	int phaseTicks,
	List<Integer> customSequence
) {
	public InputWaveformSpec {
		kind = kind == null ? InputWaveformKind.SQUARE : kind;
		periodTicks = Math.max(1, periodTicks);
		highTicks = Math.max(1, Math.min(periodTicks, highTicks));
		highPower = SignalStrengths.clamp(highPower);
		lowPower = SignalStrengths.clamp(lowPower);
		phaseTicks = Math.max(0, phaseTicks);
		customSequence = customSequence == null ? List.of() : normalizeSequence(customSequence);
		if (kind == InputWaveformKind.CUSTOM_SEQUENCE && customSequence.isEmpty()) {
			throw new IllegalArgumentException("customSequence must not be empty");
		}
	}

	/**
	 * 构造方波规格。
	 */
	public static InputWaveformSpec square(
		int periodTicks,
		int highTicks,
		int highPower,
		int lowPower,
		int phaseTicks
	) {
		return new InputWaveformSpec(
			InputWaveformKind.SQUARE,
			periodTicks,
			highTicks,
			highPower,
			lowPower,
			phaseTicks,
			List.of()
		);
	}

	/**
	 * 构造自定义序列规格。
	 */
	public static InputWaveformSpec customSequence(List<Integer> customSequence, int phaseTicks) {
		int normalizedSize = customSequence == null ? 1 : Math.max(1, customSequence.size());
		return new InputWaveformSpec(
			InputWaveformKind.CUSTOM_SEQUENCE,
			normalizedSize,
			normalizedSize,
			0,
			0,
			phaseTicks,
			customSequence
		);
	}

	/**
	 * 采样指定偏移 tick 的功率值。
	 */
	public int sampleAt(long elapsedTicks) {
		long normalizedElapsedTicks = Math.max(0L, elapsedTicks) + phaseTicks;
		return switch (kind) {
			case SQUARE -> sampleSquare(normalizedElapsedTicks);
			case CUSTOM_SEQUENCE -> sampleCustomSequence(normalizedElapsedTicks);
		};
	}

	/**
	 * 用于列表与日志的简要描述。
	 */
	public String describe() {
		return switch (kind) {
			case SQUARE -> String.format(
				Locale.ROOT,
				"square(period=%d,highTicks=%d,high=%d,low=%d,phase=%d)",
				periodTicks,
				highTicks,
				highPower,
				lowPower,
				phaseTicks
			);
			case CUSTOM_SEQUENCE -> String.format(
				Locale.ROOT,
				"custom(size=%d,phase=%d,seq=%s)",
				customSequence.size(),
				phaseTicks,
				formatSequence(customSequence)
			);
		};
	}

	/**
	 * 解析自定义序列文本。
	 * <p>
	 * 支持两种格式：
	 * 1. 位串/十六进制串，例如 `0101`、`f0f0`
	 * 2. 显式强度列表，例如 `15/0/7/0`
	 * </p>
	 */
	public static List<Integer> parseCustomSequence(String rawSequence) {
		String normalized = rawSequence == null ? "" : rawSequence.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("sequence must not be empty");
		}
		if (normalized.matches("^(?:1[0-5]|[0-9])$")) {
			return List.of(Integer.parseInt(normalized, 10));
		}
		if (containsSequenceSeparator(normalized)) {
			String[] tokens = normalized.split("[,/:|]");
			List<Integer> sequence = new ArrayList<>();
			for (String token : tokens) {
				String item = token == null ? "" : token.trim();
				if (item.isEmpty()) {
					continue;
				}
				int value;
				try {
					value = Integer.parseInt(item, 10);
				} catch (NumberFormatException ex) {
					throw new IllegalArgumentException("invalid sequence token: " + item, ex);
				}
				if (value < 0 || value > 15) {
					throw new IllegalArgumentException("sequence token out of range: " + item);
				}
				sequence.add(value);
			}
			if (sequence.isEmpty()) {
				throw new IllegalArgumentException("sequence must not be empty");
			}
			return List.copyOf(sequence);
		}

		List<Integer> sequence = new ArrayList<>(normalized.length());
		for (char ch : normalized.toCharArray()) {
			if (Character.isWhitespace(ch)) {
				continue;
			}
			int value = Character.digit(ch, 16);
			if (value < 0 || value > 15) {
				throw new IllegalArgumentException("invalid sequence char: " + ch);
			}
			sequence.add(value);
		}
		if (sequence.isEmpty()) {
			throw new IllegalArgumentException("sequence must not be empty");
		}
		return List.copyOf(sequence);
	}

	private int sampleSquare(long normalizedElapsedTicks) {
		int cycleTick = (int) (normalizedElapsedTicks % periodTicks);
		return cycleTick < highTicks ? highPower : lowPower;
	}

	private int sampleCustomSequence(long normalizedElapsedTicks) {
		if (customSequence.isEmpty()) {
			return 0;
		}
		int index = (int) (normalizedElapsedTicks % customSequence.size());
		return customSequence.get(index);
	}

	private static boolean containsSequenceSeparator(String rawSequence) {
		return rawSequence.indexOf(',') >= 0
			|| rawSequence.indexOf('/') >= 0
			|| rawSequence.indexOf(':') >= 0
			|| rawSequence.indexOf('|') >= 0;
	}

	private static List<Integer> normalizeSequence(List<Integer> rawSequence) {
		List<Integer> normalized = new ArrayList<>(rawSequence.size());
		for (Integer value : rawSequence) {
			normalized.add(SignalStrengths.clamp(value == null ? 0 : value));
		}
		return List.copyOf(normalized);
	}

	private static String formatSequence(List<Integer> sequence) {
		if (sequence == null || sequence.isEmpty()) {
			return "-";
		}
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < sequence.size(); index++) {
			if (index > 0) {
				builder.append('/');
			}
			builder.append(sequence.get(index));
		}
		return builder.toString();
	}
}
