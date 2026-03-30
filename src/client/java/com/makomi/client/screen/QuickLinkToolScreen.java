package com.makomi.client.screen;

import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import com.makomi.network.QuickLinkNetwork;
import com.makomi.util.SerialParseUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 快速连接工具缓存编辑界面。
 */
public class QuickLinkToolScreen extends Screen {
	private static final Component SAVE = Component.translatable("screen.redstonelink.quick_link.save");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.quick_link.clear");
	private static final int BUTTON_WIDTH = 108;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int INPUT_BOX_WIDTH = BUTTON_WIDTH * 2 + BUTTON_GAP;
	private static final int INPUT_BOX_HEIGHT = 64;

	private final QuickLinkToolData.Snapshot initialSnapshot;

	private MultiLineEditBox inputBox;
	private Button cacheTypeButton;
	private Component statusMessage = Component.empty();
	private LinkNodeType currentSerialCacheType;

	public QuickLinkToolScreen(QuickLinkToolData.Snapshot snapshot) {
		super(Component.translatable("screen.redstonelink.quick_link.title"));
		initialSnapshot = QuickLinkToolData.normalize(snapshot);
		currentSerialCacheType = initialSnapshot.serialCacheType();
	}

	@Override
	protected void init() {
		super.init();
		int inputX = width / 2 - INPUT_BOX_WIDTH / 2;
		int inputY = height / 2 - 24;
		inputBox = new MultiLineEditBox(font, inputX, inputY, INPUT_BOX_WIDTH, INPUT_BOX_HEIGHT, inputLabel(), Component.empty());
		inputBox.setCharacterLimit(RedstoneLinkClientDisplayConfig.pairing().inputMaxLength());
		inputBox.setValue(initialInputValue());
		addRenderableWidget(inputBox);
		setInitialFocus(inputBox);

		int buttonY = inputY + INPUT_BOX_HEIGHT + 10;
		addRenderableWidget(Button.builder(SAVE, button -> saveAndClose()).bounds(inputX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		addRenderableWidget(
			Button
				.builder(CLEAR, button -> {
					inputBox.setValue("");
					statusMessage = Component.empty();
				})
				.bounds(inputX + BUTTON_WIDTH + BUTTON_GAP, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build()
		);

		cacheTypeButton = addRenderableWidget(
			Button
				.builder(cacheTypeButtonLabel(), button -> {
					currentSerialCacheType = currentSerialCacheType == LinkNodeType.TRIGGER_SOURCE
						? LinkNodeType.CORE
						: LinkNodeType.TRIGGER_SOURCE;
					button.setMessage(cacheTypeButtonLabel());
				})
				.bounds(inputX, buttonY - BUTTON_HEIGHT - 6, INPUT_BOX_WIDTH, BUTTON_HEIGHT)
				.build()
		);
		cacheTypeButton.active = isSerialMode();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		int centerX = width / 2;
		int titleY = height / 2 - 72;
		int leftX = width / 2 - INPUT_BOX_WIDTH / 2;
		guiGraphics.drawCenteredString(font, title, centerX, titleY, 0xFFFFFF);
		guiGraphics.drawCenteredString(
			font,
			Component.translatable("screen.redstonelink.quick_link.mode_line", Component.translatable(currentMode().translationKey())),
			centerX,
			titleY + 14,
			0xC8C8C8
		);
		if (isSerialMode()) {
			guiGraphics.drawString(font, inputLabel(), leftX, titleY + 34, 0xFFFFFF, false);
		} else {
			guiGraphics.drawString(font, inputLabel(), leftX, titleY + 34, 0xFFFFFF, false);
			guiGraphics.drawString(
				font,
				Component.translatable("screen.redstonelink.quick_link.channel_note"),
				leftX,
				titleY + 46,
				0xE0B040,
				false
			);
		}

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, inputBox.getY() + inputBox.getHeight() + 36, 0xFF6666);
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			saveAndClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 保存缓存并关闭界面。
	 */
	private void saveAndClose() {
		if (isSerialMode() && !validateSerialInput(inputBox.getValue())) {
			return;
		}

		ClientPlayNetworking.send(
			new QuickLinkNetwork.SaveQuickLinkPayload(
				currentMode().token(),
				LinkNodeSemantics.toSemanticName(currentSerialCacheType),
				isSerialMode() ? inputBox.getValue() : initialSnapshot.serialCacheExpression(),
				isSerialMode() ? initialSnapshot.channelCache() : inputBox.getValue()
			)
		);
		onClose();
	}

	/**
	 * 校验序号模式输入，仅在客户端做语法级提示。
	 */
	private boolean validateSerialInput(String rawInput) {
		if (rawInput == null || rawInput.isBlank()) {
			statusMessage = Component.empty();
			return true;
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawInput, 0);
		if (!parseResult.invalidEntries().isEmpty()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
			return false;
		}
		statusMessage = Component.empty();
		return true;
	}

	/**
	 * @return 当前界面模式
	 */
	private QuickLinkToolData.Mode currentMode() {
		return initialSnapshot.mode();
	}

	/**
	 * @return 当前是否为序号模式
	 */
	private boolean isSerialMode() {
		return currentMode() == QuickLinkToolData.Mode.SERIAL;
	}

	/**
	 * @return 输入框标题
	 */
	private Component inputLabel() {
		return isSerialMode()
			? Component.translatable("screen.redstonelink.quick_link.serial_input")
			: Component.translatable("screen.redstonelink.quick_link.channel_input");
	}

	/**
	 * @return 输入框初始值
	 */
	private String initialInputValue() {
		return isSerialMode() ? initialSnapshot.serialCacheExpression() : initialSnapshot.channelCache();
	}

	/**
	 * @return 缓存类型按钮文案
	 */
	private Component cacheTypeButtonLabel() {
		return Component.translatable(
			"screen.redstonelink.quick_link.serial_cache_type_button",
			LinkNodeSemantics.toSemanticName(currentSerialCacheType)
		);
	}
}
