package com.makomi.item;

import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkNodeSemantics;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

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
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.link_filter.service_target",
				LinkNodeSemantics.toSemanticName(filterKind.servicedNodeType())
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.link_filter.open_editor"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}
}
