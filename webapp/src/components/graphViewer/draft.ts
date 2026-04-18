import type {
  GraphDraft,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
  GraphUpdatedNodeState,
  GraphWriteResponse,
  ReplaceTriggerSourceTargetsOperation,
  RenameNodeAliasOperation,
} from '../../graphTypes';
import {
  createGraphNodeKey,
  parseGraphDraft,
  parseGraphWriteResponse,
  serializeGraphDraft,
} from '../../graphTypes';
import type { GraphDraftDiff, GraphEditMode } from './types';

export function buildDraftFileName(graphFileName: string, snapshotId: string): string {
  const normalizedBaseName = graphFileName.trim()
    ? graphFileName.replace(/\.json\.gz$/i, '')
    : snapshotId;
  return `draft-${normalizedBaseName}.json`;
}

export function sameGraphDraft(left: GraphDraft, right: GraphDraft): boolean {
  return serializeGraphDraft(left) === serializeGraphDraft(right);
}

function normalizeAlias(rawAlias: string): string {
  return rawAlias.trim();
}

function normalizeTargetSerials(targetSerials: number[]): number[] {
  return Array.from(
    new Set(
      targetSerials
        .map((serial) => Math.max(0, Math.trunc(serial)))
        .filter((serial) => serial > 0),
    ),
  ).sort((left, right) => left - right);
}

function buildAliasOperationKey(nodeType: GraphNodeTypeToken, serial: number): string {
  return `${nodeType}:${serial}`;
}

