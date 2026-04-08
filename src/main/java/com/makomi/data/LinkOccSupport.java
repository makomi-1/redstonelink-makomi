package com.makomi.data;

import com.makomi.command.link.LinkSetExecutionService;
import java.util.List;

/**
 * 链接编辑 OCC 基线与冲突判定支撑。
 * <p>
 * 统一复用 `LinkSavedData` 中的 `graphRevision/sourceRevision/coreRevision` 真值，
 * 供 pairing、quick-link 与 bench/internal 提交路径共用。
 * </p>
 */
public final class LinkOccSupport {
	private LinkOccSupport() {
	}

	/**
	 * 读取指定节点当前可见的 OCC 基线。
	 */
	public static RevisionBaseline readBaseline(LinkSavedData savedData, LinkNodeType nodeType, long nodeSerial) {
		if (savedData == null) {
			return new RevisionBaseline(0L, 0L, 0L);
		}
		return new RevisionBaseline(
			savedData.graphRevision(),
			savedData.sourceRevision(nodeType, nodeSerial),
			nodeType == LinkNodeType.CORE ? savedData.coreRevision(nodeSerial) : 0L
		);
	}

	/**
	 * 判断 expected revision 是否与当前真值不一致。
	 */
	public static boolean isRevisionMismatch(long expectedRevision, long currentRevision) {
		return Math.max(0L, expectedRevision) != Math.max(0L, currentRevision);
	}

	/**
	 * 按 `triggerSource` 提交语义解析 revision 冲突。
	 */
	public static OccConflict resolveTriggerSourceConflict(
		LinkSavedData savedData,
		long triggerSourceSerial,
		long expectedSourceRevision
	) {
		RevisionBaseline currentBaseline = readBaseline(savedData, LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);
		return resolveTargetConflictWithCurrentBaseline(
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			0L,
			expectedSourceRevision,
			currentBaseline
		);
	}

	/**
	 * 按 `core` 提交语义解析 revision 冲突。
	 */
	public static OccConflict resolveCoreConflict(LinkSavedData savedData, long coreSerial, long expectedCoreRevision) {
		RevisionBaseline currentBaseline = readBaseline(savedData, LinkNodeType.CORE, coreSerial);
		return resolveTargetConflictWithCurrentBaseline(
			LinkNodeType.CORE,
			coreSerial,
			0L,
			expectedCoreRevision,
			currentBaseline
		);
	}

	/**
	 * 按目标节点语义解析 OCC 冲突；无冲突时返回 `null`。
	 */
	public static OccConflict resolveTargetConflict(
		LinkSavedData savedData,
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		RevisionBaseline currentBaseline = readBaseline(savedData, targetNodeType, targetNodeSerial);
		return resolveTargetConflictWithCurrentBaseline(
			targetNodeType,
			targetNodeSerial,
			expectedCoreRevision,
			expectedSourceRevision,
			currentBaseline
		);
	}

	/**
	 * 基于显式当前 baseline 解析 OCC 冲突；无冲突时返回 `null`。
	 */
	public static OccConflict resolveTargetConflictWithCurrentBaseline(
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		long expectedCoreRevision,
		long expectedSourceRevision,
		RevisionBaseline currentBaseline
	) {
		if (targetNodeType == null || currentBaseline == null) {
			return null;
		}
		if (targetNodeType == LinkNodeType.TRIGGER_SOURCE) {
			if (!isRevisionMismatch(expectedSourceRevision, currentBaseline.sourceRevision())) {
				return null;
			}
			return new OccConflict(
				targetNodeType,
				targetNodeSerial,
				"message.redstonelink.pairing.conflict.source_revision",
				List.of(
					Long.toString(Math.max(0L, targetNodeSerial)),
					Long.toString(Math.max(0L, expectedSourceRevision)),
					Long.toString(Math.max(0L, currentBaseline.sourceRevision()))
				),
				0L,
				Math.max(0L, expectedSourceRevision),
				currentBaseline.graphRevision(),
				currentBaseline.sourceRevision(),
				currentBaseline.coreRevision()
			);
		}
		if (targetNodeType == LinkNodeType.CORE) {
			if (!isRevisionMismatch(expectedCoreRevision, currentBaseline.coreRevision())) {
				return null;
			}
			return new OccConflict(
				targetNodeType,
				targetNodeSerial,
				"message.redstonelink.pairing.conflict.core_revision",
				List.of(
					Long.toString(Math.max(0L, expectedCoreRevision)),
					Long.toString(Math.max(0L, currentBaseline.coreRevision()))
				),
				Math.max(0L, expectedCoreRevision),
				0L,
				currentBaseline.graphRevision(),
				currentBaseline.sourceRevision(),
				currentBaseline.coreRevision()
			);
		}
		return null;
	}

	/**
	 * 将 OCC 冲突映射为命令层反馈。
	 */
	public static LinkSetExecutionService.OperationFeedback toOperationFeedback(OccConflict conflict) {
		if (conflict == null) {
			return LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient");
		}
		return LinkSetExecutionService.OperationFeedback.failure(conflict.messageKey(), conflict.messageArgs());
	}

	/**
	 * 将 OCC 冲突映射为 quick-link 反馈。
	 */
	public static QuickLinkOperationFeedback toQuickLinkFeedback(OccConflict conflict) {
		if (conflict == null) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient");
		}
		return QuickLinkOperationFeedback.failure(conflict.messageKey(), conflict.messageArgs().toArray(String[]::new));
	}

	/**
	 * OCC 基线快照。
	 */
	public record RevisionBaseline(long graphRevision, long sourceRevision, long coreRevision) {
		public RevisionBaseline {
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
		}

		public RevisionBaseline(long graphRevision, long sourceRevision) {
			this(graphRevision, sourceRevision, 0L);
		}
	}

	/**
	 * OCC 冲突的结构化结果。
	 */
	public record OccConflict(
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		String messageKey,
		List<String> messageArgs,
		long expectedCoreRevision,
		long expectedSourceRevision,
		long currentGraphRevision,
		long currentSourceRevision,
		long currentCoreRevision
	) {
		public OccConflict {
			targetNodeSerial = Math.max(0L, targetNodeSerial);
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
			expectedCoreRevision = Math.max(0L, expectedCoreRevision);
			expectedSourceRevision = Math.max(0L, expectedSourceRevision);
			currentGraphRevision = Math.max(0L, currentGraphRevision);
			currentSourceRevision = Math.max(0L, currentSourceRevision);
			currentCoreRevision = Math.max(0L, currentCoreRevision);
		}
	}
}
