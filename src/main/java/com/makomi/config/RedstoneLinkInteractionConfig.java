package com.makomi.config;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/**
 * 配对界面交互门禁配置。
 */
public record RedstoneLinkInteractionConfig(
	boolean requireSneakToOpenPairing,
	boolean requireSneakToOpenLinkerPairing,
	boolean requireEmptyOffhandToOpenPairing
) {
	/**
	 * 统一“手持物品打开配对界面”条件校验。
	 */
	public boolean canOpenPairingByHeldItem(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (requireSneakToOpenPairing && !player.isShiftKeyDown()) {
			return false;
		}
		if (requireEmptyOffhandToOpenPairing && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 统一“遥控器打开配对界面”条件校验。
	 */
	public boolean canOpenPairingByLinker(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (requireSneakToOpenLinkerPairing && !player.isShiftKeyDown()) {
			return false;
		}
		if (requireEmptyOffhandToOpenPairing && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 统一“已放置方块打开配对界面”条件校验。
	 */
	public boolean canOpenPairingByPlacedBlock(Player player) {
		if (!player.getMainHandItem().isEmpty()) {
			return false;
		}
		if (requireSneakToOpenPairing && !player.isShiftKeyDown()) {
			return false;
		}
		if (requireEmptyOffhandToOpenPairing && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}
}
