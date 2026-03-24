package com.makomi.config;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/**
 * 配对界面交互策略辅助。
 * <p>
 * 负责基于当前配置判断不同入口是否允许打开配对界面。
 * </p>
 */
final class RedstoneLinkConfigInteractionSupport {
	private RedstoneLinkConfigInteractionSupport() {
	}

	/**
	 * 统一“手持物品打开配对界面”条件校验。
	 */
	static boolean canOpenPairingByHeldItem(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (RedstoneLinkConfig.requireSneakToOpenPairing() && !player.isShiftKeyDown()) {
			return false;
		}
		if (RedstoneLinkConfig.requireEmptyOffhandToOpenPairing() && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 统一“遥控器打开配对界面”条件校验。
	 */
	static boolean canOpenPairingByLinker(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (RedstoneLinkConfig.requireSneakToOpenLinkerPairing() && !player.isShiftKeyDown()) {
			return false;
		}
		if (RedstoneLinkConfig.requireEmptyOffhandToOpenPairing() && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 统一“已放置方块打开配对界面”条件校验。
	 */
	static boolean canOpenPairingByPlacedBlock(Player player) {
		if (!player.getMainHandItem().isEmpty()) {
			return false;
		}
		if (RedstoneLinkConfig.requireSneakToOpenPairing() && !player.isShiftKeyDown()) {
			return false;
		}
		if (RedstoneLinkConfig.requireEmptyOffhandToOpenPairing() && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}
}
