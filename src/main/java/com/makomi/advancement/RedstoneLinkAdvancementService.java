package com.makomi.advancement;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkedTargetDispatchService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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

	public static final Identifier WIRELESS_AGE = id("wireless_age");
	public static final Identifier COME_FIND_ME_IN_THE_END = id("come_find_me_in_the_end");
	public static final Identifier MASTER_STRATEGIST = id("master_strategist");
	public static final Identifier CONSTELLATION = id("constellation");

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
		if (!isEligibleGameMode(player)) {
			return;
		}
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
		if (!isEligibleGameMode(player)) {
			return;
		}
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
		return isEligibleGameMode(resolveGameType(player));
	}

	/**
	 * 判断给定模式是否允许进入成就发放流程。
	 */
	static boolean isEligibleGameMode(GameType gameType) {
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
	 * 判断当前是否仍需要继续处理指定成就。
	 */
	static boolean shouldProcessAward(GameType gameType, boolean alreadyAwarded) {
		return isEligibleGameMode(gameType) && !alreadyAwarded;
	}

	/**
	 * 解析玩家当前模式。
	 */
	static GameType resolveGameType(ServerPlayer player) {
		if (player == null || player.gameMode == null) {
			return null;
		}
		return player.gameMode.getGameModeForPlayer();
	}

	/**
	 * 解析指定成就定义；解析失败时返回 `null`。
	 */
	static AdvancementHolder resolveAdvancement(ServerPlayer player, Identifier advancementId) {
		if (player == null || player.level() == null || player.level().getServer() == null || advancementId == null) {
			return null;
		}
		return player.level().getServer().getAdvancements().get(advancementId);
	}

	/**
	 * 判断玩家是否已经完成指定成就。
	 */
	static boolean isAdvancementAlreadyAwarded(ServerPlayer player, AdvancementHolder advancement) {
		if (player == null || advancement == null) {
			return false;
		}
		return player.getAdvancements().getOrStartProgress(advancement).isDone();
	}

	/**
	 * 解析“本次仍可发放”的成就定义；若模式不合法、成就不存在或已完成，则返回 `null`。
	 */
	static AdvancementHolder resolveAwardableAdvancement(ServerPlayer player, Identifier advancementId) {
		AdvancementHolder advancement = resolveAdvancement(player, advancementId);
		if (advancement == null) {
			return null;
		}
		return shouldProcessAward(resolveGameType(player), isAdvancementAlreadyAwarded(player, advancement))
			? advancement
			: null;
	}

	/**
	 * 发放已解析且确认仍待处理的成就。
	 */
	static boolean award(ServerPlayer player, AdvancementHolder advancement) {
		if (player == null || advancement == null) {
			return false;
		}
		return player.getAdvancements().award(advancement, DEFAULT_CRITERION);
	}

	/**
	 * 在资格满足且尚未发放时发放指定成就。
	 */
	static boolean awardIfEligible(ServerPlayer player, Identifier advancementId) {
		return award(player, resolveAwardableAdvancement(player, advancementId));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}
}
