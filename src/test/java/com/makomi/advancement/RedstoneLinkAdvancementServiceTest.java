package com.makomi.advancement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkedTargetDispatchService;
import com.makomi.testsupport.TestMinecraftSupport;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 成就服务关键判定测试。
 */
@Tag("stable-core")
class RedstoneLinkAdvancementServiceTest {
	static {
		TestMinecraftSupport.bootstrapMinecraft();
	}

	/**
	 * “主世界 -> 末地成功激活”只应在来源为主世界且成功目标包含末地时成立。
	 */
	@Test
	void shouldAwardComeFindMeInTheEndShouldRequireOverworldAndHandledEndTarget() {
		LinkedTargetDispatchService.DispatchSummary summaryWithEndTarget = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			9L,
			LinkNodeType.CORE,
			2,
			1,
			List.of(),
			List.of(),
			Set.of(Level.END)
		);
		LinkedTargetDispatchService.DispatchSummary summaryWithoutEndTarget = new LinkedTargetDispatchService.DispatchSummary(
			LinkNodeType.TRIGGER_SOURCE,
			9L,
			LinkNodeType.CORE,
			2,
			1,
			List.of(),
			List.of(),
			Set.of(Level.NETHER)
		);

		assertTrue(RedstoneLinkAdvancementService.shouldAwardComeFindMeInTheEnd(Level.OVERWORLD, summaryWithEndTarget));
		assertFalse(RedstoneLinkAdvancementService.shouldAwardComeFindMeInTheEnd(Level.NETHER, summaryWithEndTarget));
		assertFalse(RedstoneLinkAdvancementService.shouldAwardComeFindMeInTheEnd(Level.OVERWORLD, summaryWithoutEndTarget));
	}

	/**
	 * “星罗棋布”只应在首次跨过 `>=5` 阈值时成立。
	 */
	@Test
	void reachesConstellationThresholdShouldOnlyTriggerOnFirstCrossing() {
		assertTrue(RedstoneLinkAdvancementService.reachesConstellationThreshold(4, 5));
		assertTrue(RedstoneLinkAdvancementService.reachesConstellationThreshold(0, 8));
		assertFalse(RedstoneLinkAdvancementService.reachesConstellationThreshold(5, 5));
		assertFalse(RedstoneLinkAdvancementService.reachesConstellationThreshold(6, 8));
		assertFalse(RedstoneLinkAdvancementService.reachesConstellationThreshold(1, 4));
	}

	/**
	 * 只有生存类模式才允许进入成就处理。
	 */
	@Test
	void isEligibleGameModeShouldRejectCreativeAndNull() {
		assertTrue(RedstoneLinkAdvancementService.isEligibleGameMode(GameType.SURVIVAL));
		assertFalse(RedstoneLinkAdvancementService.isEligibleGameMode(GameType.CREATIVE));
		assertFalse(RedstoneLinkAdvancementService.isEligibleGameMode(GameType.SPECTATOR));
		assertFalse(RedstoneLinkAdvancementService.isEligibleGameMode((GameType) null));
	}

	/**
	 * 创造模式或已达成成就时，都不应继续进入后续成就判定。
	 */
	@Test
	void shouldProcessAwardShouldShortCircuitCreativeAndAlreadyAwarded() {
		assertTrue(RedstoneLinkAdvancementService.shouldProcessAward(GameType.SURVIVAL, false));
		assertFalse(RedstoneLinkAdvancementService.shouldProcessAward(GameType.CREATIVE, false));
		assertFalse(RedstoneLinkAdvancementService.shouldProcessAward(GameType.SURVIVAL, true));
		assertFalse(RedstoneLinkAdvancementService.shouldProcessAward((GameType) null, false));
	}
}
