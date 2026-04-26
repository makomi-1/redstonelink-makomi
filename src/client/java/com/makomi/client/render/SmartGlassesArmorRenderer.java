package com.makomi.client.render;

import com.makomi.RedstoneLink;
import com.makomi.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 智能眼镜半透明护甲渲染器。
 * <p>
 * 继续复用默认头盔模型，只把穿戴渲染层切到半透明，
 * 这样无需改模型即可得到“眼镜佩戴时半透明显示”的效果。
 * </p>
 */
public final class SmartGlassesArmorRenderer implements ArmorRenderer {
	private static final ResourceLocation SMART_GLASSES_ARMOR_TEXTURE = ResourceLocation.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/models/armor/smart_glasses_layer_1.png"
	);
	private static final int SEMI_TRANSPARENT_WHITE = 0xB8FFFFFF;

	private SmartGlassesArmorRenderer() {
	}

	/**
	 * 注册智能眼镜穿戴渲染器。
	 */
	public static void register() {
		ArmorRenderer.register(new SmartGlassesArmorRenderer(), ModItems.SMART_GLASSES);
	}

	@Override
	public void render(
		PoseStack matrices,
		MultiBufferSource vertexConsumers,
		ItemStack stack,
		LivingEntity entity,
		EquipmentSlot slot,
		int light,
		HumanoidModel<LivingEntity> contextModel
	) {
		if (slot != EquipmentSlot.HEAD || contextModel == null) {
			return;
		}
		contextModel.setAllVisible(false);
		contextModel.head.visible = true;
		contextModel.hat.visible = true;
		VertexConsumer vertexConsumer = ItemRenderer.getArmorFoilBuffer(
			vertexConsumers,
			RenderType.entityTranslucent(SMART_GLASSES_ARMOR_TEXTURE),
			stack.hasFoil()
		);
		contextModel.renderToBuffer(
			matrices,
			vertexConsumer,
			light,
			OverlayTexture.NO_OVERLAY,
			SEMI_TRANSPARENT_WHITE
		);
	}
}
