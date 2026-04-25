package com.makomi.client.network;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.render.QuickLinkFeedbackOverlayRenderer;
import com.makomi.client.screen.QuickLinkToolScreen;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.network.QuickLinkNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 快速连接工具客户端接包与交互壳。
 */
public final class QuickLinkNetworkClientHandlerSupport {
	private static final String QUICK_LINK_CHUNK_ACTIVATOR_TARGET_TOKEN = "chunk_activator";
	private static boolean collectTriggeredForCurrentAttack;
	private static boolean applyTriggeredForCurrentUse;
	private static PendingApplyBaselineRequest pendingApplyBaselineRequest;

	private QuickLinkNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.OpenQuickLinkEditorPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openEditor(payload.snapshot()));
		});
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.ApplyQuickLinkBaselinePayload.TYPE, (payload, context) -> {
			context.client().execute(() -> continuePendingApply(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.QuickLinkFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() ->
				QuickLinkFeedbackOverlayRenderer.showFeedback(payload.success(), payload.messageKey(), payload.messageArgs())
			);
		});
	}

	/**
	 * 注册客户端 quick-link 交互回调。
	 */
	public static void registerInteractionCallbacks() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client == null || client.options == null) {
				collectTriggeredForCurrentAttack = false;
				applyTriggeredForCurrentUse = false;
				return;
			}
			if (!client.options.keyAttack.isDown()) {
				collectTriggeredForCurrentAttack = false;
			}
			if (!client.options.keyUse.isDown()) {
				applyTriggeredForCurrentUse = false;
			}
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide && shouldSendCollectRequest(player == null ? null : Minecraft.getInstance(), hand, pos)) {
				if (collectTriggeredForCurrentAttack) {
					return InteractionResult.FAIL;
				}
				QuickLinkNetwork.CollectQuickLinkPayload payload = buildCollectPayload(player == null ? null : Minecraft.getInstance(), hand, pos);
				if (payload != null) {
					ClientPlayNetworking.send(payload);
				}
				collectTriggeredForCurrentAttack = true;
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide && shouldSendApplyRequest(player == null ? null : Minecraft.getInstance(), hand, hitResult.getBlockPos())) {
				if (applyTriggeredForCurrentUse) {
					return InteractionResult.FAIL;
				}
				QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload payload = buildApplyBaselineRequest(
					player == null ? null : Minecraft.getInstance(),
					hand,
					hitResult.getBlockPos()
				);
				if (payload != null) {
					pendingApplyBaselineRequest = new PendingApplyBaselineRequest(
						payload.dimensionKey(),
						payload.blockPosLong(),
						payload.expectedNodeTypeToken(),
						payload.expectedNodeSerial()
					);
					ClientPlayNetworking.send(payload);
				}
				applyTriggeredForCurrentUse = true;
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
	}

	/**
	 * 打开快速连接工具编辑界面。
	 */
	public static void openEditor(QuickLinkToolData.Snapshot snapshot) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.setScreen(new QuickLinkToolScreen(snapshot));
	}

	/**
	 * 判断本次左键是否应发送“快速采集”请求。
	 */
	private static boolean shouldSendCollectRequest(Minecraft minecraft, InteractionHand hand, BlockPos blockPos) {
		return resolveQuickLinkTarget(minecraft, hand, blockPos, false, false) != null;
	}

	/**
	 * 判断本次右键是否应发送“快速应用”请求。
	 */
	private static boolean shouldSendApplyRequest(Minecraft minecraft, InteractionHand hand, BlockPos blockPos) {
		return resolveQuickLinkTarget(minecraft, hand, blockPos, true, true) != null;
	}

	/**
	 * 构建“快速采集”请求，附带客户端当前命中的期望节点身份。
	 */
	private static QuickLinkNetwork.CollectQuickLinkPayload buildCollectPayload(Minecraft minecraft, InteractionHand hand, BlockPos blockPos) {
		ResolvedQuickLinkTarget target = resolveQuickLinkTarget(minecraft, hand, blockPos, false, false);
		if (target == null) {
			return null;
		}
		return new QuickLinkNetwork.CollectQuickLinkPayload(
			target.dimensionKey(),
			target.blockPosLong(),
			target.expectedNodeTypeToken(),
			target.expectedNodeSerial()
		);
	}

	/**
	 * 构建“快速应用 revision 预检”请求，附带客户端当前命中的期望目标身份。
	 */
	private static QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload buildApplyBaselineRequest(
		Minecraft minecraft,
		InteractionHand hand,
		BlockPos blockPos
	) {
		ResolvedQuickLinkTarget target = resolveQuickLinkTarget(minecraft, hand, blockPos, true, true);
		if (target == null) {
			return null;
		}
		return new QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload(
			target.dimensionKey(),
			target.blockPosLong(),
			target.expectedNodeTypeToken(),
			target.expectedNodeSerial()
		);
	}

	/**
	 * 解析当前客户端命中的 quick-link 目标。
	 * <p>
	 * 采集只会返回节点目标；应用则允许返回节点或过滤器目标。
	 * 过滤器目标统一编码为 `send/receive + serial=0`。
	 * </p>
	 */
	private static ResolvedQuickLinkTarget resolveQuickLinkTarget(
		Minecraft minecraft,
		InteractionHand hand,
		BlockPos blockPos,
		boolean requireStanding,
		boolean allowFilters
	) {
		if (!hasQuickLinkInteractionContext(minecraft, hand, requireStanding) || minecraft == null || minecraft.level == null) {
			return null;
		}
		BlockEntity blockEntity = minecraft.level.getBlockEntity(blockPos);
		if (blockEntity instanceof LinkRepeaterBlockEntity repeaterBlockEntity) {
			if (repeaterBlockEntity.getSerial() <= 0L) {
				return null;
			}
			if (!allowFilters) {
				QuickLinkToolData.Snapshot snapshot = minecraft.player == null
					? QuickLinkToolData.Snapshot.EMPTY
					: QuickLinkToolData.read(minecraft.player.getMainHandItem());
				return new ResolvedQuickLinkTarget(
					minecraft.level.dimension().location().toString(),
					blockPos.asLong(),
					LinkNodeSemantics.toSemanticName(resolveRepeaterCollectNodeType(snapshot)),
					repeaterBlockEntity.getSerial()
				);
			}
			return new ResolvedQuickLinkTarget(
				minecraft.level.dimension().location().toString(),
				blockPos.asLong(),
				LinkGuiDisplayContext.LINK_REPEATER,
				repeaterBlockEntity.getSerial()
			);
		}
		if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
			if (pairableNodeBlockEntity.getLinkNodeType() == null || pairableNodeBlockEntity.getSerial() <= 0L) {
				return null;
			}
			return new ResolvedQuickLinkTarget(
				minecraft.level.dimension().location().toString(),
				blockPos.asLong(),
				LinkNodeSemantics.toSemanticName(pairableNodeBlockEntity.getLinkNodeType()),
				pairableNodeBlockEntity.getSerial()
			);
		}
		if (allowFilters && blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity && filterBlockEntity.filterKind() != null) {
			return new ResolvedQuickLinkTarget(
				minecraft.level.dimension().location().toString(),
				blockPos.asLong(),
				filterBlockEntity.filterKind().token(),
				0L
			);
		}
		if (allowFilters && blockEntity instanceof LinkChunkActivatorBlockEntity) {
			return new ResolvedQuickLinkTarget(
				minecraft.level.dimension().location().toString(),
				blockPos.asLong(),
				QUICK_LINK_CHUNK_ACTIVATOR_TARGET_TOKEN,
				0L
			);
		}
		return null;
	}

	/**
	 * 解析转发器 quick-link 采集时应命中的逻辑身份。
	 */
	static LinkNodeType resolveRepeaterCollectNodeType(QuickLinkToolData.Snapshot snapshot) {
		return snapshot != null && snapshot.serialCacheType() == LinkNodeType.TRIGGER_SOURCE
			? LinkNodeType.TRIGGER_SOURCE
			: LinkNodeType.CORE;
	}

	/**
	 * 判断当前玩家状态是否允许继续走 quick-link 命中目标解析。
	 */
	private static boolean hasQuickLinkInteractionContext(
		Minecraft minecraft,
		InteractionHand hand,
		boolean requireStanding
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null) {
			return false;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return false;
		}
		return !requireStanding || !minecraft.player.isShiftKeyDown();
	}

	/**
	 * 收到服务端 apply revision 基线后，若仍匹配待处理目标，则自动继续发送正式 apply 请求。
	 */
	private static void continuePendingApply(QuickLinkNetwork.ApplyQuickLinkBaselinePayload payload) {
		if (!matchesPendingApplyRequest(payload, pendingApplyBaselineRequest)) {
			return;
		}
		pendingApplyBaselineRequest = null;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || !(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return;
		}
		ClientPlayNetworking.send(
			new QuickLinkNetwork.ApplyQuickLinkPayload(
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.expectedNodeTypeToken(),
				payload.expectedNodeSerial(),
				payload.coreRevision(),
				payload.sourceRevision()
			)
		);
	}

	/**
	 * 判断服务端返回的 apply 基线是否仍对应当前待处理目标。
	 */
	private static boolean matchesPendingApplyRequest(
		QuickLinkNetwork.ApplyQuickLinkBaselinePayload payload,
		PendingApplyBaselineRequest pendingRequest
	) {
		return payload != null
			&& pendingRequest != null
			&& pendingRequest.blockPosLong() == payload.blockPosLong()
			&& pendingRequest.expectedNodeSerial() == payload.expectedNodeSerial()
			&& pendingRequest.dimensionKey().equals(payload.dimensionKey())
			&& pendingRequest.expectedNodeTypeToken().equals(payload.expectedNodeTypeToken());
	}

	/**
	 * quick-link 目标请求的客户端本地快照。
	 * <p>
	 * `expectedNodeTypeToken/expectedNodeSerial` 在 apply 场景下既可表示节点身份，
	 * 也可表示过滤器目标（`send/receive + 0`）。
	 * </p>
	 */
	private record ResolvedQuickLinkTarget(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) {
	}

	/**
	 * 待续发正式 apply 的 quick-link 目标快照。
	 */
	private record PendingApplyBaselineRequest(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) {
	}
}
