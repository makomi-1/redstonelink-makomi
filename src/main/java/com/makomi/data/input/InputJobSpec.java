package com.makomi.data.input;

import java.util.List;

/**
 * 输入播放 job 规格。
 * <p>
 * 一个 job 对一组同类端点播放同一波形。
 * </p>
 */
public record InputJobSpec(
	InputEndpointKind endpointKind,
	List<Long> targetSerials,
	InputWaveformSpec waveform,
	int totalTicks
) {
	public InputJobSpec {
		endpointKind = endpointKind == null ? InputEndpointKind.TRIGGER_SOURCE_INPUT : endpointKind;
		targetSerials = targetSerials == null ? List.of() : targetSerials.stream().filter(serial -> serial != null && serial > 0L).distinct().toList();
		waveform = waveform == null ? InputWaveformSpec.square(2, 1, 15, 0, 0) : waveform;
		totalTicks = Math.max(0, totalTicks);
	}

	/**
	 * @return 是否为有限时长 job
	 */
	public boolean finiteDuration() {
		return totalTicks > 0;
	}
}
