package com.makomi.data;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 隐藏节点定向编辑工具本地数据。
 * <p>
 * 当前仅保存编辑模式，不承担任何真实节点写入逻辑。
 * </p>
 */
public final class HideDirectionalEditorToolData {
	private static final String KEY_EDIT_MODE = "rl_hide_directional_editor_mode";

	private HideDirectionalEditorToolData() {
	}

	/**
	 * 读取当前编辑模式。
	 */
	public static EditMode readMode(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return EditMode.TOGGLE;
		}
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return EditMode.fromToken(customData.copyTag().getStringOr(KEY_EDIT_MODE, ""));
	}

	/**
	 * 覆盖写入当前编辑模式。
	 */
	public static void writeMode(ItemStack stack, EditMode editMode) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		EditMode normalizedMode = editMode == null ? EditMode.TOGGLE : editMode;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(KEY_EDIT_MODE, normalizedMode.token()));
	}

	/**
	 * 循环切换到下一个编辑模式并回写。
	 */
	public static EditMode cycleMode(ItemStack stack) {
		EditMode nextMode = readMode(stack).next();
		writeMode(stack, nextMode);
		return nextMode;
	}

	/**
	 * 隐藏节点面集编辑模式。
	 */
	public enum EditMode {
		TOGGLE("toggle", "tooltip.redstonelink.directional_face_editor.mode.toggle"),
		SINGLE("single", "tooltip.redstonelink.directional_face_editor.mode.single"),
		ALL("all", "tooltip.redstonelink.directional_face_editor.mode.all"),
		CLEAR("clear", "tooltip.redstonelink.directional_face_editor.mode.clear");

		private final String token;
		private final String translationKey;

		EditMode(String token, String translationKey) {
			this.token = token;
			this.translationKey = translationKey;
		}

		/**
		 * 当前模式持久化 token。
		 */
		public String token() {
			return token;
		}

		/**
		 * 当前模式显示文案 key。
		 */
		public String translationKey() {
			return translationKey;
		}

		/**
		 * 循环切换到下一个模式。
		 */
		public EditMode next() {
			return switch (this) {
				case TOGGLE -> SINGLE;
				case SINGLE -> ALL;
				case ALL -> CLEAR;
				case CLEAR -> TOGGLE;
			};
		}

		/**
		 * 从持久化 token 解析模式。
		 */
		public static EditMode fromToken(String rawToken) {
			if (rawToken == null || rawToken.isBlank()) {
				return TOGGLE;
			}
			String normalizedToken = rawToken.trim();
			for (EditMode value : values()) {
				if (value.token.equalsIgnoreCase(normalizedToken)) {
					return value;
				}
			}
			return TOGGLE;
		}
	}
}
