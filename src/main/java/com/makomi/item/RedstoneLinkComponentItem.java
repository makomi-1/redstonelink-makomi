package com.makomi.item;

import com.makomi.advancement.RedstoneLinkAdvancementService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 红石连接原件物品。
 * <p>
 * 该物品承接“无线时代”根节点成就：当玩家真实完成一次合成并拿到产物时，
 * 在服务端 `onCraftedBy(...)` 回调中发放成就。
 * </p>
 */
public class RedstoneLinkComponentItem extends Item {
	public RedstoneLinkComponentItem(Properties properties) {
		super(properties);
	}

	@Override
	public void onCraftedBy(ItemStack stack, Level level, Player player) {
		super.onCraftedBy(stack, level, player);
		if (player instanceof ServerPlayer serverPlayer) {
			RedstoneLinkAdvancementService.awardWirelessAge(serverPlayer);
		}
	}
}
