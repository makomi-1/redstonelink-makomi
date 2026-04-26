package com.makomi.client.screen;

import com.makomi.data.SmartNodeContainerPlacementType;
import com.makomi.menu.SmartNodeContainerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 智能节点容器界面。
 * <p>
 * 视觉上沿用原版大箱子主体，并在右侧扩出独立信息区，
 * 避免“当前放置类型 / 自动排序”压到槽位悬停区域。
 * </p>
 */
public class SmartNodeContainerScreen extends AbstractContainerScreen<SmartNodeContainerMenu> {
	private static final int CHEST_IMAGE_WIDTH = 176;
	private static final int RIGHT_PANEL_WIDTH = 96;
	private static final int RIGHT_PANEL_GAP = 8;
	private static final int RIGHT_PANEL_X = CHEST_IMAGE_WIDTH + RIGHT_PANEL_GAP;
	private static final int RIGHT_PANEL_INSET = 8;
	private static final int RIGHT_PANEL_LABEL_Y = 20;
	private static final int RIGHT_PANEL_VALUE_Y = 34;
	private static final int RIGHT_PANEL_BUTTON_Y = 58;
	private static final int RIGHT_PANEL_BUTTON_WIDTH = RIGHT_PANEL_WIDTH - RIGHT_PANEL_INSET * 2;
	private static final int RIGHT_PANEL_BUTTON_HEIGHT = 20;
	private static final int RIGHT_PANEL_TOP = 12;
	private static final int RIGHT_PANEL_BOTTOM_INSET = 12;
	private static final int RIGHT_PANEL_BACKGROUND = 0xD92A1C13;
	private static final int RIGHT_PANEL_BORDER = 0xFF8E6B59;
	private static final int RIGHT_PANEL_DIVIDER = 0xFF5A4032;
	private static final ResourceLocation CONTAINER_TEXTURE = ResourceLocation.withDefaultNamespace(
		"textures/gui/container/generic_54.png"
	);

	private Button autoSortButton;

	public SmartNodeContainerScreen(SmartNodeContainerMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		imageWidth = CHEST_IMAGE_WIDTH + RIGHT_PANEL_GAP + RIGHT_PANEL_WIDTH;
		imageHeight = 222;
		inventoryLabelY = imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		autoSortButton = addRenderableWidget(
			Button
				.builder(autoSortButtonLabel(), button -> toggleAutoSort())
				.bounds(
					leftPos + RIGHT_PANEL_X + RIGHT_PANEL_INSET,
					topPos + RIGHT_PANEL_BUTTON_Y,
					RIGHT_PANEL_BUTTON_WIDTH,
					RIGHT_PANEL_BUTTON_HEIGHT
				)
				.build()
		);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		if (autoSortButton != null) {
			autoSortButton.setMessage(autoSortButtonLabel());
		}
	}

	@Override
	protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
		guiGraphics.blit(CONTAINER_TEXTURE, leftPos, topPos, 0, 0, CHEST_IMAGE_WIDTH, ROW_HEIGHT(), 256, 256);
		guiGraphics.blit(CONTAINER_TEXTURE, leftPos, topPos + ROW_HEIGHT(), 0, 126, CHEST_IMAGE_WIDTH, 96, 256, 256);
		renderRightPanelBackground(guiGraphics);
	}

	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.smart_node_container.selected_type_label"),
			RIGHT_PANEL_X + RIGHT_PANEL_INSET,
			RIGHT_PANEL_LABEL_Y,
			0xF4E8D8,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable(
				currentSelectedType().translationKey()
			),
			RIGHT_PANEL_X + RIGHT_PANEL_INSET,
			RIGHT_PANEL_VALUE_Y,
			0xFFFFFF,
			false
		);
		guiGraphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
	}

	private void renderRightPanelBackground(GuiGraphics guiGraphics) {
		int panelLeft = leftPos + RIGHT_PANEL_X;
		int panelTop = topPos + RIGHT_PANEL_TOP;
		int panelRight = leftPos + imageWidth;
		int panelBottom = topPos + imageHeight - RIGHT_PANEL_BOTTOM_INSET;
		guiGraphics.fill(panelLeft, panelTop, panelRight, panelBottom, RIGHT_PANEL_BORDER);
		guiGraphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelBottom - 1, RIGHT_PANEL_BACKGROUND);
		guiGraphics.fill(
			panelLeft + RIGHT_PANEL_INSET,
			topPos + RIGHT_PANEL_BUTTON_Y - 8,
			panelRight - RIGHT_PANEL_INSET,
			topPos + RIGHT_PANEL_BUTTON_Y - 7,
			RIGHT_PANEL_DIVIDER
		);
	}

	private void toggleAutoSort() {
		if (minecraft == null || minecraft.gameMode == null) {
			return;
		}
		minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SmartNodeContainerMenu.BUTTON_TOGGLE_AUTO_SORT);
	}

	private Component autoSortButtonLabel() {
		return Component.translatable(
			"screen.redstonelink.smart_node_container.auto_sort",
			Component.translatable(
				menu.isAutoSortEnabled()
					? "screen.redstonelink.smart_node_container.toggle.on"
					: "screen.redstonelink.smart_node_container.toggle.off"
			)
		);
	}

	private SmartNodeContainerPlacementType currentSelectedType() {
		return menu.selectedType();
	}

	private static int ROW_HEIGHT() {
		return SmartNodeContainerMenu.ROW_COUNT * 18 + 17;
	}
}
