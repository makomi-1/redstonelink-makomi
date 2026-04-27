package com.makomi.item;

import com.makomi.RedstoneLink;
import com.makomi.data.GraphSnapshotExportService;
import com.makomi.data.WebFeaturePermissionService;
import com.makomi.network.GraphExportNetworkSupport;
import java.io.IOException;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 图可视化编辑器物品。
 * <p>
 * 主手右键时复用现有 graph 去重导出链路，并在客户端本地网页桥中自动打开 graph 页面。
 * </p>
 */
public class GraphVisualEditorItem extends Item {
	public GraphVisualEditorItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		exportGraph(level, player);
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getHand() != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		exportGraph(context.getLevel(), player);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
		java.util.function.Consumer<Component> tooltipAdder,
		TooltipFlag tooltipFlag
	) {
		List<Component> tooltipComponents = new java.util.ArrayList<>();
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.graph_visual_editor.export"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.graph_visual_editor.dedupe"));
		tooltipComponents.forEach(tooltipAdder);
		super.appendHoverText(stack, context, tooltipDisplay, tooltipAdder, tooltipFlag);
	}

	/**
	 * 在服务端导出 graph 并通过客户端网页桥自动打开页面。
	 */
	private static void exportGraph(Level level, Player player) {
		if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (!WebFeaturePermissionService.canUseGraphFeature(serverPlayer)) {
			serverPlayer.displayClientMessage(Component.translatable("message.redstonelink.permission.insufficient"), true);
			return;
		}
		try {
			GraphSnapshotExportService.ExportBundle exportBundle = GraphExportNetworkSupport.exportVisibleSerialGraph(
				serverPlayer,
				false,
				true
			);
			serverPlayer.displayClientMessage(
				Component.translatable("message.redstonelink.graph.export.done", exportBundle.fileName()),
				true
			);
		} catch (IOException | RuntimeException exception) {
			RedstoneLink.LOGGER.warn("图可视化编辑器导出 graph 失败: player={}", serverPlayer.getScoreboardName(), exception);
			serverPlayer.displayClientMessage(Component.translatable("message.redstonelink.graph.export.failed"), true);
		}
	}
}
