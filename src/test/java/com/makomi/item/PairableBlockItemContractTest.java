package com.makomi.item;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import net.minecraft.world.entity.item.ItemEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PairableBlockItem 销毁打标回归契约测试。
 */
@Tag("stable-core")
class PairableBlockItemContractTest {

	/**
	 * 应声明 onDestroyed(ItemEntity) 覆写入口，防止回归删除。
	 */
	@Test
	void shouldDeclareOnDestroyedOverride() throws Exception {
		Method method = PairableBlockItem.class.getDeclaredMethod("onDestroyed", ItemEntity.class);
		assertNotNull(method);
		assertTrue(
			method.getDeclaringClass().equals(PairableBlockItem.class),
			"onDestroyed 应由 PairableBlockItem 自身声明，避免回退到基类空实现"
		);
	}
}
