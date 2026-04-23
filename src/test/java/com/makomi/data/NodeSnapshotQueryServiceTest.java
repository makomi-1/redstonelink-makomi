package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkCrossChunkConfig;
import com.makomi.config.RedstoneLinkCrossChunkRetryConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * NodeSnapshotQueryService 物品快照查询契约测试。
 */
@Tag("stable-core")
class NodeSnapshotQueryServiceTest {
	/**
	 * 物品快照应直接保留原始连接集合，不走隐私裁剪。
	 */
	@Test
	void buildItemSnapshotLinksShouldKeepUnfilteredVisibleTargets() {
		NodeLinksSnapshot snapshot = NodeSnapshotQueryService.buildItemSnapshotLinks(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			12L,
			new LinkedHashSet<>(List.of(7L, 3L, -1L))
		);

		assertEquals(LinkNodeType.TRIGGER_SOURCE, snapshot.sourceIdentity().nodeType());
		assertEquals(12L, snapshot.sourceIdentity().serial());
		assertEquals(java.util.List.of(3L, 7L), snapshot.visibleTargets());
		assertEquals(java.util.List.of("#3", "#7"), snapshot.visibleTargetDisplayTexts());
		assertFalse(snapshot.masked());
		assertEquals(0L, snapshot.graphRevision());
		assertEquals(0L, snapshot.sourceRevision());
		assertEquals(0L, snapshot.coreRevision());
	}

	/**
	 * 物品快照在提供运行时存档上下文时，应透出当前 revision 基线。
	 */
	@Test
	void buildItemSnapshotLinksShouldExposeRevisionBaselineWhenSavedDataProvided() {
		LinkSavedData savedData = new LinkSavedData();
		savedData.toggleTriggerSourceCoreLink(15L, 31L);

		NodeLinksSnapshot snapshot = NodeSnapshotQueryService.buildItemSnapshotLinks(
			null,
			LinkNodeType.TRIGGER_SOURCE,
			15L,
			new LinkedHashSet<>(List.of(31L)),
			savedData
		);

		assertEquals(1L, snapshot.graphRevision());
		assertEquals(1L, snapshot.sourceRevision());
		assertEquals(0L, snapshot.coreRevision());
	}

	/**
	 * core 物品快照在提供运行时存档上下文时，应同时透出当前 core revision。
	 */
	@Test
	void buildItemSnapshotLinksShouldExposeCoreRevisionForCoreTarget() {
		LinkSavedData savedData = new LinkSavedData();
		savedData.toggleTriggerSourceCoreLink(15L, 31L);

		NodeLinksSnapshot snapshot = NodeSnapshotQueryService.buildItemSnapshotLinks(
			null,
			LinkNodeType.CORE,
			31L,
			new LinkedHashSet<>(List.of(15L)),
			savedData
		);

		assertEquals(1L, snapshot.graphRevision());
		assertEquals(0L, snapshot.sourceRevision());
		assertEquals(1L, snapshot.coreRevision());
	}

