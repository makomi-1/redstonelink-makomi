package com.makomi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.HideCoreBlock;
import com.makomi.block.HideSyncTriggerSourceBlock;
import com.makomi.block.LinkCoreBlock;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * `hide` 节点定向输入/输出的集成级回归测试。
 * <p>
 * 这里不走完整游戏实例，而是使用最小 `Level` 测试夹具，
 * 直接验证隐藏节点在运行时对面集配置的采样与发射行为。
 * </p>
 */
@Tag("integration")
class HideNodeDirectionalIntegrationTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * `hide core` 只应在启用输出面返回红石强度，其余方向必须为 0。
	 */
	@Test
	void hideCoreShouldOnlyOutputOnEnabledFace() throws Exception {
		TestHideCoreBlock block = createHideCoreBlock();
		TestLevel level = TestLevel.create();
		BlockPos pos = new BlockPos(4, 80, 4);
		BlockState state = NodeFaceSetBlockStateSupport.withSingleFace(
			block.defaultBlockState().setValue(LinkCoreBlock.ACTIVE, true),
			Direction.EAST
		);
		level.setTestBlockState(pos, state);

		int eastSignal = block.exposeSignal(state, level, pos, Direction.EAST);
		assertTrue(eastSignal > 0, "启用输出面必须向外提供红石信号");
		assertEquals(0, block.exposeSignal(state, level, pos, Direction.WEST));
		assertEquals(0, block.exposeSignal(state, level, pos, Direction.NORTH));
		assertEquals(eastSignal, block.exposeDirectSignal(state, level, pos, Direction.EAST));
		assertEquals(0, block.exposeDirectSignal(state, level, pos, Direction.SOUTH));
	}

	/**
	 * `hide sync triggerSource` 只应采样启用输入面，并在多面启用时取最大值。
	 */
	@Test
	void hideSyncTriggerSourceShouldOnlyReadEnabledInputFaces() throws Exception {
		TestHideSyncTriggerSourceBlock block = createHideSyncTriggerSourceBlock();
		TestLevel level = TestLevel.create();
		BlockPos pos = new BlockPos(12, 70, 12);

		BlockState northOnlyState = NodeFaceSetBlockStateSupport.withSingleFace(block.defaultBlockState(), Direction.NORTH);
		level.setTestBlockState(pos, northOnlyState);
		level.setTestSignal(pos.relative(Direction.NORTH), Direction.NORTH, 7);
		level.setTestSignal(pos.relative(Direction.SOUTH), Direction.SOUTH, 15);

		assertEquals(7, block.exposeResolveInputSignalStrength(level, pos));

		BlockState northSouthState = NodeFaceSetBlockStateSupport.setFaceEnabled(northOnlyState, Direction.SOUTH, true);
		level.setTestBlockState(pos, northSouthState);

		assertEquals(15, block.exposeResolveInputSignalStrength(level, pos));
	}

	/**
	 * 暴露受保护的 `hide core` 信号读取入口，避免测试中引入反射。
	 */
	private static final class TestHideCoreBlock extends HideCoreBlock {
		private TestHideCoreBlock(BlockBehaviour.Properties properties) {
			super(properties);
		}

		/**
		 * 读取普通红石输出，供测试断言使用。
		 */
		private int exposeSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
			return super.getSignal(state, level, pos, direction);
		}

		/**
		 * 读取强充能输出，供测试断言使用。
		 */
		private int exposeDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
			return super.getDirectSignal(state, level, pos, direction);
		}
	}

	/**
	 * 暴露受保护的输入采样入口，便于验证面集裁剪行为。
	 */
	private static final class TestHideSyncTriggerSourceBlock extends HideSyncTriggerSourceBlock {
		private TestHideSyncTriggerSourceBlock(BlockBehaviour.Properties properties) {
			super(properties);
		}

		/**
		 * 调用隐藏同步触发器的输入采样逻辑。
		 */
		private int exposeResolveInputSignalStrength(Level level, BlockPos pos) {
			return super.resolveInputSignalStrength(level, pos);
		}
	}

	/**
	 * 为测试临时创建一个最小 `hide core`，避免依赖真实模组注册表项。
	 */
	private static TestHideCoreBlock createHideCoreBlock() throws Exception {
		try (RegistryWriteWindow ignored = RegistryWriteWindow.open()) {
			return new TestHideCoreBlock(BlockBehaviour.Properties.of().noCollission());
		}
	}

	/**
	 * 为测试临时创建一个最小 `hide sync triggerSource`。
	 */
	private static TestHideSyncTriggerSourceBlock createHideSyncTriggerSourceBlock() throws Exception {
		try (RegistryWriteWindow ignored = RegistryWriteWindow.open()) {
			return new TestHideSyncTriggerSourceBlock(BlockBehaviour.Properties.of().noCollission());
		}
	}

	/**
	 * 仅实现本测试所需读接口的最小 `Level` 替身。
	 * <p>
	 * 通过 `Unsafe.allocateInstance` 绕过父类复杂构造，仅覆盖：
	 * `BlockState` 读取、邻位红石输入查询与块实体查询。
	 * </p>
	 */
	private static final class TestLevel extends Level {
		private Map<BlockPos, BlockState> blockStates;
		private Map<SignalQueryKey, Integer> signals;
		private Map<BlockPos, BlockEntity> blockEntities;

		private TestLevel() {
			super(
				(WritableLevelData) null,
				Level.OVERWORLD,
				RegistryAccess.EMPTY,
				(Holder<DimensionType>) null,
				(Supplier<ProfilerFiller>) () -> null,
				false,
				false,
				0L,
				0
			);
			throw new UnsupportedOperationException("仅供 Unsafe.allocateInstance 使用");
		}

		/**
		 * 构造可读写测试状态的最小世界替身。
		 */
		private static TestLevel create() throws Exception {
			TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
			level.blockStates = new HashMap<>();
			level.signals = new HashMap<>();
			level.blockEntities = new HashMap<>();
			return level;
		}

		/**
		 * 写入测试世界中的方块状态。
		 */
		private void setTestBlockState(BlockPos pos, BlockState state) {
			blockStates.put(pos.immutable(), state);
		}

		/**
		 * 写入邻位定向红石输入结果。
		 */
		private void setTestSignal(BlockPos pos, Direction direction, int strength) {
			signals.put(new SignalQueryKey(pos.immutable(), direction), strength);
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return blockStates.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getSignal(BlockPos pos, Direction direction) {
			return signals.getOrDefault(new SignalQueryKey(pos.immutable(), direction), 0);
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return blockEntities.get(pos);
		}

		@Override
		public void setBlockEntity(BlockEntity blockEntity) {
			blockEntities.put(blockEntity.getBlockPos().immutable(), blockEntity);
		}

		@Override
		public void removeBlockEntity(BlockPos pos) {
			blockEntities.remove(pos);
		}

		@Override
		public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
		}

		@Override
		public LevelTickAccess<Block> getBlockTicks() {
			return null;
		}

		@Override
		public LevelTickAccess<Fluid> getFluidTicks() {
			return null;
		}

		@Override
		public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
			return null;
		}

		@Override
		public MinecraftServer getServer() {
			return null;
		}

		@Override
		public ChunkSource getChunkSource() {
			return null;
		}

		@Override
		public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
		}

		@Override
		public void addParticle(ParticleOptions options, double x, double y, double z, double velocityX, double velocityY, double velocityZ) {
		}

		@Override
		public void levelEvent(Player player, int type, BlockPos pos, int data) {
		}

		@Override
		public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
		}

		@Override
		public void playSeededSound(
			Player player,
			double x,
			double y,
			double z,
			Holder<net.minecraft.sounds.SoundEvent> sound,
			SoundSource source,
			float volume,
			float pitch,
			long seed
		) {
		}

		@Override
		public void playSeededSound(
			Player player,
			Entity entity,
			Holder<net.minecraft.sounds.SoundEvent> sound,
			SoundSource source,
			float volume,
			float pitch,
			long seed
		) {
		}

		@Override
		public String gatherChunkSourceStats() {
			return "test";
		}

		@Override
		public Entity getEntity(int id) {
			return null;
		}

		@Override
		public TickRateManager tickRateManager() {
			return null;
		}

		@Override
		public MapItemSavedData getMapData(MapId mapId) {
			return null;
		}

		@Override
		public void setMapData(MapId mapId, MapItemSavedData mapData) {
		}

		@Override
		public MapId getFreeMapId() {
			return null;
		}

		@Override
		public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
		}

		@Override
		public Scoreboard getScoreboard() {
			return new Scoreboard();
		}

		@Override
		public RecipeManager getRecipeManager() {
			return null;
		}

		@Override
		public PotionBrewing potionBrewing() {
			return null;
		}

		@Override
		protected LevelEntityGetter<Entity> getEntities() {
			return null;
		}

		@Override
		public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
			return this;
		}

		@Override
		public int getHeight(Heightmap.Types heightmapType, int x, int z) {
			return 0;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			return null;
		}

		@Override
		public int getSeaLevel() {
			return 0;
		}

		@Override
		public WorldBorder getWorldBorder() {
			return new WorldBorder();
		}

		@Override
		public LevelData getLevelData() {
			return null;
		}

		@Override
		public GameRules getGameRules() {
			return null;
		}

		@Override
		public BiomeManager getBiomeManager() {
			return null;
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			return true;
		}

		@Override
		public ChunkAccess getChunk(int chunkX, int chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus requiredStatus, boolean nonnull) {
			return null;
		}

		@Override
		public List<? extends Player> players() {
			return List.of();
		}

		@Override
		public FeatureFlagSet enabledFeatures() {
			return null;
		}

		@Override
		public Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
			return null;
		}

		@Override
		public float getShade(Direction direction, boolean shaded) {
			return 1.0F;
		}
	}

	/**
	 * 邻位输入查询键，按“位置 + 被查询方向”稳定索引测试红石值。
	 */
	private record SignalQueryKey(BlockPos pos, Direction direction) {
	}

	/**
	 * 注册表写窗口：仅在测试中短暂恢复 `BLOCK` 注册表的 intrusive holder 创建能力。
	 */
	private static final class RegistryWriteWindow implements AutoCloseable {
		private final RegistryState state;

		private RegistryWriteWindow(RegistryState state) {
			this.state = state;
		}

		private static RegistryWriteWindow open() throws ReflectiveOperationException {
			return new RegistryWriteWindow(RegistryState.open((MappedRegistry<?>) BuiltInRegistries.BLOCK));
		}

		@Override
		public void close() throws ReflectiveOperationException {
			state.close();
		}
	}

	/**
	 * 单个注册表的临时可写快照。
	 */
	private static final class RegistryState implements AutoCloseable {
		private final MappedRegistry<?> registry;
		private final boolean frozen;
		private final Map<?, ?> intrusiveHolders;

		private RegistryState(MappedRegistry<?> registry, boolean frozen, Map<?, ?> intrusiveHolders) {
			this.registry = registry;
			this.frozen = frozen;
			this.intrusiveHolders = intrusiveHolders;
		}

		private static RegistryState open(MappedRegistry<?> registry) throws ReflectiveOperationException {
			boolean previousFrozen = FROZEN_FIELD.getBoolean(registry);
			Map<?, ?> previousIntrusiveHolders = (Map<?, ?>) UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.get(registry);
			FROZEN_FIELD.setBoolean(registry, false);
			UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.set(registry, new IdentityHashMap<>());
			return new RegistryState(registry, previousFrozen, previousIntrusiveHolders);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.set(registry, intrusiveHolders);
			FROZEN_FIELD.setBoolean(registry, frozen);
		}
	}

	/**
	 * 读取 `Unsafe`，复用既有测试样板。
	 */
	private static Unsafe unsafe() throws Exception {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}

	private static final Field FROZEN_FIELD = field("frozen");
	private static final Field UNREGISTERED_INTRUSIVE_HOLDERS_FIELD = field("unregisteredIntrusiveHolders");

	/**
	 * 读取 `MappedRegistry` 私有字段，复用 intrusive holder 测试样板。
	 */
	private static Field field(String name) {
		try {
			Field field = MappedRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}
}
