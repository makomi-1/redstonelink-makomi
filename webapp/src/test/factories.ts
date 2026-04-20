import type {
  GraphDraft,
  GraphEdgeInfo,
  GraphNodeInfo,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
} from '../graphTypes';
import { createGraphNodeKey } from '../graphTypes';
import type {
  BridgePingPayload,
  StorageCategorySummary,
  StorageEntryPayload,
  StorageEntrySummary,
  StorageIndexPayload,
  WebPreferencesPayload,
} from '../app/types';

type GraphNodeInput = Partial<GraphNodeInfo> & {
  type: GraphNodeTypeToken;
  serial: number;
};

type GraphEdgeInput = Partial<GraphEdgeInfo> & {
  sourceSerial: number;
  targetSerial: number;
};

/**
 * 统一构造测试节点，减少各测试文件里的样板数据。
 */
export function createTestGraphNode(input: GraphNodeInput): GraphNodeInfo {
  const nodeKey = input.nodeKey ?? createGraphNodeKey(input.type, input.serial);
  const alias = input.alias ?? '';
  return {
    nodeKey,
    type: input.type,
    serial: input.serial,
    alias,
    displayText:
      input.displayText ??
      (alias ? `${alias}(#${input.serial})` : `${input.type}(#${input.serial})`),
    allocated: input.allocated ?? true,
    retired: input.retired ?? false,
    connectionMode: input.connectionMode ?? 'serial',
    channel: input.channel ?? 0,
    sourceRevision: input.sourceRevision ?? input.serial,
    coreRevision: input.coreRevision ?? input.serial,
    capabilityFlags: input.capabilityFlags ?? [],
  };
}

export function createTestGraphEdge(input: GraphEdgeInput): GraphEdgeInfo {
  const sourceNodeKey =
    input.sourceNodeKey ?? createGraphNodeKey('triggerSource', input.sourceSerial);
  const targetNodeKey =
    input.targetNodeKey ?? createGraphNodeKey('core', input.targetSerial);
  return {
    edgeKey: input.edgeKey ?? `${sourceNodeKey}->${targetNodeKey}`,
    sourceNodeKey,
    targetNodeKey,
    kind: input.kind ?? 'serial',
    readable: input.readable ?? true,
    editable: input.editable ?? true,
  };
}

export function createTestGraphBundle(
  overrides: Partial<GraphSnapshotBundle> & {
    nodes?: GraphNodeInfo[];
    edges?: GraphEdgeInfo[];
  } = {},
): GraphSnapshotBundle {
  const nodes = overrides.nodes ?? [];
  const edges = overrides.edges ?? [];
  return {
    kind: 'graphSnapshotBundle',
    snapshotId: overrides.snapshotId ?? 'snapshot-1',
    mode: overrides.mode ?? 'serial',
    graphRevision: overrides.graphRevision ?? 1,
    generatedAtTick: overrides.generatedAtTick ?? 123,
    viewerPlayerId: overrides.viewerPlayerId ?? 'player-1',
    structureChecksum: overrides.structureChecksum ?? 'checksum-1',
    nodes,
    edges,
    stats: overrides.stats ?? {
      nodeCount: nodes.length,
      edgeCount: edges.length,
      triggerSourceCount: nodes.filter((node) => node.type === 'triggerSource').length,
      coreCount: nodes.filter((node) => node.type === 'core').length,
      maskedSourceCount: 0,
    },
  };
}

export function createTestGraphDraft(overrides: Partial<GraphDraft> = {}): GraphDraft {
  return {
    draftId: overrides.draftId ?? 'draft-snapshot-1',
    baseSnapshotId: overrides.baseSnapshotId ?? 'snapshot-1',
    mode: overrides.mode ?? 'serial',
    baseGraphRevision: overrides.baseGraphRevision ?? 1,
    dirty: overrides.dirty ?? false,
    layoutVersion: overrides.layoutVersion ?? 1,
    operations: overrides.operations ?? [],
  };
}

export function createTestBridgePayload(
  overrides: Partial<BridgePingPayload> = {},
): BridgePingPayload {
  return {
    status: overrides.status ?? 'ok',
    modId: overrides.modId ?? 'redstonelink',
    bridgeVersion: overrides.bridgeVersion ?? '1.0.0',
    mode: overrides.mode ?? 'embedded',
    startedAtEpochMillis: overrides.startedAtEpochMillis ?? 1710000000000,
    baseUrl: overrides.baseUrl ?? 'http://127.0.0.1:18080',
  };
}

export function createTestStorageEntrySummary(
  overrides: Partial<StorageEntrySummary> & Pick<StorageEntrySummary, 'kind' | 'fileName'>,
): StorageEntrySummary {
  return {
    kind: overrides.kind,
    fileName: overrides.fileName,
    relativePath: overrides.relativePath ?? `${overrides.kind}/${overrides.fileName}`,
    compressed: overrides.compressed ?? false,
    sizeBytes: overrides.sizeBytes ?? 128,
    lastModifiedEpochMillis: overrides.lastModifiedEpochMillis ?? 1710000000000,
  };
}

export function createTestStorageCategory(
  overrides: Partial<StorageCategorySummary> & {
    kind: string;
    label?: string;
    entries?: StorageEntrySummary[];
  },
): StorageCategorySummary {
  const entries = overrides.entries ?? [];
  return {
    kind: overrides.kind,
    label: overrides.label ?? overrides.kind,
    directoryName: overrides.directoryName ?? overrides.kind,
    fileExtension: overrides.fileExtension ?? '.json',
    compressed: overrides.compressed ?? false,
    entryCount: overrides.entryCount ?? entries.length,
    entries,
  };
}

export function createTestStorageIndex(
  categories: StorageCategorySummary[],
  overrides: Partial<StorageIndexPayload> = {},
): StorageIndexPayload {
  return {
    status: overrides.status ?? 'ok',
    rootPath: overrides.rootPath ?? '/tmp/redstonelink',
    refreshedAtEpochMillis: overrides.refreshedAtEpochMillis ?? 1710000000000,
    categories,
  };
}

export function createTestStorageEntryPayload(
  overrides: Partial<StorageEntryPayload> & {
    kind: string;
    fileName: string;
    textContent: string;
  },
): StorageEntryPayload {
  return {
    status: overrides.status ?? 'ok',
    kind: overrides.kind,
    label: overrides.label ?? overrides.kind,
    fileName: overrides.fileName,
    relativePath: overrides.relativePath ?? `${overrides.kind}/${overrides.fileName}`,
    compressed: overrides.compressed ?? false,
    contentEncoding: overrides.contentEncoding ?? 'identity',
    sizeBytes: overrides.sizeBytes ?? overrides.textContent.length,
    lastModifiedEpochMillis: overrides.lastModifiedEpochMillis ?? 1710000000000,
    textContent: overrides.textContent,
  };
}

export function createTestWebPreferencesPayload(
  overrides: Partial<WebPreferencesPayload> = {},
): WebPreferencesPayload {
  return {
    status: overrides.status ?? 'ok',
    language: overrides.language ?? 'zh-CN',
    themeId: overrides.themeId ?? 'future-command',
  };
}
