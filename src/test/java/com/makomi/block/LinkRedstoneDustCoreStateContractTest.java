package com.makomi.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
