package com.makomi.client.screen;

import com.makomi.menu.WirelessConverterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

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
}
