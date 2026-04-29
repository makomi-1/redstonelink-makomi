package com.makomi.client.screen;

import com.makomi.data.SmartNodeContainerPlacementType;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.makomi.menu.SmartNodeContainerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 智能节点容器界面。
 * <p>
 * 视觉上沿用原版大箱子主体，并在右侧扩出独立信息区，
 * 避免“当前放置类型 / 自动排序”压到槽位悬停区域。
 * </p>
 */
public class SmartNodeContainerScreen extends AbstractContainerScreen<SmartNodeContainerMenu> {
	private static final int CHEST_IMAGE_WIDTH = 176;
	private static final int RIGHT_PANEL_WIDTH = 144;
	private static final int RIGHT_PANEL_GAP = 8;
	private static final int RIGHT_PANEL_X = CHEST_IMAGE_WIDTH + RIGHT_PANEL_GAP;
	private static final int RIGHT_PANEL_INSET = 8;
	private static final int RIGHT_PANEL_LABEL_Y = 20;
	private static final int RIGHT_PANEL_FIELD_Y = 30;
	private static final int RIGHT_PANEL_FIELD_HEIGHT = 18;
	private static final int RIGHT_PANEL_VALUE_TEXT_INSET = 6;
	private static final int RIGHT_PANEL_BUTTON_Y = 58;
	private static final int RIGHT_PANEL_SECOND_BUTTON_Y = 84;
	private static final int RIGHT_PANEL_BUTTON_WIDTH = RIGHT_PANEL_WIDTH - RIGHT_PANEL_INSET * 2;
	private static final int RIGHT_PANEL_BUTTON_HEIGHT = 20;
	private static final int RIGHT_PANEL_TOP = 12;
	private static final int RIGHT_PANEL_BOTTOM_INSET = 12;
	private static final int RIGHT_PANEL_BACKGROUND = 0xD92A1C13;
	private static final int RIGHT_PANEL_BORDER = 0xFF8E6B59;
	private static final int RIGHT_PANEL_FIELD_BORDER = 0xFF6C5244;
	private static final int RIGHT_PANEL_FIELD_BACKGROUND = 0xE6150E0A;
	private static final int RIGHT_PANEL_DIVIDER = 0xFF5A4032;
	private static final RenderPipeline CONTAINER_RENDER_PIPELINE = RenderPipelines.GUI_TEXTURED;
	private static final Identifier CONTAINER_TEXTURE = Identifier.withDefaultNamespace(
		"textures/gui/container/generic_54.png"
	);

