package com.makomi.client.web;

/**
 * 本地网页偏好快照。
 * <p>
 * 当前仅承载网页语言与主题两个字段，避免把网页运行态偏好扩展成通用配置体系。
 * </p>
 *
 * @param language 当前网页语言
 * @param themeId 当前网页主题标识
 */
public record LocalWebPreferences(String language, String themeId) {
	public static final String DEFAULT_LANGUAGE = "zh-CN";
	public static final String DEFAULT_THEME_ID = "future-command";

	/**
	 * @return 默认网页偏好
	 */
	public static LocalWebPreferences defaults() {
		return new LocalWebPreferences(DEFAULT_LANGUAGE, DEFAULT_THEME_ID);
	}

	/**
	 * 以当前快照为基线应用局部覆盖，并在字段缺失时回退到默认值。
	 *
	 * @param nextLanguage 下一个语言；为空时保持当前值
	 * @param nextThemeId 下一个主题；为空时保持当前值
	 * @return 归一化后的新快照
	 */
	public LocalWebPreferences withOverrides(String nextLanguage, String nextThemeId) {
		return new LocalWebPreferences(
			normalizeLanguage(nextLanguage == null || nextLanguage.isBlank() ? language : nextLanguage),
			normalizeThemeId(nextThemeId == null || nextThemeId.isBlank() ? themeId : nextThemeId)
		);
	}

	/**
	 * @return 将当前快照收敛到可持久化的规范值
	 */
	public LocalWebPreferences normalized() {
		return new LocalWebPreferences(normalizeLanguage(language), normalizeThemeId(themeId));
	}

	private static String normalizeLanguage(String rawLanguage) {
		if (rawLanguage == null || rawLanguage.isBlank()) {
			return DEFAULT_LANGUAGE;
		}
		return rawLanguage.trim();
	}

	private static String normalizeThemeId(String rawThemeId) {
		if (rawThemeId == null || rawThemeId.isBlank()) {
			return DEFAULT_THEME_ID;
		}
		return rawThemeId.trim();
	}
}