	/**
	 * resident 身份应优先于普通强加载身份。
	 */
	@Test
	void resolveCrossChunkNodeIdentityShouldPreferResidentOverForceLoad() {
		CrossChunkWhitelistSavedData whitelistSavedData = new CrossChunkWhitelistSavedData();
		whitelistSavedData.add(LinkNodeType.TRIGGER_SOURCE, 12L, LinkNodeSemantics.Role.SOURCE, true);

		CrossChunkNodeIdentity identity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.TRIGGER_SOURCE,
			12L,
			whitelistSavedData,
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of()
			)
		);

		assertEquals(CrossChunkNodeIdentity.RESIDENT, identity);
	}

	/**
	 * whitelist/preset 命中时应显示为强加载。
	 */
	@Test
	void resolveCrossChunkNodeIdentityShouldResolveForceLoadFromWhitelistAndPreset() {
		CrossChunkWhitelistSavedData whitelistSavedData = new CrossChunkWhitelistSavedData();
		whitelistSavedData.add(LinkNodeType.CORE, 34L, LinkNodeSemantics.Role.TARGET, false);

		CrossChunkNodeIdentity whitelistIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.CORE,
			34L,
			whitelistSavedData,
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of()
			)
		);
		CrossChunkNodeIdentity presetIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.TRIGGER_SOURCE,
			91L,
			new CrossChunkWhitelistSavedData(),
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(LinkNodeType.TRIGGER_SOURCE, Set.of(91L)),
				Map.of()
			)
		);

		assertEquals(CrossChunkNodeIdentity.FORCE_LOAD, whitelistIdentity);
		assertEquals(CrossChunkNodeIdentity.FORCE_LOAD, presetIdentity);
	}

	/**
	 * 区块激活器贡献也应参与跨区块身份解析。
	 */
	@Test
	void resolveCrossChunkNodeIdentityShouldIncludeChunkActivatorContribution() {
		PlacedChunkActivatorSavedData activatorSavedData = new PlacedChunkActivatorSavedData();
		activatorSavedData.upsert(
			net.minecraft.world.level.Level.OVERWORLD,
			BlockPos.ZERO,
			new ChunkActivatorConfigStateSnapshot(
				LinkNodeType.TRIGGER_SOURCE,
				new ChunkActivatorConfigSnapshot("54", ChunkActivatorMode.FORCE_LOAD),
				new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			),
			"",
			true
		);
		activatorSavedData.upsert(
			net.minecraft.world.level.Level.OVERWORLD,
			new BlockPos(1, 64, 1),
			new ChunkActivatorConfigStateSnapshot(
				LinkNodeType.TRIGGER_SOURCE,
				new ChunkActivatorConfigSnapshot("91", ChunkActivatorMode.RESIDENT),
				new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			),
			"",
			true
		);
		activatorSavedData.upsert(
			net.minecraft.world.level.Level.OVERWORLD,
			new BlockPos(2, 64, 2),
			new ChunkActivatorConfigStateSnapshot(
				LinkNodeType.CORE,
				new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
				new ChunkActivatorConfigSnapshot("34", ChunkActivatorMode.FORCE_LOAD)
			),
			"",
			true
		);

		CrossChunkNodeIdentity forceLoadIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.TRIGGER_SOURCE,
			54L,
			new CrossChunkWhitelistSavedData(),
			activatorSavedData,
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of()
			)
		);
		CrossChunkNodeIdentity residentIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.TRIGGER_SOURCE,
			91L,
			new CrossChunkWhitelistSavedData(),
			activatorSavedData,
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of()
			)
		);
		CrossChunkNodeIdentity coreIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.CORE,
			34L,
			new CrossChunkWhitelistSavedData(),
			activatorSavedData,
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.WHITELIST,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of()
			)
		);

		assertEquals(CrossChunkNodeIdentity.FORCE_LOAD, forceLoadIdentity);
		assertEquals(CrossChunkNodeIdentity.RESIDENT, residentIdentity);
		assertEquals(CrossChunkNodeIdentity.FORCE_LOAD, coreIdentity);
	}

	/**
	 * `mode=all` 仅对配置允许类型生效；未允许时应回退普通身份。
	 */
	@Test
	void resolveCrossChunkNodeIdentityShouldRespectAllowedTypesInAllMode() {
		CrossChunkNodeIdentity allowedIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.TRIGGER_SOURCE,
			77L,
			new CrossChunkWhitelistSavedData(),
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.ALL,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of()
			)
		);
		CrossChunkNodeIdentity disallowedIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
			LinkNodeType.CORE,
			77L,
			new CrossChunkWhitelistSavedData(),
			crossChunkConfig(
				true,
				RedstoneLinkConfig.CrossChunkForceLoadMode.ALL,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(),
				Map.of(),
				Map.of()
			)
		);

		assertEquals(CrossChunkNodeIdentity.FORCE_LOAD, allowedIdentity);
		assertEquals(CrossChunkNodeIdentity.NORMAL, disallowedIdentity);
	}

	private static RedstoneLinkCrossChunkConfig crossChunkConfig(
		boolean forceLoadEnabled,
		RedstoneLinkConfig.CrossChunkForceLoadMode forceLoadMode,
		Set<LinkNodeType> allowedSourceTypes,
		Set<LinkNodeType> allowedTargetTypes,
		Map<LinkNodeType, Set<Long>> mergedPresetSources,
		Map<LinkNodeType, Set<Long>> mergedPresetTargets
	) {
		return new RedstoneLinkCrossChunkConfig(
			40,
			true,
			true,
			false,
			RedstoneLinkConfig.CrossChunkDirectBatchingMode.ALL_DIRECT,
			0,
			false,
			200,
			false,
			false,
			200,
			false,
			true,
			true,
			200,
			100_000,
			500,
			forceLoadEnabled,
			forceLoadMode,
			80,
			8,
			2,
			128,
			true,
			2,
			true,
			RedstoneLinkConfig.CrossChunkNotifyMode.SIMPLE,
			false,
			25,
			false,
			allowedSourceTypes,
			allowedTargetTypes,
			Map.of(),
			mergedPresetSources,
			mergedPresetTargets,
			new RedstoneLinkCrossChunkRetryConfig(200, 1000, 2000, 99, 1, 499, 5, 999, 20, 100)
		);
	}
}
