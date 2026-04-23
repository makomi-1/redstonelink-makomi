package com.makomi.item;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterItemData;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkFilterTargetMode;
import com.makomi.data.NodeAliasDisplayUtil;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import com.makomi.network.LinkFilterNetwork;

/**
 * 过滤器方块物品。
 * <p>
 * 统一为 `send/receive` 过滤器补齐与其它节点一致风格的 tooltip，
 * 但只展示过滤器真实具备的服务对象与编辑器使用提示，
 * 不伪造 `serial/current links` 这类仅节点物品才有的数据。
 * </p>
 */
public class LinkFilterBlockItem extends BlockItem {
	private final LinkFilterKind filterKind;

	/**
	 * @param block 过滤器方块
	 * @param properties 物品属性
	 * @param filterKind 过滤器种类；决定服务对象文案
	 */
	public LinkFilterBlockItem(Block block, Item.Properties properties, LinkFilterKind filterKind) {
		super(block, properties);
		this.filterKind = Objects.requireNonNull(filterKind);
	}

	/**
	 * @return 当前物品对应的过滤器种类
	 */
	public LinkFilterKind filterKind() {
		return filterKind;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		if (shouldOpenEditor(player, hand)) {
			openEditor(level, player, heldStack);
			return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
		}
		return InteractionResultHolder.pass(heldStack);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return super.useOn(context);
		}
		if (shouldOpenEditor(player, context.getHand())) {
			openEditor(context.getLevel(), player, context.getItemInHand());
			return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
		}
		return super.useOn(context);
	}

	/**
	 * 为过滤器物品追加服务对象与编辑器打开提示。
	 */
	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		LinkFilterConfigSnapshot snapshot = LinkFilterItemData.read(stack);
		String displayAlias = NodeAliasDisplayUtil.normalizeAlias(LinkFilterItemData.getDisplayAlias(stack));
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.alias",
				displayAlias.isEmpty() ? "-" : displayAlias
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.service_target",
				LinkNodeSemantics.toSemanticName(filterKind.servicedNodeType())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.target_mode",
				targetModeLabel(snapshot)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.target_value",
				LinkFilterItemData.buildTooltipTargetText(
					snapshot,
					LinkFilterItemData.getNodeSetDisplayTexts(stack),
					TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS
				)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.node_set_mode",
				nodeSetModeLabel(snapshot)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.signal_threshold_source",
				thresholdSourceLabel(snapshot)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.fixed_threshold",
				Integer.toString(snapshot.fixedSignalThreshold())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.signal_mode",
				signalModeLabel(snapshot)
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.link_filter.open_editor"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 判断当前手势是否允许打开手持过滤器编辑器。
	 */
	private static boolean shouldOpenEditor(Player player, InteractionHand hand) {
		return RedstoneLinkConfig.canOpenPairingByHeldItem(player, hand);
	}

	/**
	 * 在服务端打开主手过滤器编辑器。
	 */
	private void openEditor(Level level, Player player, ItemStack stack) {
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			LinkFilterNetwork.openHeldItemEditor(serverPlayer, stack, filterKind);
		}
	}

	/**
	 * 构建节点集模式 tooltip 文案。
	 */
	private static Component nodeSetModeLabel(LinkFilterConfigSnapshot snapshot) {
		return switch (snapshot.nodeSetMode()) {
			case WHITELIST -> Component.translatable("screen.redstonelink.link_filter.node_set_mode.whitelist");
			case BLOCKLIST -> Component.translatable("screen.redstonelink.link_filter.node_set_mode.blocklist");
			case DISABLED -> Component.translatable("screen.redstonelink.link_filter.node_set_mode.disabled");
		};
	}

	/**
	 * 构建过滤目标模式 tooltip 文案。
	 */
	private static Component targetModeLabel(LinkFilterConfigSnapshot snapshot) {
		LinkFilterTargetMode targetMode = snapshot == null ? LinkFilterTargetMode.SERIAL : snapshot.targetMode();
		return switch (targetMode) {
			case SERIAL -> Component.translatable("screen.redstonelink.link_filter.target_mode.serial");
			case CHANNEL -> Component.translatable("screen.redstonelink.link_filter.target_mode.channel");
		};
	}

	/**
	 * 构建阈值来源 tooltip 文案。
	 */
	private static Component thresholdSourceLabel(LinkFilterConfigSnapshot snapshot) {
		return switch (snapshot.signalThresholdSource()) {
			case FIXED_INPUT -> Component.translatable("screen.redstonelink.link_filter.threshold_source.fixed_input");
			case NEIGHBOR_MAX_INPUT -> Component.translatable("screen.redstonelink.link_filter.threshold_source.neighbor_max_input");
		};
	}

	/**
	 * 构建信号模式 tooltip 文案。
	 */
	private static Component signalModeLabel(LinkFilterConfigSnapshot snapshot) {
		return switch (snapshot.signalMode()) {
			case UPPER_BOUND -> Component.translatable("screen.redstonelink.link_filter.signal_mode.upper_bound");
			case LOWER_BOUND -> Component.translatable("screen.redstonelink.link_filter.signal_mode.lower_bound");
			case DISABLED -> Component.translatable("screen.redstonelink.link_filter.signal_mode.disabled");
		};
	}
}
