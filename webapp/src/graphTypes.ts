export type GraphNodeTypeToken = 'triggerSource' | 'core';

export type GraphNodeInfo = {
  nodeKey: string;
  type: GraphNodeTypeToken;
  serial: number;
  alias: string;
  displayText: string;
  allocated: boolean;
  retired: boolean;
  connectionMode: string;
  channel: number;
  sourceRevision: number;
  coreRevision: number;
  capabilityFlags: string[];
};

export type GraphEdgeInfo = {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  kind: string;
  readable: boolean;
  editable: boolean;
};

export type GraphStats = {
  nodeCount: number;
  edgeCount: number;
  triggerSourceCount: number;
  coreCount: number;
  maskedSourceCount: number;
};

export type GraphSnapshotBundle = {
  kind: 'graphSnapshotBundle';
  snapshotId: string;
  mode: string;
  graphRevision: number;
  generatedAtTick: number;
  viewerPlayerId: string;
  structureChecksum: string;
  nodes: GraphNodeInfo[];
  edges: GraphEdgeInfo[];
  stats: GraphStats;
};

export type GraphUpdatedNodeState = {
  nodeKey: string;
  nodeType: GraphNodeTypeToken;
  serial: number;
  alias: string;
  displayText: string;
  sourceRevision: number;
  coreRevision: number;
};

export type RenameNodeAliasOperation = {
  type: 'RenameNodeAlias';
  nodeType: GraphNodeTypeToken;
  serial: number;
  alias: string;
};

export type ReplaceTriggerSourceTargetsOperation = {
  type: 'ReplaceTriggerSourceTargets';
  triggerSourceSerial: number;
  expectedSourceRevision: number;
  targetCoreSerials: number[];
};

export type GraphWriteOperation =
  | RenameNodeAliasOperation
  | ReplaceTriggerSourceTargetsOperation;

export type GraphDraft = {
  draftId: string;
  baseSnapshotId: string;
  mode: string;
  baseGraphRevision: number;
  dirty: boolean;
  layoutVersion: number;
  operations: GraphWriteOperation[];
};

export type GraphWriteResponse = {
  status: 'ok' | 'error';
  result: 'applied' | 'conflict' | 'rejected' | '';
  reason: string;
  message: string;
  graphRevision: number;
  updatedNodes: GraphUpdatedNodeState[];
};

function isGraphNodeTypeToken(value: unknown): value is GraphNodeTypeToken {
  return value === 'triggerSource' || value === 'core';
}

function normalizeBoolean(value: unknown): boolean {
  return value === true;
}

function normalizeNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function normalizeText(value: unknown, fallback = ''): string {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function normalizeStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function createGraphNodeKey(type: GraphNodeTypeToken, serial: number): string {
  return `${type}:${Math.max(0, Math.trunc(serial))}`;
}

export function createInitialGraphDraft(graphBundle: GraphSnapshotBundle): GraphDraft {
  return {
    draftId: `draft-${graphBundle.snapshotId}`,
    baseSnapshotId: graphBundle.snapshotId,
    mode: graphBundle.mode,
    baseGraphRevision: graphBundle.graphRevision,
    dirty: false,
    layoutVersion: 1,
    operations: [],
  };
}

function normalizeGraphWriteOperation(value: unknown): GraphWriteOperation | null {
  if (value == null || typeof value !== 'object') {
    return null;
  }
  const record = value as Partial<GraphWriteOperation> & Record<string, unknown>;
  if (record.type === 'RenameNodeAlias' && isGraphNodeTypeToken(record.nodeType)) {
    return {
      type: 'RenameNodeAlias',
      nodeType: record.nodeType,
      serial: normalizeNumber(record.serial),
      alias: normalizeText(record.alias),
    };
  }
  if (record.type === 'ReplaceTriggerSourceTargets') {
    const targetCoreSerials = Array.isArray(record.targetCoreSerials)
      ? record.targetCoreSerials
          .map((serial) => normalizeNumber(serial))
          .filter((serial) => serial > 0)
      : [];
    return {
      type: 'ReplaceTriggerSourceTargets',
      triggerSourceSerial: normalizeNumber(record.triggerSourceSerial),
      expectedSourceRevision: normalizeNumber(record.expectedSourceRevision),
      targetCoreSerials: Array.from(new Set(targetCoreSerials)),
    };
  }
  return null;
}

export function parseGraphDraft(textContent: string, kind: string): GraphDraft | null {
  if (kind !== 'draft') {
    return null;
  }
  try {
    const parsed = JSON.parse(textContent) as Partial<GraphDraft> & {
      operations?: unknown[];
    };
    if (typeof parsed.baseSnapshotId !== 'string') {
      return null;
    }
    const operations = Array.isArray(parsed.operations)
      ? parsed.operations
          .map((operation) => normalizeGraphWriteOperation(operation))
          .filter((operation): operation is GraphWriteOperation => operation != null)
      : [];
    return {
      draftId: normalizeText(parsed.draftId, `draft-${parsed.baseSnapshotId}`),
      baseSnapshotId: normalizeText(parsed.baseSnapshotId),
      mode: normalizeText(parsed.mode, 'serial'),
      baseGraphRevision: normalizeNumber(parsed.baseGraphRevision),
      dirty: normalizeBoolean(parsed.dirty),
      layoutVersion: normalizeNumber(parsed.layoutVersion),
      operations,
    };
  } catch {
    return null;
  }
}

export function serializeGraphDraft(graphDraft: GraphDraft): string {
  return JSON.stringify(graphDraft);
}

export function parseGraphWriteResponse(payload: unknown): GraphWriteResponse {
  const record = payload == null || typeof payload !== 'object' ? {} : (payload as Record<string, unknown>);
  const updatedNodes = Array.isArray(record.updatedNodes)
    ? record.updatedNodes
        .flatMap((nodeState) => {
          if (nodeState == null || typeof nodeState !== 'object') {
            return [];
          }
          const nodeRecord = nodeState as Partial<GraphUpdatedNodeState>;
          if (!isGraphNodeTypeToken(nodeRecord.nodeType) || typeof nodeRecord.nodeKey !== 'string') {
            return [];
          }
          return [
            {
              nodeKey: normalizeText(nodeRecord.nodeKey),
              nodeType: nodeRecord.nodeType,
              serial: normalizeNumber(nodeRecord.serial),
              alias: normalizeText(nodeRecord.alias),
              displayText: normalizeText(nodeRecord.displayText, normalizeText(nodeRecord.nodeKey)),
              sourceRevision: normalizeNumber(nodeRecord.sourceRevision),
              coreRevision: normalizeNumber(nodeRecord.coreRevision),
            },
          ];
        })
    : [];
  return {
    status: record.status === 'error' ? 'error' : 'ok',
    result:
      record.result === 'applied' || record.result === 'conflict' || record.result === 'rejected'
        ? record.result
        : '',
    reason: normalizeText(record.reason),
    message: normalizeText(record.message),
    graphRevision: normalizeNumber(record.graphRevision),
    updatedNodes,
  };
}

/**
 * 解析 graph snapshot bundle。
 * <p>
 * 当前只接受 `kind=graph` 的本地资产条目。
 * </p>
 */
export function parseGraphSnapshotBundle(
  textContent: string,
  kind: string,
): GraphSnapshotBundle | null {
  if (kind !== 'graph') {
    return null;
  }
  try {
    const parsed = JSON.parse(textContent) as Partial<GraphSnapshotBundle> & {
      nodes?: unknown[];
      edges?: unknown[];
      stats?: Partial<GraphStats>;
    };
    if (
      parsed.kind !== 'graphSnapshotBundle' ||
      typeof parsed.snapshotId !== 'string' ||
      !Array.isArray(parsed.nodes) ||
      !Array.isArray(parsed.edges)
    ) {
      return null;
    }

    const nodes: GraphNodeInfo[] = parsed.nodes.flatMap((node) => {
      if (node == null || typeof node !== 'object') {
        return [];
      }
      const record = node as Partial<GraphNodeInfo>;
      if (!isGraphNodeTypeToken(record.type) || typeof record.nodeKey !== 'string') {
        return [];
      }
      return [
        {
          nodeKey: normalizeText(record.nodeKey),
          type: record.type,
          serial: normalizeNumber(record.serial),
          alias: normalizeText(record.alias),
          displayText: normalizeText(record.displayText, normalizeText(record.nodeKey)),
          allocated: normalizeBoolean(record.allocated),
          retired: normalizeBoolean(record.retired),
          connectionMode: normalizeText(record.connectionMode, 'serial'),
          channel: normalizeNumber(record.channel),
          sourceRevision: normalizeNumber(record.sourceRevision),
          coreRevision: normalizeNumber(record.coreRevision),
          capabilityFlags: normalizeStringArray(record.capabilityFlags),
        },
      ];
    });

    const edges: GraphEdgeInfo[] = parsed.edges.flatMap((edge) => {
      if (edge == null || typeof edge !== 'object') {
        return [];
      }
      const record = edge as Partial<GraphEdgeInfo>;
      if (
        typeof record.edgeKey !== 'string' ||
        typeof record.sourceNodeKey !== 'string' ||
        typeof record.targetNodeKey !== 'string'
      ) {
        return [];
      }
      return [
        {
          edgeKey: normalizeText(record.edgeKey),
          sourceNodeKey: normalizeText(record.sourceNodeKey),
          targetNodeKey: normalizeText(record.targetNodeKey),
          kind: normalizeText(record.kind, 'serial'),
          readable: normalizeBoolean(record.readable),
          editable: normalizeBoolean(record.editable),
        },
      ];
    });

    const statsRecord = (parsed.stats ?? {}) as Partial<GraphStats>;
    return {
      kind: 'graphSnapshotBundle',
      snapshotId: normalizeText(parsed.snapshotId, 'graph'),
      mode: normalizeText(parsed.mode, 'serial'),
      graphRevision: normalizeNumber(parsed.graphRevision),
      generatedAtTick: normalizeNumber(parsed.generatedAtTick),
      viewerPlayerId: normalizeText(parsed.viewerPlayerId, 'unknown'),
      structureChecksum: normalizeText(parsed.structureChecksum, 'graph'),
      nodes,
      edges,
      stats: {
        nodeCount: normalizeNumber(statsRecord.nodeCount ?? nodes.length),
        edgeCount: normalizeNumber(statsRecord.edgeCount ?? edges.length),
        triggerSourceCount: normalizeNumber(
          statsRecord.triggerSourceCount ?? nodes.filter((node) => node.type === 'triggerSource').length,
        ),
        coreCount: normalizeNumber(
          statsRecord.coreCount ?? nodes.filter((node) => node.type === 'core').length,
        ),
        maskedSourceCount: normalizeNumber(statsRecord.maskedSourceCount),
      },
    };
  } catch {
    return null;
  }
}
