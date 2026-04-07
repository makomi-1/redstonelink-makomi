package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkSavedData 的性能边界回归（慢测）。
 */
@Tag("slow")
class LinkSavedDataPerformanceSlowTest {
	private static final int CORE_COUNT = 1000;
	private static final int TRIGGER_SOURCE_COUNT = 2000;
	private static final int LINKS_PER_TRIGGER_SOURCE = 3;
	private static final long DEFAULT_SAVE_LIMIT_MS = 50L;
	private static final long DEFAULT_LOAD_LIMIT_MS = 40L;
	private static final long DEFAULT_QUERY_LIMIT_MS = 5L;

	/**
	 * 千级节点/链接的保存、加载与查询耗时应可接受。
	 */
	@Test
	void saveLoadAndQueryShouldStayWithinBaseline() {
		long saveLimitMs = readLimit("redstonelink.test.slow.saveMs", DEFAULT_SAVE_LIMIT_MS);
		long loadLimitMs = readLimit("redstonelink.test.slow.loadMs", DEFAULT_LOAD_LIMIT_MS);
		long queryLimitMs = readLimit("redstonelink.test.slow.queryMs", DEFAULT_QUERY_LIMIT_MS);

		LinkSavedData data = new LinkSavedData();
		List<Long> coreSerials = new ArrayList<>(CORE_COUNT);
		List<Long> triggerSourceSerials = new ArrayList<>(TRIGGER_SOURCE_COUNT);

		for (int i = 0; i < CORE_COUNT; i++) {
			long coreSerial = data.allocateSerial(LinkNodeType.CORE);
			coreSerials.add(coreSerial);
			data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(0, 64, i), LinkNodeType.CORE);
		}

		for (int i = 0; i < TRIGGER_SOURCE_COUNT; i++) {
			long triggerSourceSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
			triggerSourceSerials.add(triggerSourceSerial);
			data.registerNode(triggerSourceSerial, Level.OVERWORLD, new BlockPos(1, 64, i), LinkNodeType.TRIGGER_SOURCE);
			for (int j = 0; j < LINKS_PER_TRIGGER_SOURCE; j++) {
				long coreSerial = coreSerials.get((i + j) % coreSerials.size());
				data.toggleTriggerSourceCoreLink(triggerSourceSerial, coreSerial);
			}
		}

		long saveStart = System.nanoTime();
		CompoundTag saved = data.save(new CompoundTag(), null);
		long saveCostMs = toMillis(saveStart);

		long loadStart = System.nanoTime();
		LinkSavedData restored = invokeLoad(saved);
		long loadCostMs = toMillis(loadStart);

		long queryStart = System.nanoTime();
		int totalLinks = 0;
		for (long triggerSourceSerial : triggerSourceSerials) {
			totalLinks += restored.getLinkedCoresByTriggerSource(triggerSourceSerial).size();
		}
		long queryCostMs = toMillis(queryStart);

		System.out.println("[perf] save=" + saveCostMs + "ms, load=" + loadCostMs + "ms, query=" + queryCostMs + "ms");

		assertTrue(totalLinks > 0);
		assertTrue(saveCostMs <= saveLimitMs, buildLimitMessage("save", saveCostMs, saveLimitMs));
		assertTrue(loadCostMs <= loadLimitMs, buildLimitMessage("load", loadCostMs, loadLimitMs));
		assertTrue(queryCostMs <= queryLimitMs, buildLimitMessage("query", queryCostMs, queryLimitMs));
	}

	private static long toMillis(long startNs) {
		return (System.nanoTime() - startNs) / 1_000_000L;
	}

	private static long readLimit(String key, long fallback) {
		String raw = System.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static String buildLimitMessage(String label, long costMs, long limitMs) {
		return label + " cost " + costMs + "ms exceeds limit " + limitMs + "ms";
	}

	/**
	 * 通过反射调用私有 load 方法，避免直接暴露内部接口。
	 */
	private static LinkSavedData invokeLoad(CompoundTag tag) {
		try {
			var loadMethod = LinkSavedData.class.getDeclaredMethod("load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
			loadMethod.setAccessible(true);
			return (LinkSavedData) loadMethod.invoke(null, tag, null);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to invoke LinkSavedData.load by reflection", ex);
		}
	}
}