export function sameNumberArray(left: number[], right: number[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
}

export function formatEditModeLabel(editMode: GraphEditMode): string {
  switch (editMode) {
    case 'add':
      return '追加';
    case 'remove':
      return '移除';
    case 'replace':
      return '覆盖';
    default:
      return '查看';
  }
}

export function formatEditModeInstruction(editMode: GraphEditMode): string {
  switch (editMode) {
    case 'add':
      return '先点选一个或多个 triggerSource，再点选要追加的 core，应用后会把这些 core 并入每个来源节点的目标集合。';
    case 'remove':
      return '先点选一个或多个 triggerSource，再点选要移除的 core，应用后会从每个来源节点当前目标集合中扣除它们。';
    case 'replace':
      return '先点选一个或多个 triggerSource，再点选新的 core 集合，应用后会整体覆盖这些来源节点的目标集合。';
    default:
      return '查看模式下点击节点只会切换详情，不会修改拓扑草稿。';
  }
}

function resolveBaseTargetSerials(
  graphBundle: GraphSnapshotBundle,
  triggerSourceSerial: number,
): number[] {
  const sourceNodeKey = createGraphNodeKey('triggerSource', triggerSourceSerial);
  return normalizeTargetSerials(
    graphBundle.edges
      .filter((edge) => edge.sourceNodeKey === sourceNodeKey)
      .map((edge) => {
        const matched = edge.targetNodeKey.match(/^core:(\d+)$/);
        return matched == null ? 0 : Number(matched[1]);
      }),
  );
}

export function resolveEffectiveTargetSerials(
  graphBundle: GraphSnapshotBundle,
  graphDraft: GraphDraft,
  triggerSourceSerial: number,
): number[] {
  const replaceOperation = graphDraft.operations.find(
    (operation): operation is ReplaceTriggerSourceTargetsOperation =>
      operation.type === 'ReplaceTriggerSourceTargets' &&
      operation.triggerSourceSerial === triggerSourceSerial,
  );
  return replaceOperation == null
    ? resolveBaseTargetSerials(graphBundle, triggerSourceSerial)
    : normalizeTargetSerials(replaceOperation.targetCoreSerials);
}

export function upsertAliasDraft(
  graphDraft: GraphDraft,
  graphBundle: GraphSnapshotBundle,
  nodeType: GraphNodeTypeToken,
  serial: number,
  alias: string,
): GraphDraft {
  const normalizedAlias = normalizeAlias(alias);
  const baseAlias =
    graphBundle.nodes.find(
      (node) => node.type === nodeType && node.serial === serial,
    )?.alias ?? '';
  const nextOperations = graphDraft.operations.filter(
    (operation) =>
      !(
        operation.type === 'RenameNodeAlias' &&
        operation.nodeType === nodeType &&
        operation.serial === serial
      ),
  );
  if (normalizedAlias !== normalizeAlias(baseAlias)) {
    nextOperations.push({
      type: 'RenameNodeAlias',
      nodeType,
      serial,
      alias: normalizedAlias,
    });
  }
  return {
    ...graphDraft,
    dirty: nextOperations.length > 0,
    operations: nextOperations,
  };
}

function upsertReplaceTargetsDraft(
  graphDraft: GraphDraft,
  graphBundle: GraphSnapshotBundle,
  triggerSourceSerial: number,
  expectedSourceRevision: number,
  targetCoreSerials: number[],
): GraphDraft {
  const normalizedTargets = normalizeTargetSerials(targetCoreSerials);
  const baseTargets = resolveBaseTargetSerials(graphBundle, triggerSourceSerial);
  const nextOperations = graphDraft.operations.filter(
    (operation) =>
      !(
        operation.type === 'ReplaceTriggerSourceTargets' &&
        operation.triggerSourceSerial === triggerSourceSerial
      ),
  );
  if (!sameNumberArray(normalizedTargets, baseTargets)) {
    nextOperations.push({
      type: 'ReplaceTriggerSourceTargets',
      triggerSourceSerial,
      expectedSourceRevision,
      targetCoreSerials: normalizedTargets,
    });
  }
  return {
    ...graphDraft,
    dirty: nextOperations.length > 0,
    operations: nextOperations,
  };
}

/**
 * 将批量编辑模式折算为现有 replace 草稿操作。
 * <p>
 * 服务端仍只接收 `triggerSource -> core` 覆盖语义，因此 add/remove/replace 都在前端先计算出最终目标集合。
 * </p>
 */
export function applyBatchEditToDraft(
  graphDraft: GraphDraft,
  graphBundle: GraphSnapshotBundle,
  editMode: GraphEditMode,
  triggerSourceSerials: number[],
  targetCoreSerials: number[],
): GraphDraft {
  let nextDraft = graphDraft;
  const normalizedSourceSerials = normalizeTargetSerials(triggerSourceSerials);
  const normalizedTargetSerials = normalizeTargetSerials(targetCoreSerials);
  const removedTargetSerialSet = new Set(normalizedTargetSerials);
  normalizedSourceSerials.forEach((triggerSourceSerial) => {
    const sourceNode = graphBundle.nodes.find(
      (node) => node.type === 'triggerSource' && node.serial === triggerSourceSerial,
    );
    if (!sourceNode) {
      return;
    }
    const currentTargets = resolveEffectiveTargetSerials(graphBundle, nextDraft, triggerSourceSerial);
    const nextTargets =
      editMode === 'add'
        ? normalizeTargetSerials([...currentTargets, ...normalizedTargetSerials])
        : editMode === 'remove'
          ? currentTargets.filter((serial) => !removedTargetSerialSet.has(serial))
          : normalizedTargetSerials;
    nextDraft = upsertReplaceTargetsDraft(
      nextDraft,
      graphBundle,
      triggerSourceSerial,
      sourceNode.sourceRevision,
      nextTargets,
    );
  });
  return nextDraft;
}

export function applyDraftToGraph(
  graphBundle: GraphSnapshotBundle,
  graphDraft: GraphDraft,
): GraphSnapshotBundle {
  const aliasOverrides = new Map<string, RenameNodeAliasOperation>();
  const replaceOverrides = new Map<number, ReplaceTriggerSourceTargetsOperation>();
  graphDraft.operations.forEach((operation) => {
    if (operation.type === 'RenameNodeAlias') {
      aliasOverrides.set(buildAliasOperationKey(operation.nodeType, operation.serial), operation);
      return;
    }
    replaceOverrides.set(operation.triggerSourceSerial, operation);
  });

  const nodes = graphBundle.nodes.map((node) => {
    const aliasOverride = aliasOverrides.get(buildAliasOperationKey(node.type, node.serial));
    if (aliasOverride == null) {
      return node;
    }
    const nextAlias = normalizeAlias(aliasOverride.alias);
    return {
      ...node,
      alias: nextAlias,
      displayText: nextAlias ? `${nextAlias}(#${node.serial})` : `${node.type}(#${node.serial})`,
    };
  });

  const edges: GraphSnapshotBundle['edges'] = [];
  const replacedSources = new Set<number>(Array.from(replaceOverrides.keys()));
  graphBundle.edges.forEach((edge) => {
    const matched = edge.sourceNodeKey.match(/^triggerSource:(\d+)$/);
    const sourceSerial = matched == null ? 0 : Number(matched[1]);
    if (replacedSources.has(sourceSerial)) {
      return;
    }
    edges.push(edge);
  });
  replaceOverrides.forEach((operation, triggerSourceSerial) => {
    operation.targetCoreSerials.forEach((targetCoreSerial) => {
      const sourceNodeKey = createGraphNodeKey('triggerSource', triggerSourceSerial);
      const targetNodeKey = createGraphNodeKey('core', targetCoreSerial);
      edges.push({
        edgeKey: `${sourceNodeKey}->${targetNodeKey}`,
        sourceNodeKey,
        targetNodeKey,
        kind: 'serial',
        readable: true,
        editable: true,
      });
    });
  });
  edges.sort((left, right) =>
    `${left.sourceNodeKey}:${left.targetNodeKey}`.localeCompare(
      `${right.sourceNodeKey}:${right.targetNodeKey}`,
    ),
  );

  return {
    ...graphBundle,
    nodes,
    edges,
    stats: {
      ...graphBundle.stats,
      edgeCount: edges.length,
    },
  };
}

export function buildDraftDiff(
  baseGraphBundle: GraphSnapshotBundle,
  effectiveGraphBundle: GraphSnapshotBundle,
  graphDraft: GraphDraft,
): GraphDraftDiff {
  const changedNodeKeys = new Set<string>();
  graphDraft.operations.forEach((operation) => {
    if (operation.type === 'RenameNodeAlias') {
      changedNodeKeys.add(createGraphNodeKey(operation.nodeType, operation.serial));
      return;
    }
    const sourceNodeKey = createGraphNodeKey('triggerSource', operation.triggerSourceSerial);
    changedNodeKeys.add(sourceNodeKey);
    const baseTargets = resolveBaseTargetSerials(baseGraphBundle, operation.triggerSourceSerial);
    const nextTargets = resolveEffectiveTargetSerials(
      baseGraphBundle,
      graphDraft,
      operation.triggerSourceSerial,
    );
    normalizeTargetSerials([...baseTargets, ...nextTargets]).forEach((targetSerial) => {
      changedNodeKeys.add(createGraphNodeKey('core', targetSerial));
    });
  });

  const baseEdgeMap = new Map(baseGraphBundle.edges.map((edge) => [edge.edgeKey, edge] as const));
  const effectiveEdgeMap = new Map(
    effectiveGraphBundle.edges.map((edge) => [edge.edgeKey, edge] as const),
  );
  const addedEdgeKeys = new Set<string>();
  effectiveEdgeMap.forEach((_, edgeKey) => {
    if (!baseEdgeMap.has(edgeKey)) {
      addedEdgeKeys.add(edgeKey);
    }
  });
  const removedEdges = baseGraphBundle.edges.filter((edge) => !effectiveEdgeMap.has(edge.edgeKey));
  removedEdges.forEach((edge) => {
    changedNodeKeys.add(edge.sourceNodeKey);
    changedNodeKeys.add(edge.targetNodeKey);
  });
  addedEdgeKeys.forEach((edgeKey) => {
    const addedEdge = effectiveEdgeMap.get(edgeKey);
    if (!addedEdge) {
      return;
    }
    changedNodeKeys.add(addedEdge.sourceNodeKey);
    changedNodeKeys.add(addedEdge.targetNodeKey);
  });

  return {
    changedNodeKeys,
    addedEdgeKeys,
    removedEdges,
  };
}

export function applyUpdatedNodeStates(
  graphBundle: GraphSnapshotBundle,
  graphWriteResponse: GraphWriteResponse,
): GraphSnapshotBundle {
  const updatedNodeStateByNodeKey = new Map<string, GraphUpdatedNodeState>(
    graphWriteResponse.updatedNodes.map((nodeState) => [nodeState.nodeKey, nodeState]),
  );
  return {
    ...graphBundle,
    graphRevision:
      graphWriteResponse.graphRevision > 0
        ? graphWriteResponse.graphRevision
        : graphBundle.graphRevision,
    nodes: graphBundle.nodes.map((node) => {
      const updatedNodeState = updatedNodeStateByNodeKey.get(node.nodeKey);
      if (updatedNodeState == null) {
        return node;
      }
      return {
        ...node,
        alias: updatedNodeState.alias,
        displayText: updatedNodeState.displayText,
        sourceRevision: updatedNodeState.sourceRevision,
        coreRevision: updatedNodeState.coreRevision,
      };
    }),
  };
}

export async function loadDraft(draftFileName: string): Promise<GraphDraft | null> {
  const response = await fetch(
    `./api/storage/entry?kind=draft&name=${encodeURIComponent(draftFileName)}`,
    {
      cache: 'no-store',
    },
  );
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  const payload = (await response.json()) as {
    kind: string;
    textContent: string;
  };
  return parseGraphDraft(payload.textContent, payload.kind);
}

export async function persistDraft(draftFileName: string, graphDraft: GraphDraft): Promise<void> {
  if (graphDraft.operations.length === 0) {
    await fetch(`./api/graph/draft?name=${encodeURIComponent(draftFileName)}`, {
      method: 'DELETE',
    });
    return;
  }
  const response = await fetch(`./api/graph/draft?name=${encodeURIComponent(draftFileName)}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: serializeGraphDraft(graphDraft),
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
}

export async function submitGraphSave(graphDraft: GraphDraft): Promise<GraphWriteResponse> {
  const response = await fetch('./api/graph/save', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      draftId: graphDraft.draftId,
      baseSnapshotId: graphDraft.baseSnapshotId,
      mode: graphDraft.mode,
      baseGraphRevision: graphDraft.baseGraphRevision,
      operations: graphDraft.operations,
    }),
  });
  const payload = await response.json();
  return parseGraphWriteResponse(payload);
}
