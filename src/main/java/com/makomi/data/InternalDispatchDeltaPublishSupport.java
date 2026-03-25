package com.makomi.data;

import java.util.Iterator;

/**
 * InternalDispatchDeltaEvents 投递 helper。
 * <p>
 * 负责监听器注册/注销、同步分发与最近事件去重缓存维护。
 * </p>
 */
final class InternalDispatchDeltaPublishSupport {
	private InternalDispatchDeltaPublishSupport() {
	}

	/**
	 * 注册监听器。
	 */
	static void register(InternalDispatchDeltaEvents.Listener listener) {
		if (listener == null || InternalDispatchDeltaEvents.LISTENERS.contains(listener)) {
			return;
		}
		InternalDispatchDeltaEvents.LISTENERS.add(listener);
	}

	/**
	 * 注销监听器。
	 */
	static void unregister(InternalDispatchDeltaEvents.Listener listener) {
		if (listener == null) {
			return;
		}
		InternalDispatchDeltaEvents.LISTENERS.remove(listener);
	}

	/**
	 * 同步发布事件。
	 */
	static void publish(InternalDispatchDeltaEvents.DispatchDeltaEvent event) {
		if (event == null) {
			return;
		}
		if (!markPublished(InternalDispatchDeltaEvents.DispatchDedupKey.from(event))) {
			return;
		}
		for (InternalDispatchDeltaEvents.Listener listener : InternalDispatchDeltaEvents.LISTENERS) {
			listener.onDispatchDelta(event);
		}
	}

	/**
	 * 将事件标记为“本窗口首发”；重复事件返回 false。
	 */
	static boolean markPublished(InternalDispatchDeltaEvents.DispatchDedupKey key) {
		if (key == null) {
			return false;
		}
		synchronized (InternalDispatchDeltaEvents.RECENT_EVENT_KEYS) {
			if (InternalDispatchDeltaEvents.RECENT_EVENT_KEYS.contains(key)) {
				return false;
			}
			InternalDispatchDeltaEvents.RECENT_EVENT_KEYS.add(key);
			while (InternalDispatchDeltaEvents.RECENT_EVENT_KEYS.size() > InternalDispatchDeltaEvents.RECENT_EVENT_CACHE_LIMIT) {
				Iterator<InternalDispatchDeltaEvents.DispatchDedupKey> iterator =
					InternalDispatchDeltaEvents.RECENT_EVENT_KEYS.iterator();
				if (!iterator.hasNext()) {
					break;
				}
				iterator.next();
				iterator.remove();
			}
			return true;
		}
	}

	/**
	 * 测试专用：重置监听器与防重缓存。
	 */
	static void resetForTesting() {
		InternalDispatchDeltaEvents.LISTENERS.clear();
		synchronized (InternalDispatchDeltaEvents.RECENT_EVENT_KEYS) {
			InternalDispatchDeltaEvents.RECENT_EVENT_KEYS.clear();
		}
	}
}
