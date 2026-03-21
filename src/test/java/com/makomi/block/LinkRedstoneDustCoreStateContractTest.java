package com.makomi.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 核心红石粉状态语义契约测试。
 */
@Tag("stable-core")
class LinkRedstoneDustCoreStateContractTest {
	/**
	 * 初始化 Minecraft 注册表，确保红石粉方块类可安全装载。
	 */
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 核心红石粉应对外暴露 active 状态键。
	 */
	@Test
	void dustCoreShouldExposeActivePropertyName() {
		assertEquals("active", LinkRedstoneDustCoreBlock.ACTIVE.getName());
	}

	/**
	 * 核心红石粉不再保留 powered 状态字段，避免语义混淆。
	 */
	@Test
	void dustCoreShouldNotKeepLegacyPoweredField() {
		assertThrows(NoSuchFieldException.class, () -> LinkRedstoneDustCoreBlock.class.getDeclaredField("POWERED"));
	}

	/**
	 * 核心红石粉状态应收敛为 support_face + active，不再暴露旧红石线连接态字段。
	 */
	@Test
	void dustCoreShouldNotKeepLegacyWireStateFields() {
		assertThrows(NoSuchFieldException.class, () -> LinkRedstoneDustCoreBlock.class.getDeclaredField("POWER"));
		assertThrows(NoSuchFieldException.class, () -> LinkRedstoneDustCoreBlock.class.getDeclaredField("NORTH"));
		assertThrows(NoSuchFieldException.class, () -> LinkRedstoneDustCoreBlock.class.getDeclaredField("EAST"));
		assertThrows(NoSuchFieldException.class, () -> LinkRedstoneDustCoreBlock.class.getDeclaredField("SOUTH"));
		assertThrows(NoSuchFieldException.class, () -> LinkRedstoneDustCoreBlock.class.getDeclaredField("WEST"));
	}

	/**
	 * 核心红石块与核心红石粉应保持一致的 active 语义命名。
	 */
	@Test
	void coreBlockAndDustCoreShouldUseSameActiveKeyName() {
		assertEquals(LinkCoreBlock.ACTIVE.getName(), LinkRedstoneDustCoreBlock.ACTIVE.getName());
	}

