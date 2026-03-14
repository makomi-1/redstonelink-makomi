package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CrossChunkWhitelistSavedData 稳定契约测试。
 */
@Tag("stable-core")
class CrossChunkWhitelistSavedDataTest {
	/**
	 * 增删查列清接口应遵循 role 分桶与参数校验规则。
	 */
	@Test
	void whitelistCrudShouldRespectRoleBucketsAndInputGuards() {
		CrossChunkWhitelistSavedData data = new CrossChunkWhitelistSavedData();

		assertFalse(data.add(null, 1L, LinkNodeSemantics.Role.SOURCE));
		assertFalse(data.add(LinkNodeType.BUTTON, 0L, LinkNodeSemantics.Role.SOURCE));
		assertFalse(data.add(LinkNodeType.BUTTON, 1L, null));
		assertTrue(data.add(LinkNodeType.BUTTON, 11L, LinkNodeSemantics.Role.SOURCE));
		assertFalse(data.add(LinkNodeType.BUTTON, 11L, LinkNodeSemantics.Role.SOURCE));

		assertTrue(data.contains(LinkNodeType.BUTTON, 11L, LinkNodeSemantics.Role.SOURCE));
		assertFalse(data.contains(LinkNodeType.BUTTON, 11L, LinkNodeSemantics.Role.TARGET));
		assertFalse(data.contains(LinkNodeType.BUTTON, -1L, LinkNodeSemantics.Role.SOURCE));

		Set<Long> sourceSerials = data.list(LinkNodeType.BUTTON, LinkNodeSemantics.Role.SOURCE);
		assertEquals(Set.of(11L), sourceSerials);
		assertThrows(UnsupportedOperationException.class, () -> sourceSerials.add(12L));
		assertTrue(data.list(null, LinkNodeSemantics.Role.SOURCE).isEmpty());
		assertTrue(data.list(LinkNodeType.BUTTON, null).isEmpty());

		assertFalse(data.remove(LinkNodeType.BUTTON, 99L, LinkNodeSemantics.Role.SOURCE));
		assertFalse(data.remove(null, 11L, LinkNodeSemantics.Role.SOURCE));
		assertTrue(data.remove(LinkNodeType.BUTTON, 11L, LinkNodeSemantics.Role.SOURCE));
		assertFalse(data.contains(LinkNodeType.BUTTON, 11L, LinkNodeSemantics.Role.SOURCE));

		assertTrue(data.add(LinkNodeType.CORE, 21L, LinkNodeSemantics.Role.TARGET));
		assertTrue(data.add(LinkNodeType.CORE, 22L, LinkNodeSemantics.Role.TARGET));
		assertEquals(2, data.clear(LinkNodeType.CORE, LinkNodeSemantics.Role.TARGET));
		assertEquals(0, data.clear(LinkNodeType.CORE, LinkNodeSemantics.Role.TARGET));
		assertEquals(0, data.clear(null, LinkNodeSemantics.Role.TARGET));
		assertEquals(0, data.clear(LinkNodeType.CORE, null));
	}

	/**
	 * 保存时应输出 canonical type，并过滤空集合与非法序号。
	 */
	@Test
	void saveShouldWriteCanonicalTypeAndFilterInvalidSerials() throws Exception {
		CrossChunkWhitelistSavedData data = new CrossChunkWhitelistSavedData();
		assertTrue(data.add(LinkNodeType.BUTTON, 9L, LinkNodeSemantics.Role.SOURCE));
		assertTrue(data.add(LinkNodeType.CORE, 7L, LinkNodeSemantics.Role.TARGET));

		Map<LinkNodeType, Set<Long>> sourceBucket = readBucketMap(data, "sourceWhitelist");
		sourceBucket.put(LinkNodeType.CORE, new HashSet<>());
		sourceBucket.get(LinkNodeType.BUTTON).add(null);
		sourceBucket.get(LinkNodeType.BUTTON).add(-3L);

		CompoundTag root = data.save(new CompoundTag(), null);
		ListTag sourceList = root.getList("sources", net.minecraft.nbt.Tag.TAG_COMPOUND);
		ListTag targetList = root.getList("targets", net.minecraft.nbt.Tag.TAG_COMPOUND);

		CompoundTag sourceEntry = findTypeEntry(sourceList, "triggerSource");
		assertNotNull(sourceEntry);
		assertEquals(List.of(9L), toLongList(sourceEntry.getList("serials", net.minecraft.nbt.Tag.TAG_LONG)));

		CompoundTag targetEntry = findTypeEntry(targetList, "core");
		assertNotNull(targetEntry);
		assertEquals(List.of(7L), toLongList(targetEntry.getList("serials", net.minecraft.nbt.Tag.TAG_LONG)));
	}

	/**
	 * 反序列化时应忽略非法 type 和非法 serial。
	 */
	@Test
	void loadShouldIgnoreInvalidTypeAndSerialEntries() throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag sources = new ListTag();
		sources.add(entry("triggerSource", 1L, -2L, 1L));
		sources.add(entry("BUTTON", 5L));
		sources.add(entry("core", -9L));
		root.put("sources", sources);

		ListTag targets = new ListTag();
		targets.add(entry("core", 8L, 0L, 8L));
		targets.add(entry("unknown", 3L));
		root.put("targets", targets);

		CrossChunkWhitelistSavedData loaded = invokeLoad(root);
		assertEquals(Set.of(1L), loaded.list(LinkNodeType.BUTTON, LinkNodeSemantics.Role.SOURCE));
		assertEquals(Set.of(8L), loaded.list(LinkNodeType.CORE, LinkNodeSemantics.Role.TARGET));
		assertTrue(loaded.list(LinkNodeType.CORE, LinkNodeSemantics.Role.SOURCE).isEmpty());
		assertTrue(loaded.list(LinkNodeType.BUTTON, LinkNodeSemantics.Role.TARGET).isEmpty());
	}

	private static CompoundTag entry(String type, long... serials) {
		CompoundTag entry = new CompoundTag();
		entry.putString("type", type);
		ListTag serialList = new ListTag();
		for (long serial : serials) {
			serialList.add(LongTag.valueOf(serial));
		}
		entry.put("serials", serialList);
		return entry;
	}

	private static CrossChunkWhitelistSavedData invokeLoad(CompoundTag root) throws Exception {
		Method loadMethod = CrossChunkWhitelistSavedData.class.getDeclaredMethod(
			"load",
			CompoundTag.class,
			HolderLookup.Provider.class
		);
		loadMethod.setAccessible(true);
		return (CrossChunkWhitelistSavedData) loadMethod.invoke(null, root, null);
	}

	@SuppressWarnings("unchecked")
	private static Map<LinkNodeType, Set<Long>> readBucketMap(CrossChunkWhitelistSavedData data, String fieldName) throws Exception {
		Field field = CrossChunkWhitelistSavedData.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return (Map<LinkNodeType, Set<Long>>) field.get(data);
	}

	private static CompoundTag findTypeEntry(ListTag listTag, String expectedType) {
		for (net.minecraft.nbt.Tag element : listTag) {
			CompoundTag entry = (CompoundTag) element;
			if (expectedType.equals(entry.getString("type"))) {
				return entry;
			}
		}
		return null;
	}

	private static List<Long> toLongList(ListTag listTag) {
		return listTag.stream().map(tag -> ((LongTag) tag).getAsLong()).toList();
	}
}
