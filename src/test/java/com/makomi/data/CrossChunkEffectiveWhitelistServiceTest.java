package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.testsupport.TestMinecraftSupport;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CrossChunkEffectiveWhitelistService resident 聚合契约测试。
 */
@Tag("stable-core")
class CrossChunkEffectiveWhitelistServiceTest {
	static {
		TestMinecraftSupport.bootstrapMinecraft();
	}

	/**
	 * 手动 resident 与激活态区块激活器 resident 应按 role/type/serial 去重计数。
	 */
	@Test
	void countDistinctResidentsShouldDeduplicateManualAndActivatorResidents() {
		CrossChunkWhitelistSavedData whitelistSavedData = new CrossChunkWhitelistSavedData();
		PlacedChunkActivatorSavedData activatorSavedData = new PlacedChunkActivatorSavedData();
		whitelistSavedData.add(LinkNodeType.TRIGGER_SOURCE, 11L, LinkNodeSemantics.Role.SOURCE, true);
		whitelistSavedData.add(LinkNodeType.CORE, 21L, LinkNodeSemantics.Role.TARGET, true);
		activatorSavedData.upsert(
			Level.OVERWORLD,
			new BlockPos(1, 64, 1),
			new ChunkActivatorConfigStateSnapshot(
				LinkNodeType.TRIGGER_SOURCE,
				new ChunkActivatorConfigSnapshot("11/12", ChunkActivatorMode.RESIDENT),
				new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			),
			"",
			true
		);
		activatorSavedData.upsert(
			Level.OVERWORLD,
			new BlockPos(2, 64, 2),
			new ChunkActivatorConfigStateSnapshot(
				LinkNodeType.CORE,
				new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
				new ChunkActivatorConfigSnapshot("21/22", ChunkActivatorMode.RESIDENT)
			),
			"",
			true
		);

		assertEquals(4, CrossChunkEffectiveWhitelistService.countDistinctResidents(whitelistSavedData, activatorSavedData));
	}

	/**
	 * resident 预估计数在手动覆盖与区块激活器覆盖时，应替换旧贡献而不是把旧值继续累加。
	 */
	@Test
	void countDistinctResidentsAfterChangesShouldReplacePreviousContribution() {
		CrossChunkWhitelistSavedData whitelistSavedData = new CrossChunkWhitelistSavedData();
		PlacedChunkActivatorSavedData activatorSavedData = new PlacedChunkActivatorSavedData();
		BlockPos activatorPos = new BlockPos(4, 64, 4);
		whitelistSavedData.add(LinkNodeType.TRIGGER_SOURCE, 11L, LinkNodeSemantics.Role.SOURCE, true);
		whitelistSavedData.add(LinkNodeType.CORE, 21L, LinkNodeSemantics.Role.TARGET, true);
		activatorSavedData.upsert(
			Level.OVERWORLD,
			activatorPos,
			new ChunkActivatorConfigStateSnapshot(
				LinkNodeType.TRIGGER_SOURCE,
				new ChunkActivatorConfigSnapshot("11/12", ChunkActivatorMode.RESIDENT),
				new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			),
			"",
			true
		);

		assertEquals(
			5,
			CrossChunkEffectiveWhitelistService.countDistinctResidentsAfterManualResidentChange(
				whitelistSavedData,
				activatorSavedData,
				LinkNodeType.TRIGGER_SOURCE,
				LinkNodeSemantics.Role.SOURCE,
				Set.of(11L, 13L, 14L)
			)
		);
		assertEquals(
			3,
			CrossChunkEffectiveWhitelistService.countDistinctResidentsAfterActivatorChange(
				whitelistSavedData,
				activatorSavedData,
				Level.OVERWORLD,
				activatorPos,
				new ChunkActivatorConfigStateSnapshot(
					LinkNodeType.CORE,
					new ChunkActivatorConfigSnapshot("11/12", ChunkActivatorMode.RESIDENT),
					new ChunkActivatorConfigSnapshot("21/22", ChunkActivatorMode.RESIDENT)
				),
				true
			)
		);
	}
}
