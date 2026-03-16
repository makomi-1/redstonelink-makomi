package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LinkSavedData 的慢速回归测试。
 */
@Tag("slow")
class LinkSavedDataSlowTest {

	/**
	 * 大规模节点与链接保存/加载后应保持拓扑一致。
	 */
	@Test
	void saveAndLoadShouldPreserveLargeTopology() {
		LinkSavedData data = new LinkSavedData();
		List<Long> coreSerials = new ArrayList<>();
		List<Long> buttonSerials = new ArrayList<>();

		for (int i = 0; i < 50; i++) {
			long coreSerial = data.allocateSerial(LinkNodeType.CORE);
			coreSerials.add(coreSerial);
			data.registerNode(coreSerial, Level.OVERWORLD, new BlockPos(40 + i, 64, 40), LinkNodeType.CORE);
		}

		for (int i = 0; i < 80; i++) {
			long buttonSerial = data.allocateSerial(LinkNodeType.TRIGGER_SOURCE);
			buttonSerials.add(buttonSerial);
			data.registerNode(buttonSerial, Level.OVERWORLD, new BlockPos(60 + i, 64, 60), LinkNodeType.TRIGGER_SOURCE);
			for (int j = 0; j < 3; j++) {
				long coreSerial = coreSerials.get((i + j) % coreSerials.size());
				data.toggleLink(buttonSerial, coreSerial);
			}
		}

		CompoundTag saved = data.save(new CompoundTag(), null);
		LinkSavedData restored = invokeLoad(saved);

		assertEquals(coreSerials.size(), restored.getOnlineSerials(LinkNodeType.CORE).size());
		assertEquals(buttonSerials.size(), restored.getOnlineSerials(LinkNodeType.TRIGGER_SOURCE).size());
		for (long buttonSerial : buttonSerials) {
			assertEquals(3, restored.getLinkedCores(buttonSerial).size());
		}
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
