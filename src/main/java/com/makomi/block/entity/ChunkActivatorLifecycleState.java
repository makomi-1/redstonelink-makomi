package com.makomi.block.entity;

/**
 * 区块激活器的放置态生命周期状态。
 * <p>
 * 普通区块卸载不删除区块激活器真值；
 * 只有真实物理移除时才删除持久化条目。
 * </p>
 */
final class ChunkActivatorLifecycleState {
	private boolean physicalRemovalInProgress;
	private boolean contextAttached;

	/**
	 * 记录当前区块激活器已重新附着到区块上下文。
	 */
	void onContextAttached() {
		contextAttached = true;
		physicalRemovalInProgress = false;
	}

	/**
	 * 标记当前区块激活器正进入真实物理移除路径。
	 */
	void onPhysicalRemovalStarted() {
		physicalRemovalInProgress = true;
	}

	/**
	 * 处理一次上下文脱附，并回答是否应删除持久化区块激活器真值。
	 */
	boolean onContextDetachedShouldRemovePersistedActivator() {
		boolean shouldRemove = contextAttached && physicalRemovalInProgress;
		contextAttached = false;
		physicalRemovalInProgress = false;
		return shouldRemove;
	}
}
