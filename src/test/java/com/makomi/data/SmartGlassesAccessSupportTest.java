package com.makomi.data;

import com.makomi.registry.ModItems;
import com.makomi.testsupport.TestMinecraftSupport;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SmartGlassesAccessSupport} 的交互门槛回归测试。
 */
@Tag("stable-core")
class SmartGlassesAccessSupportTest {
	@BeforeAll
	static void bootstrapRegistries() {
		TestMinecraftSupport.bootstrapMinecraft();
		TestMinecraftSupport.withWritableBlockRegistries(() -> {
			ModItems.register();
			return null;
		});
	}

	/**
	 * 站立、佩戴智能眼镜且主手为空时，允许添加或移除显示对象。
	 */
	@Test
	void canModifyQuickLinkVisualizationObjectsShouldRequireStanding() throws Exception {
		TestPlayer player = createPlayer(createSmartGlassesStack(), ItemStack.EMPTY, false);

		assertTrue(SmartGlassesAccessSupport.canModifyQuickLinkVisualizationObjects(player));
	}

	/**
	 * 潜行时仍允许保留 visualize 模式与清空入口，但不能继续添加或移除显示对象。
	 */
	@Test
	void sneakShouldDisableObjectMutationButKeepBroadOperatePermission() throws Exception {
		TestPlayer player = createPlayer(createSmartGlassesStack(), ItemStack.EMPTY, true);

		assertFalse(SmartGlassesAccessSupport.canModifyQuickLinkVisualizationObjects(player));
		assertTrue(SmartGlassesAccessSupport.canOperateQuickLinkVisualization(player));
	}

	/**
	 * 仅显示连线时不受主手占用与潜行姿态影响，只要求佩戴智能眼镜。
	 */
	@Test
	void renderPermissionShouldOnlyRequireWearingSmartGlasses() throws Exception {
		TestPlayer player = createPlayer(createSmartGlassesStack(), TestMinecraftSupport.createVanillaStack(Items.STONE), true);

		assertTrue(SmartGlassesAccessSupport.canRenderQuickLinkVisualization(player));
		assertFalse(SmartGlassesAccessSupport.canOperateQuickLinkVisualization(player));
	}

	/**
	 * 未佩戴智能眼镜时，不应获得任何 visualize 相关交互权限。
	 */
	@Test
	void missingSmartGlassesShouldBlockVisualizationInteraction() throws Exception {
		TestPlayer player = createPlayer(ItemStack.EMPTY, ItemStack.EMPTY, false);

		assertFalse(SmartGlassesAccessSupport.canRenderQuickLinkVisualization(player));
		assertFalse(SmartGlassesAccessSupport.canOperateQuickLinkVisualization(player));
		assertFalse(SmartGlassesAccessSupport.canModifyQuickLinkVisualizationObjects(player));
	}

	/**
	 * 构造仅暴露测试所需行为的最小玩家替身。
	 */
	private static TestPlayer createPlayer(ItemStack headStack, ItemStack mainHandStack, boolean shiftKeyDown) throws Exception {
		TestPlayer player = (TestPlayer) unsafe().allocateInstance(TestPlayer.class);
		player.headStack = headStack == null ? ItemStack.EMPTY : headStack;
		player.mainHandStack = mainHandStack == null ? ItemStack.EMPTY : mainHandStack;
		player.shiftKeyDown = shiftKeyDown;
		return player;
	}

	/**
	 * 构造一份能命中 `SmartGlassesItem` 类型判定的最小物品栈。
	 */
	private static ItemStack createSmartGlassesStack() {
		return new ItemStack(ModItems.SMART_GLASSES);
	}

	/**
	 * 读取 {@link Unsafe}，避免重复反射样板。
	 */
	private static Unsafe unsafe() throws Exception {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}

	/**
	 * 只覆盖本测试需要访问的玩家行为。
	 */
	private static final class TestPlayer extends Player {
		private static final GameProfile TEST_PROFILE = new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000123"), "smart-glasses-test");

		private ItemStack headStack;
		private ItemStack mainHandStack;
		private boolean shiftKeyDown;

		private TestPlayer() {
			super(null, TEST_PROFILE);
			throw new UnsupportedOperationException("仅供 Unsafe.allocateInstance 使用");
		}

		@Override
		public ItemStack getItemBySlot(EquipmentSlot slot) {
			return slot == EquipmentSlot.HEAD ? normalize(headStack) : ItemStack.EMPTY;
		}

		@Override
		public ItemStack getMainHandItem() {
			return normalize(mainHandStack);
		}

		@Override
		public boolean isShiftKeyDown() {
			return shiftKeyDown;
		}

		@Override
		public boolean isSpectator() {
			return false;
		}

		@Override
		public boolean isCreative() {
			return false;
		}

		@Override
		public GameType gameMode() {
			return GameType.SURVIVAL;
		}

		/**
		 * 避免 `Unsafe` 绕过构造时留下的 `null` 物品栈影响断言。
		 */
		private static ItemStack normalize(ItemStack stack) {
			return stack == null ? ItemStack.EMPTY : stack;
		}
	}
}