	private Button autoSortButton;
	private Button creativeAutoConsumeButton;

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
		creativeAutoConsumeButton = addRenderableWidget(
			Button
				.builder(creativeAutoConsumeButtonLabel(), button -> toggleCreativeAutoConsume())
				.bounds(
					leftPos + RIGHT_PANEL_X + RIGHT_PANEL_INSET,
					topPos + RIGHT_PANEL_SECOND_BUTTON_Y,
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
		if (creativeAutoConsumeButton != null) {
			creativeAutoConsumeButton.setMessage(creativeAutoConsumeButtonLabel());
			creativeAutoConsumeButton.active = menu.canToggleCreativeAutoConsume();
		}
	}

	@Override
	protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
		// 1.21.11 下原版容器底图需要显式走 GUI_TEXTURED 管线，直接 blit 资源路径会导致箱子槽位与背景缺失。
		guiGraphics.blit(
			CONTAINER_RENDER_PIPELINE,
			CONTAINER_TEXTURE,
			leftPos,
			topPos,
			0.0F,
			0.0F,
			CHEST_IMAGE_WIDTH,
			ROW_HEIGHT(),
			256,
			256
		);
		guiGraphics.blit(
			CONTAINER_RENDER_PIPELINE,
			CONTAINER_TEXTURE,
			leftPos,
			topPos + ROW_HEIGHT(),
			0.0F,
			126.0F,
			CHEST_IMAGE_WIDTH,
			96,
			256,
			256
		);
		renderRightPanelBackground(guiGraphics);
	}

	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFF404040, false);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.smart_node_container.selected_type_label"),
			RIGHT_PANEL_X + RIGHT_PANEL_INSET,
			RIGHT_PANEL_LABEL_Y,
			0xFFF4E8D8,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable(
				currentSelectedType().translationKey()
			),
			RIGHT_PANEL_X + RIGHT_PANEL_INSET + RIGHT_PANEL_VALUE_TEXT_INSET,
			RIGHT_PANEL_FIELD_Y + ((RIGHT_PANEL_FIELD_HEIGHT - font.lineHeight) / 2),
			0xFFFFFFFF,
			false
		);
		guiGraphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF404040, false);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		renderHoveredSlotTooltip(guiGraphics, mouseX, mouseY);
	}

	@Override
	protected void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		// tooltip 改为在 render 末尾统一绘制，避免继续依赖父类内部维护的 hoveredSlot 状态。
	}

	private void renderRightPanelBackground(GuiGraphics guiGraphics) {
		int panelLeft = leftPos + RIGHT_PANEL_X;
		int panelTop = topPos + RIGHT_PANEL_TOP;
		int panelRight = leftPos + imageWidth;
		int panelBottom = topPos + imageHeight - RIGHT_PANEL_BOTTOM_INSET;
		int fieldLeft = panelLeft + RIGHT_PANEL_INSET;
		int fieldTop = topPos + RIGHT_PANEL_FIELD_Y;
		int fieldRight = panelRight - RIGHT_PANEL_INSET;
		int fieldBottom = fieldTop + RIGHT_PANEL_FIELD_HEIGHT;
		guiGraphics.fill(panelLeft, panelTop, panelRight, panelBottom, RIGHT_PANEL_BORDER);
		guiGraphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelBottom - 1, RIGHT_PANEL_BACKGROUND);
		// 当前放置类型沿用原设计的独立字段框，避免右侧信息区只剩裸文本。
		guiGraphics.fill(fieldLeft, fieldTop, fieldRight, fieldBottom, RIGHT_PANEL_FIELD_BORDER);
		guiGraphics.fill(fieldLeft + 1, fieldTop + 1, fieldRight - 1, fieldBottom - 1, RIGHT_PANEL_FIELD_BACKGROUND);
		guiGraphics.fill(
			panelLeft + RIGHT_PANEL_INSET,
			topPos + RIGHT_PANEL_BUTTON_Y - 8,
			panelRight - RIGHT_PANEL_INSET,
			topPos + RIGHT_PANEL_BUTTON_Y - 7,
			RIGHT_PANEL_DIVIDER
		);
		guiGraphics.fill(
			panelLeft + RIGHT_PANEL_INSET,
			topPos + RIGHT_PANEL_SECOND_BUTTON_Y - 8,
			panelRight - RIGHT_PANEL_INSET,
			topPos + RIGHT_PANEL_SECOND_BUTTON_Y - 7,
			RIGHT_PANEL_DIVIDER
		);
	}

	private void toggleAutoSort() {
		if (minecraft == null || minecraft.gameMode == null) {
			return;
		}
		minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SmartNodeContainerMenu.BUTTON_TOGGLE_AUTO_SORT);
	}

	private void toggleCreativeAutoConsume() {
		if (minecraft == null || minecraft.gameMode == null || !menu.canToggleCreativeAutoConsume()) {
			return;
		}
		minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SmartNodeContainerMenu.BUTTON_TOGGLE_CREATIVE_AUTO_CONSUME);
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

	private Component creativeAutoConsumeButtonLabel() {
		return Component.translatable(
			"screen.redstonelink.smart_node_container.creative_auto_consume",
			Component.translatable(
				menu.isCreativeAutoConsumeEnabled()
					? "screen.redstonelink.smart_node_container.toggle.on"
					: "screen.redstonelink.smart_node_container.toggle.off"
			)
		);
	}

	private SmartNodeContainerPlacementType currentSelectedType() {
		return menu.selectedType();
	}

	/**
	 * 为容器区、玩家背包区与热键栏统一补一层槽位 tooltip。
	 * <p>
	 * 原版容器界面依赖父类在 render 过程中维护 hoveredSlot。
	 * 这里直接按相同的槽位命中规则重新解析一次，避免 screen 扩展后
	 * tooltip 继续受父类内部状态影响。
	 * </p>
	 */
	private void renderHoveredSlotTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (!menu.getCarried().isEmpty()) {
			return;
		}
		Slot slot = resolveHoveredSlot(mouseX, mouseY);
		if (slot == null || !slot.hasItem()) {
			return;
		}
		ItemStack stack = slot.getItem();
		guiGraphics.setTooltipForNextFrame(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(), mouseX, mouseY);
	}

	/**
	 * 对照原版 AbstractContainerScreen 的命中逻辑重新解析当前悬停槽位。
	 */
	private Slot resolveHoveredSlot(int mouseX, int mouseY) {
		Slot hovered = null;
		for (Slot slot : menu.slots) {
			if (slot != null && slot.isActive() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
				hovered = slot;
			}
		}
		return hovered;
	}

	private static int ROW_HEIGHT() {
		return SmartNodeContainerMenu.ROW_COUNT * 18 + 17;
	}
}
