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
	 * 附着块通知链路应覆盖顶面与非顶面，避免出现“隔着方块不生效”。
	 */
	@Test
	void dustCoreNotifyAttachedNeighborShouldNotEarlyReturnForTopFace() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern earlyReturnPattern = Pattern.compile(
			"notifyAttachedNeighbor\\s*\\([^)]*\\)\\s*\\{\\s*if\\s*\\(!isNonTopAttached\\(state\\)\\)\\s*\\{\\s*return;",
			Pattern.MULTILINE | Pattern.DOTALL
		);
		assertFalse(earlyReturnPattern.matcher(source).find());
	}

	/**
	 * 方块实体不应再执行“中心+六方向”邻居通知，避免与方块侧重复扇出。
	 */
	@Test
	void dustCoreBlockEntityShouldNotBroadcastNeighborsManually() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkRedstoneDustCoreBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		Pattern manualFanoutPattern = Pattern.compile(
			"onActiveChanged\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*updateNeighborsAt\\(worldPosition",
			Pattern.MULTILINE
		);
		assertFalse(manualFanoutPattern.matcher(source).find());
	}

	/**
	 * 附着块通知应避免对六方向再扇出，降低重复通知量。
	 */
	@Test
	void dustCoreNotifyAttachedNeighborShouldOnlyNotifyAttachedPos() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/LinkRedstoneDustCoreBlock.java"),
			StandardCharsets.UTF_8
		);
		Pattern neighborFanoutPattern = Pattern.compile(
			"notifyAttachedNeighbor\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*attachedPos\\.relative\\(direction\\)",
			Pattern.MULTILINE
		);
		assertFalse(neighborFanoutPattern.matcher(source).find());
	}
}
