package com.makomi.data;

import java.util.List;

/**
 * 快速连接工具统一反馈结果。
 * <p>
 * 供采集、应用与模式限制等场景共用，避免网络层为每种动作维护不同回执结构。
 * </p>
 */
public record QuickLinkOperationFeedback(boolean success, String messageKey, List<String> messageArgs) {
	/**
	 * 构建失败反馈。
	 */
	public static QuickLinkOperationFeedback failure(String messageKey, String... messageArgs) {
		return new QuickLinkOperationFeedback(false, messageKey, List.of(messageArgs));
	}

	/**
	 * 构建成功反馈。
	 */
	public static QuickLinkOperationFeedback success(String messageKey, String... messageArgs) {
		return new QuickLinkOperationFeedback(true, messageKey, List.of(messageArgs));
	}
}
