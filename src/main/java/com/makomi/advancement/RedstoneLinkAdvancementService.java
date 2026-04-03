package com.makomi.advancement;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkedTargetDispatchService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;

/**
 * RedstoneLink 成就发放服务。
 * <p>
 * 本次成就条件均为固定的一次性业务事件，因此统一采用
 * `minecraft:impossible` + 服务端手动 `award(...)` 的方式发放，
 * 避免为少量固定条件引入额外的自定义 criterion trigger 注册复杂度。
 * </p>
 */
public final class RedstoneLinkAdvancementService {
	private static final String DEFAULT_CRITERION = "unlock";

	public static final ResourceLocation WIRELESS_AGE = id("wireless_age");
	public static final ResourceLocation COME_FIND_ME_IN_THE_END = id("come_find_me_in_the_end");
	public static final ResourceLocation MASTER_STRATEGIST = id("master_strategist");
	public static final ResourceLocation CONSTELLATION = id("constellation");

	private RedstoneLinkAdvancementService() {
	}

	/**
	 * 发放根节点“无线时代”。
	 */
	public static void awardWirelessAge(ServerPlayer player) {
		awardIfEligible(player, WIRELESS_AGE);
	}

	/**
	 * 发放“喂，来末地找我”。
	 */
	public static void awardComeFindMeInTheEnd(ServerPlayer player) {
		awardIfEligible(player, COME_FIND_ME_IN_THE_END);
	}

	/**
	 * 当本次激活符合“主世界 -> 末地成功激活”时发放成就。
	 */
	public static void awardComeFindMeInTheEndIfMatched(
		ServerPlayer player,
		ResourceKey<Level> sourceDimension,
		LinkedTargetDispatchService.DispatchSummary dispatchSummary
	) {
		if (shouldAwardComeFindMeInTheEnd(sourceDimension, dispatchSummary)) {
			awardComeFindMeInTheEnd(player);
		}
	}

	/**
	 * 发放“运筹帷幄”。
	 */
	public static void awardMasterStrategist(ServerPlayer player) {
		awardIfEligible(player, MASTER_STRATEGIST);
	}

	/**
	 * 发放“星罗棋布”。
	 */
	public static void awardConstellation(ServerPlayer player) {
		awardIfEligible(player, CONSTELLATION);
	}

	/**
	 * 当来源连接数首次达到 `>=5` 时发放“星罗棋布”。
	 */
	public static void awardConstellationIfThresholdReached(
		ServerPlayer player,
		int previousTargetCount,
		int currentTargetCount
	) {
		if (reachesConstellationThreshold(previousTargetCount, currentTargetCount)) {
			awardConstellation(player);
		}
	}

	/**
	 * 判断玩家当前是否处于允许发放成就的模式。
	 * <p>
	 * Hardcore 在服务端仍表现为 `SURVIVAL`，因此这里放行 `SURVIVAL`
	 * 即可同时覆盖生存与极限语义。
	 * </p>
	 */
	static boolean isEligibleGameMode(ServerPlayer player) {
		if (player == null || player.gameMode == null) {
			return false;
		}
		GameType gameType = player.gameMode.getGameModeForPlayer();
		return gameType != null && gameType.isSurvival();
	}

	/**
	 * 判断本次派发是否满足“主世界 -> 末地成功激活”的发奖条件。
	 */
	static boolean shouldAwardComeFindMeInTheEnd(
		ResourceKey<Level> sourceDimension,
		LinkedTargetDispatchService.DispatchSummary dispatchSummary
	) {
		return Level.OVERWORLD.equals(sourceDimension)
			&& dispatchSummary != null
			&& dispatchSummary.hasHandledTargetInDimension(Level.END);
	}

	/**
	 * 判断是否从“未达阈值”首次跨过 `>=5`。
	 */
	static boolean reachesConstellationThreshold(int previousTargetCount, int currentTargetCount) {
		int safePreviousCount = Math.max(0, previousTargetCount);
		int safeCurrentCount = Math.max(0, currentTargetCount);
		return safePreviousCount < 5 && safeCurrentCount >= 5;
	}

	/**
	 * 在资格满足时发放指定成就。
	 */
	static boolean awardIfEligible(ServerPlayer player, ResourceLocation advancementId) {
		if (!isEligibleGameMode(player) || advancementId == null) {
			return false;
		}
		AdvancementHolder advancement = player.server.getAdvancements().get(advancementId);
		if (advancement == null) {
			return false;
		}
		return player.getAdvancements().award(advancement, DEFAULT_CRITERION);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}
}
