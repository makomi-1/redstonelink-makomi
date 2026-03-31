package com.makomi.block.entity;

/**
 * 可配对节点的瞬时生命周期状态机。
 * <p>
 * 该状态机只负责回答三个问题：
 * </p>
 * <ul>
 * <li>当前方块实体是否已附着到区块上下文；</li>
 * <li>当前附着是否仍有待发布 attach；</li>
 * <li>当前 `setRemoved()` 是否应被视为“普通上下文脱附”，还是“物理移除后的后继脱附”。</li>
 * </ul>
 * <p>
 * 这样可以把“真实移除”和“区块/上下文脱附”拆开，也能避免在 serial 尚未就绪时过早发布 attach。
 * </p>
 */
final class PairableNodeLifecycleState {
	private boolean physicalRemovalInProgress;
	private boolean contextAttached;
	private boolean attachPending;
	private boolean attachPublished;

	/**
	 * 记录“当前方块实体已附着到区块/世界上下文”。
	 * <p>
	 * 每次重新附着都重置物理移除标记，并要求重新走一次 attach 发布判定。
	 * </p>
	 */
	void onContextAttached() {
		physicalRemovalInProgress = false;
		contextAttached = true;
		attachPending = true;
	}

	/**
	 * 标记当前实例正进入真实物理移除路径。
	 * <p>
	 * 该标记应在 block 的 `onRemove(...)` 阶段尽早设置，
	 * 用于让后续 `setRemoved()` 不再被误判为普通上下文脱附。
	 * </p>
	 */
	void onPhysicalRemovalStarted() {
		physicalRemovalInProgress = true;
	}

	/**
	 * 当节点身份（serial）在已附着实例上发生变化时，要求重新发布 attach。
	 * <p>
	 * 该路径主要用于“同一方块实体切换到新 serial”的场景，避免沿用旧节点的发布状态。
	 * </p>
	 */
	void onNodeIdentityChangedWhileAttached() {
		if (!contextAttached) {
			return;
		}
		attachPending = true;
		attachPublished = false;
	}

	/**
	 * 当 attach 发布条件满足时，登记本轮附着已完成发布。
	 *
	 * @param publishReady 外部判定的发布条件，例如服务端上下文与有效 serial 是否就绪
	 * @return true 表示本次应真正发布 attach；false 表示仍需等待或已发布过
	 */
	boolean tryMarkAttachPublished(boolean publishReady) {
		if (!publishReady || !contextAttached || !attachPending || attachPublished || physicalRemovalInProgress) {
			return false;
		}
		attachPending = false;
		attachPublished = true;
		return true;
	}

	/**
	 * 处理一次上下文脱附。
	 *
	 * @param publishReady 外部判定的发布条件
	 * @return true 表示本次 `setRemoved()` 应作为普通 context-detach 发布；false 表示不应发布
	 */
	boolean onContextDetached(boolean publishReady) {
		boolean shouldPublishDetach = publishReady && contextAttached && attachPublished && !physicalRemovalInProgress;
		contextAttached = false;
		attachPending = false;
		attachPublished = false;
		physicalRemovalInProgress = false;
		return shouldPublishDetach;
	}

	boolean physicalRemovalInProgress() {
		return physicalRemovalInProgress;
	}

	boolean contextAttached() {
		return contextAttached;
	}

	boolean attachPending() {
		return attachPending;
	}

	boolean attachPublished() {
		return attachPublished;
	}
}
