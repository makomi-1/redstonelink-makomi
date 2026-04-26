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
 * 视觉上沿用原版大箱子布局，只额外挂一个自动排序开关和当前放置类型提示。
 * </p>
 */
public class SmartNodeContainerScreen extends AbstractContainerScreen<SmartNodeContainerMenu> {
	private static final ResourceLocation CONTAINER_TEXTURE = ResourceLocation.withDefaultNamespace(
		"textures/gui/container/generic_54.png"
	);

	private Button autoSortButton;

	public SmartNodeContainerScreen(SmartNodeContainerMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		imageWidth = 176;
		imageHeight = 222;
		inventoryLabelY = imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		autoSortButton = addRenderableWidget(
			Button
				.builder(autoSortButtonLabel(), button -> toggleAutoSort())
				.bounds(leftPos + imageWidth - 86, topPos + 4, 78, 20)
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
		guiGraphics.blit(CONTAINER_TEXTURE, leftPos, topPos, 0, 0, imageWidth, ROW_HEIGHT(), 256, 256);
		guiGraphics.blit(CONTAINER_TEXTURE, leftPos, topPos + ROW_HEIGHT(), 0, 126, imageWidth, 96, 256, 256);
	}

	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
		guiGraphics.drawString(
			font,
			Component.translatable(
				"screen.redstonelink.smart_node_container.selected_type",
				Component.translatable(currentSelectedType().translationKey())
			),
			8,
			24,
			0x404040,
			false
		);
		guiGraphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
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
