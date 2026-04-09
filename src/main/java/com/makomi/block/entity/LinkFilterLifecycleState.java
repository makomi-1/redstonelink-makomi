package com.makomi.block.entity;

/**
 * 过滤器的放置态生命周期状态。
 * <p>
 * 该状态机只区分两类上下文脱附：
 * </p>
 * <ul>
 * <li>普通区块/上下文脱附：例如区块卸载，不应删除已放置过滤器真值；</li>
 * <li>真实物理移除：例如方块被破坏或替换，应删除过滤器真值。</li>
 * </ul>
 */
final class LinkFilterLifecycleState {
	private boolean physicalRemovalInProgress;
	private boolean contextAttached;

	/**
	 * 记录当前过滤器已重新附着到区块上下文。
	 */
	void onContextAttached() {
		contextAttached = true;
		physicalRemovalInProgress = false;
	}

	/**
	 * 标记当前过滤器正进入真实物理移除路径。
	 */
	void onPhysicalRemovalStarted() {
		physicalRemovalInProgress = true;
	}

	/**
	 * 处理一次上下文脱附，并回答是否应删除已放置过滤器真值。
	 *
	 * @return true 表示本次脱附对应真实物理移除，应删除持久化过滤器
	 */
	boolean onContextDetachedShouldRemovePersistedFilter() {
		boolean shouldRemove = contextAttached && physicalRemovalInProgress;
		contextAttached = false;
		physicalRemovalInProgress = false;
		return shouldRemove;
	}

	boolean physicalRemovalInProgress() {
		return physicalRemovalInProgress;
	}

	boolean contextAttached() {
		return contextAttached;
	}
}
