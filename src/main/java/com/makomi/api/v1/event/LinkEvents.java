package com.makomi.api.v1.event;

import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiDecision;
import com.makomi.api.v1.model.ApiNodeType;
import com.makomi.api.v1.model.LinkMutationResult;
import com.makomi.api.v1.model.NodeRetireResult;
import com.makomi.api.v1.model.TriggerRequest;
import com.makomi.api.v1.model.TriggerResult;
import java.util.Set;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;

/**
 * RedstoneLink 对外事件定义。
 */
public final class LinkEvents {
	/**
	 * 链接变更前置事件。
	 * <p>
	 * 任一监听器返回 deny 即会中断执行。
	 * </p>
	 */
	public static final Event<BeforeSetLinks> BEFORE_SET_LINKS = EventFactory.createArrayBacked(
		BeforeSetLinks.class,
		listeners -> (level, sourceType, sourceSerial, targets, actorContext) -> {
			for (BeforeSetLinks listener : listeners) {
				ApiDecision decision = listener.beforeSetLinks(level, sourceType, sourceSerial, targets, actorContext);
				if (decision != null && !decision.allowed()) {
					return decision;
				}
			}
			return ApiDecision.allow();
		}
	);

	/**
	 * 链接变更后置事件。
	 */
	public static final Event<AfterSetLinks> AFTER_SET_LINKS = EventFactory.createArrayBacked(
		AfterSetLinks.class,
		listeners -> (level, sourceType, sourceSerial, targets, actorContext, result) -> {
			for (AfterSetLinks listener : listeners) {
				listener.afterSetLinks(level, sourceType, sourceSerial, targets, actorContext, result);
			}
		}
	);

	/**
	 * 触发执行前置事件。
	 * <p>
	 * 任一监听器返回 deny 即会中断执行。
	 * </p>
	 */
	public static final Event<BeforeTrigger> BEFORE_TRIGGER = EventFactory.createArrayBacked(
		BeforeTrigger.class,
		listeners -> (request, targets) -> {
			for (BeforeTrigger listener : listeners) {
				ApiDecision decision = listener.beforeTrigger(request, targets);
				if (decision != null && !decision.allowed()) {
					return decision;
				}
			}
			return ApiDecision.allow();
		}
	);

	/**
	 * 触发执行后置事件。
	 */
	public static final Event<AfterTrigger> AFTER_TRIGGER = EventFactory.createArrayBacked(
		AfterTrigger.class,
		listeners -> (request, result) -> {
			for (AfterTrigger listener : listeners) {
				listener.afterTrigger(request, result);
			}
		}
	);

	/**
	 * 节点退役后置事件。
	 */
	public static final Event<NodeRetired> NODE_RETIRED = EventFactory.createArrayBacked(
		NodeRetired.class,
		listeners -> (level, nodeType, serial, actorContext, result) -> {
			for (NodeRetired listener : listeners) {
				listener.onNodeRetired(level, nodeType, serial, actorContext, result);
			}
		}
	);

	private LinkEvents() {
	}

	/**
	 * 链接变更前置回调。
	 */
	@FunctionalInterface
	public interface BeforeSetLinks {
		ApiDecision beforeSetLinks(
			ServerLevel level,
			ApiNodeType sourceType,
			long sourceSerial,
			Set<Long> targets,
			ActorContext actorContext
		);
	}

	/**
	 * 链接变更后置回调。
	 */
	@FunctionalInterface
	public interface AfterSetLinks {
		void afterSetLinks(
			ServerLevel level,
			ApiNodeType sourceType,
			long sourceSerial,
			Set<Long> targets,
			ActorContext actorContext,
			LinkMutationResult result
		);
	}

	/**
	 * 触发前置回调。
	 */
	@FunctionalInterface
	public interface BeforeTrigger {
		ApiDecision beforeTrigger(TriggerRequest request, Set<Long> targets);
	}

	/**
	 * 触发后置回调。
	 */
	@FunctionalInterface
	public interface AfterTrigger {
		void afterTrigger(TriggerRequest request, TriggerResult result);
	}

	/**
	 * 节点退役回调。
	 */
	@FunctionalInterface
	public interface NodeRetired {
		void onNodeRetired(
			ServerLevel level,
			ApiNodeType nodeType,
			long serial,
			ActorContext actorContext,
			NodeRetireResult result
		);
	}
}