	/**
	 * 构造器应显式声明 ACTIVE 默认值为 false，避免默认态漂移。
	 */
	@Test
	void dustCoreConstructorShouldSetDefaultActiveToFalse() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern pattern = Pattern.compile(
			"registerDefaultState\\(defaultBlockState\\(\\)\\s*\\.setValue\\(SUPPORT_FACE,\\s*Direction\\.DOWN\\)\\s*\\.setValue\\(ACTIVE,\\s*false\\)\\)",
			Pattern.MULTILINE
		);
		assertTrue(pattern.matcher(source).find());
	}

	/**
	 * 核心粉状态已收敛，不应保留无意义的 supportFace 归一化透传辅助方法。
	 */
	@Test
	void dustCoreShouldNotKeepNormalizeSupportFaceHelper() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern normalizeHelperPattern = Pattern.compile(
			"private\\s+static\\s+BlockState\\s+normalizeForSupportFace\\s*\\(",
			Pattern.MULTILINE
		);
		assertFalse(normalizeHelperPattern.matcher(source).find());
	}

	/**
	 * 核心粉实体应与核心块对齐，统一使用中心+六方向扇出工具。
	 */
	@Test
	void dustCoreBlockEntityShouldUseUnifiedNeighborFanout() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkRedstoneDustCoreBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern unifiedFanoutPattern = Pattern.compile(
			"NeighborFanoutUtil\\.notifyCenterAndSixNeighbors\\(",
			Pattern.MULTILINE
		);
		assertTrue(unifiedFanoutPattern.matcher(source).find());
	}

	/**
	 * L2 收敛：核心块与核心粉都应复用基类时间粒度扇出去重守卫。
	 */
	@Test
	void coreAndDustEntitiesShouldUseSameFanoutDedupGuard() throws Exception {
		String coreSource = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkCoreBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		String dustSource = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkRedstoneDustCoreBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern guardPattern = Pattern.compile(
			"shouldFanoutByResolvedOutput\\(active\\)",
			Pattern.MULTILINE
		);
		assertTrue(guardPattern.matcher(coreSource).find());
		assertTrue(guardPattern.matcher(dustSource).find());
	}

	/**
	 * P1 收敛：核心块与核心粉应把时间粒度键传入扇出工具，避免写死同 tick。
	 */
	@Test
	void coreAndDustEntitiesShouldPassTimeGranularityToFanoutUtil() throws Exception {
		String coreSource = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkCoreBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		String dustSource = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkRedstoneDustCoreBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern tickPattern = Pattern.compile(
			"getFanoutTimeTick\\(\\)",
			Pattern.MULTILINE
		);
		Pattern slotPattern = Pattern.compile(
			"getFanoutTimeSlot\\(\\)",
			Pattern.MULTILINE
		);
		assertTrue(tickPattern.matcher(coreSource).find());
		assertTrue(slotPattern.matcher(coreSource).find());
		assertTrue(tickPattern.matcher(dustSource).find());
		assertTrue(slotPattern.matcher(dustSource).find());
	}

	/**
	 * L2 收敛：扇出去重守卫应复用 TimeKey 时间粒度，不可写死为仅 tick 判定。
	 */
	@Test
	void fanoutDedupGuardShouldReuseTimeKeyGranularity() throws Exception {
		String baseSource = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern methodPattern = Pattern.compile(
			"protected\\s+final\\s+boolean\\s+shouldFanoutByResolvedOutput\\s*\\(boolean\\s+resolvedActive\\)",
			Pattern.MULTILINE
		);
		Pattern timeKeyPattern = Pattern.compile(
			"fanoutResolvedTimeKey\\.equals\\(normalizedTimeKey\\)",
			Pattern.MULTILINE
		);
		assertTrue(methodPattern.matcher(baseSource).find());
		assertTrue(timeKeyPattern.matcher(baseSource).find());
	}

	/**
	 * P3 收敛：目标端时间粒度去重仅对 SYNC 生效，TOGGLE/PULSE 不应被该守卫抑制。
	 */
	@Test
	void fanoutDedupGuardShouldOnlyApplyToSyncMode() throws Exception {
		String baseSource = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern syncOnlyPattern = Pattern.compile(
			"if\\s*\\(getEffectiveMode\\(\\)\\s*!=\\s*EffectiveMode\\.SYNC\\)\\s*\\{\\s*return\\s+true;",
			Pattern.MULTILINE | Pattern.DOTALL
		);
		assertTrue(syncOnlyPattern.matcher(baseSource).find());
	}

	/**
	 * P1 收敛：扇出工具应提供时间粒度去重入口（tick+slot）。
	 */
	@Test
	void neighborFanoutUtilShouldExposeTimeGranularityEntry() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/util/NeighborFanoutUtil.java"),
			StandardCharsets.UTF_8
		);
		Pattern signaturePattern = Pattern.compile(
			"notifyCenterAndSixNeighbors\\s*\\([^)]*long\\s+timeTick[^)]*int\\s+timeSlot[^)]*int\\s+resolvedPower",
			Pattern.MULTILINE | Pattern.DOTALL
		);
		assertTrue(signaturePattern.matcher(source).find());
	}

	/**
	 * P1 收敛：扇出工具应在通知前检查区块已加载，避免无效 getChunk 链路。
	 */
	@Test
	void neighborFanoutUtilShouldGuardByHasChunk() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/util/NeighborFanoutUtil.java"),
			StandardCharsets.UTF_8
		);
		Pattern crossChunkGuardPattern = Pattern.compile(
			"if\\s*\\(crossChunk\\s*&&\\s*!level\\.hasChunk\\(neighborChunkX,\\s*neighborChunkZ\\)\\)\\s*\\{[\\s\\S]*?return;",
			Pattern.MULTILINE | Pattern.DOTALL
		);
		Pattern noBackflowPattern = Pattern.compile(
			"updateNeighborsAtExceptFromFacing\\(neighborPos,\\s*sourceBlock,\\s*direction\\.getOpposite\\(\\)\\)",
			Pattern.MULTILINE
		);
		assertTrue(crossChunkGuardPattern.matcher(source).find());
		assertTrue(noBackflowPattern.matcher(source).find());
	}

	/**
	 * P3-3 收敛：扇出工具应提供结构化诊断计数快照与重置入口。
	 */
	@Test
	void neighborFanoutUtilShouldExposeDiagnosticsCounters() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/util/NeighborFanoutUtil.java"),
			StandardCharsets.UTF_8
		);
		Pattern snapshotRecordPattern = Pattern.compile(
			"public\\s+record\\s+FanoutDiagnosticsSnapshot\\s*\\(",
			Pattern.MULTILINE
		);
		Pattern snapshotMethodPattern = Pattern.compile(
			"public\\s+static\\s+FanoutDiagnosticsSnapshot\\s+diagnosticsSnapshot\\s*\\(",
			Pattern.MULTILINE
		);
		Pattern resetMethodPattern = Pattern.compile(
			"public\\s+static\\s+void\\s+resetDiagnosticsCounters\\s*\\(",
			Pattern.MULTILINE
		);
		Pattern dedupCounterMethodPattern = Pattern.compile(
			"public\\s+static\\s+void\\s+recordFanoutDedupHit\\s*\\(",
			Pattern.MULTILINE
		);
		assertTrue(snapshotRecordPattern.matcher(source).find());
		assertTrue(snapshotMethodPattern.matcher(source).find());
		assertTrue(resetMethodPattern.matcher(source).find());
		assertTrue(dedupCounterMethodPattern.matcher(source).find());
	}

	/**
	 * P3-3 收敛：基类扇出去重命中应写入统一扇出诊断计数。
	 */
	@Test
	void fanoutDedupGuardShouldRecordDiagnosticsHit() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/ActivatableTargetBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern diagnosticsHitPattern = Pattern.compile(
			"NeighborFanoutUtil\\.recordFanoutDedupHit\\(\\);",
			Pattern.MULTILINE
		);
		assertTrue(diagnosticsHitPattern.matcher(source).find());
	}

	/**
	 * 核心粉方块不应保留旧的附着块专用通知入口，避免与实体侧扇出形成双入口。
	 */
	@Test
	void dustCoreBlockShouldNotKeepLegacyAttachedNeighborNotifier() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern legacyNotifierPattern = Pattern.compile(
			"private\\s+static\\s+void\\s+notifyAttachedNeighbor\\s*\\(",
			Pattern.MULTILINE
		);
		assertFalse(legacyNotifierPattern.matcher(source).find());
	}

	/**
	 * L1 收敛：neighborChanged 仅应在支撑侧变化时触发生存判定。
	 */
	@Test
	void dustCoreNeighborChangedShouldGateBySupportPos() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern supportGatePattern = Pattern.compile(
			"if\\s*\\(!fromPos\\.equals\\(supportPos\\)\\)\\s*\\{\\s*return;",
			Pattern.MULTILINE | Pattern.DOTALL
		);
		assertTrue(supportGatePattern.matcher(source).find());
	}

	/**
	 * L1 收敛：updateShape 仅支撑方向变化才执行掉落判定。
	 */
	@Test
	void dustCoreUpdateShapeShouldGateBySupportFace() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern supportDirectionPattern = Pattern.compile(
			"if\\s*\\(direction\\s*!=\\s*supportFace\\)\\s*\\{\\s*return\\s+state;",
			Pattern.MULTILINE | Pattern.DOTALL
		);
		assertTrue(supportDirectionPattern.matcher(source).find());
	}
}
