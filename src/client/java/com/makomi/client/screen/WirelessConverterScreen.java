package com.makomi.client.screen;

import com.makomi.menu.WirelessConverterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 无线化转化器界面。
 * <p>
 * 先复用原版炉子的静态容器贴图，
 * 避免在当前映射下额外绑定 recipe-book 类型。
 * </p>
 */
public class WirelessConverterScreen extends AbstractContainerScreen<WirelessConverterMenu> {
	private static final ResourceLocation CONTAINER_TEXTURE = ResourceLocation.withDefaultNamespace(
		"textures/gui/container/furnace.png"
	);

	public WirelessConverterScreen(WirelessConverterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		imageWidth = 176;
		imageHeight = 166;
		inventoryLabelY = imageHeight - 94;
	}

	@Override
	protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
		guiGraphics.blit(CONTAINER_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
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

	/**
	 * 为无线化转化器容器区、玩家背包区与热键栏统一补一层槽位 tooltip。
	 * <p>
	 * 与智能节点容器保持同一修复口径：不依赖父类内部 hoveredSlot，
	 * 而是在 render 末尾重新按原版命中逻辑解析一次悬停槽位。
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
		guiGraphics.renderTooltip(font, getTooltipFromContainerItem(stack), stack.getTooltipImage(), mouseX, mouseY);
	}

	/**
	 * 对照原版容器界面的槽位命中逻辑重新解析当前悬停槽位。
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
}
