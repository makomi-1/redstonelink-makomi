import type {
  GraphDraft,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
  GraphUpdatedNodeState,
  GraphWriteResponse,
  ReplaceTriggerSourceTargetsOperation,
  RenameNodeAliasOperation,
  SetNodeChannelOperation,
} from '../../graphTypes';
import {
  createGraphNodeKey,
  parseGraphDraft,
  parseGraphWriteResponse,
  serializeGraphDraft,
} from '../../graphTypes';
import { pickLocalizedText, type AppLanguage } from '../../app/i18n';
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

function normalizeChannel(channel: number): number {
  return Math.max(0, Math.trunc(channel));
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

export function formatEditModeLabel(
  editMode: GraphEditMode,
  language: AppLanguage,
): string {
  switch (editMode) {
    case 'add':
      return pickLocalizedText(language, '追加', 'Add');
    case 'remove':
      return pickLocalizedText(language, '移除', 'Remove');
    case 'replace':
      return pickLocalizedText(language, '覆盖', 'Replace');
    default:
      return pickLocalizedText(language, '查看', 'View');
  }
}

export function formatEditModeInstruction(
  editMode: GraphEditMode,
  language: AppLanguage,
): string {
  switch (editMode) {
    case 'add':
      return pickLocalizedText(
        language,
        '先点选一个或多个 triggerSource，再点选要追加的 core，应用后会把这些 core 并入每个来源节点的目标集合。',
        'Select one or more triggerSources first, then select the cores to append. After applying, those cores will be merged into each source node\'s target set.',
      );
    case 'remove':
      return pickLocalizedText(
        language,
        '先点选一个或多个 triggerSource，再点选要移除的 core，应用后会从每个来源节点当前目标集合中扣除它们。',
        'Select one or more triggerSources first, then select the cores to remove. After applying, they will be removed from each source node\'s current target set.',
      );
    case 'replace':
      return pickLocalizedText(
        language,
        '先点选一个或多个 triggerSource，再点选新的 core 集合，应用后会整体覆盖这些来源节点的目标集合。',
        'Select one or more triggerSources first, then select the new core set. After applying, the target set of those source nodes will be replaced as a whole.',
      );
    default:
      return pickLocalizedText(
        language,
        '查看模式下点击节点只会切换详情，不会修改拓扑草稿。',
        'In view mode, clicking nodes only switches the details panel and does not modify the topology draft.',
      );
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

/**
 * 判断某个 triggerSource 是否处于“base 为频道模式、草稿里切回 serial”的首次显式编辑阶段。
 * <p>
 * 命中该场景时，序号模式下的 add/remove 应以“空的显式 serial 边”为基线，
 * 而不是继续继承导出图里来自频道态的当前生效目标集合。
 * </p>
 */
function isTriggerSourceReturningToSerial(
  graphBundle: GraphSnapshotBundle,
  graphDraft: GraphDraft,
  triggerSourceSerial: number,
): boolean {
  const baseSourceNode = graphBundle.nodes.find(
    (node) =>
      node.type === 'triggerSource' && node.serial === triggerSourceSerial,
  );
  if (baseSourceNode?.connectionMode !== 'channel') {
    return false;
  }
  return graphDraft.operations.some(
    (operation) =>
      operation.type === 'SetNodeChannel' &&
      operation.nodeType === 'triggerSource' &&
      operation.serial === triggerSourceSerial &&
      normalizeChannel(operation.channel) === 0,
  );
}

/**
 * 解析序号模式显式边编辑应看到的“基线目标集合”。
 * <p>
 * 普通 serial 来源继续读取导出图里的当前显式边；只有“频道迁回 serial”的首次编辑，
 * 才会把基线收敛为空显式边集合。
 * </p>
 */
function resolveExplicitSerialBaseTargetSerials(
  graphBundle: GraphSnapshotBundle,
  graphDraft: GraphDraft,
  triggerSourceSerial: number,
): number[] {
  return isTriggerSourceReturningToSerial(
    graphBundle,
    graphDraft,
    triggerSourceSerial,
  )
    ? []
    : resolveBaseTargetSerials(graphBundle, triggerSourceSerial);
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
    ? resolveExplicitSerialBaseTargetSerials(
        graphBundle,
        graphDraft,
        triggerSourceSerial,
      )
    : normalizeTargetSerials(replaceOperation.targetCoreSerials);
}

function resolveBaseChannel(graphBundle: GraphSnapshotBundle, nodeType: GraphNodeTypeToken, serial: number): number {
  const baseNode = graphBundle.nodes.find(
    (node) => node.type === nodeType && node.serial === serial,
  );
  return baseNode?.connectionMode === 'channel' ? normalizeChannel(baseNode.channel) : 0;
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
  const baseTargets = resolveExplicitSerialBaseTargetSerials(
    graphBundle,
    graphDraft,
    triggerSourceSerial,
  );
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

function upsertSetNodeChannelDraft(
  graphDraft: GraphDraft,
  graphBundle: GraphSnapshotBundle,
  nodeType: GraphNodeTypeToken,
  serial: number,
  expectedSourceRevision: number,
  expectedCoreRevision: number,
  channel: number,
): GraphDraft {
  const normalizedChannel = normalizeChannel(channel);
  const baseChannel = resolveBaseChannel(graphBundle, nodeType, serial);
  const nextOperations = graphDraft.operations.filter(
    (operation) =>
      !(
        operation.type === 'SetNodeChannel' &&
        operation.nodeType === nodeType &&
        operation.serial === serial
      ),
  );
  if (normalizedChannel !== baseChannel) {
    nextOperations.push({
      type: 'SetNodeChannel',
      nodeType,
      serial,
      expectedSourceRevision,
      expectedCoreRevision,
      channel: normalizedChannel,
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

export function applyChannelEditToDraft(
  graphDraft: GraphDraft,
  graphBundle: GraphSnapshotBundle,
  nodeKeys: string[],
  channel: number,
): GraphDraft {
  let nextDraft = graphDraft;
  const normalizedNodeKeys = Array.from(new Set(nodeKeys)).sort((left, right) =>
    left.localeCompare(right),
  );
  normalizedNodeKeys.forEach((nodeKey) => {
    const node = graphBundle.nodes.find((candidate) => candidate.nodeKey === nodeKey);
    if (!node) {
      return;
    }
    nextDraft = upsertSetNodeChannelDraft(
      nextDraft,
      graphBundle,
      node.type,
      node.serial,
      node.sourceRevision,
      node.coreRevision,
      channel,
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
  const channelOverrides = new Map<string, SetNodeChannelOperation>();
  graphDraft.operations.forEach((operation) => {
    if (operation.type === 'RenameNodeAlias') {
      aliasOverrides.set(buildAliasOperationKey(operation.nodeType, operation.serial), operation);
      return;
    }
    if (operation.type === 'ReplaceTriggerSourceTargets') {
      replaceOverrides.set(operation.triggerSourceSerial, operation);
      return;
    }
    channelOverrides.set(buildAliasOperationKey(operation.nodeType, operation.serial), operation);
  });

  const nodes = graphBundle.nodes.map((node) => {
    const aliasOverride = aliasOverrides.get(buildAliasOperationKey(node.type, node.serial));
    const channelOverride = channelOverrides.get(buildAliasOperationKey(node.type, node.serial));
    const nextAlias = aliasOverride == null ? node.alias : normalizeAlias(aliasOverride.alias);
    const nextChannel = channelOverride == null ? node.channel : normalizeChannel(channelOverride.channel);
    const nextConnectionMode =
      channelOverride == null ? node.connectionMode : nextChannel > 0 ? 'channel' : 'serial';
    const nextDisplayText = nextAlias
      ? `${nextAlias}(#${node.serial})`
      : `${node.type}(#${node.serial})`;
    if (
      aliasOverride == null &&
      channelOverride == null &&
      nextAlias === node.alias &&
      nextConnectionMode === node.connectionMode &&
      nextChannel === node.channel &&
      nextDisplayText === node.displayText
    ) {
      return node;
    }
    return {
      ...node,
      alias: nextAlias,
      displayText: nextDisplayText,
      connectionMode: nextConnectionMode,
      channel: nextChannel,
    };
  });

  const explicitEdges: GraphSnapshotBundle['edges'] = [];
  const replacedSources = new Set<number>(Array.from(replaceOverrides.keys()));
  graphBundle.edges.forEach((edge) => {
    const matched = edge.sourceNodeKey.match(/^triggerSource:(\d+)$/);
    const sourceSerial = matched == null ? 0 : Number(matched[1]);
    if (
      replacedSources.has(sourceSerial) ||
      isTriggerSourceReturningToSerial(graphBundle, graphDraft, sourceSerial)
    ) {
      return;
    }
    explicitEdges.push(edge);
  });
  replaceOverrides.forEach((operation, triggerSourceSerial) => {
    operation.targetCoreSerials.forEach((targetCoreSerial) => {
      const sourceNodeKey = createGraphNodeKey('triggerSource', triggerSourceSerial);
      const targetNodeKey = createGraphNodeKey('core', targetCoreSerial);
      explicitEdges.push({
        edgeKey: `${sourceNodeKey}->${targetNodeKey}`,
        sourceNodeKey,
        targetNodeKey,
        kind: 'serial',
        readable: true,
        editable: true,
      });
    });
  });

  const nodeByKey = new Map(nodes.map((node) => [node.nodeKey, node] as const));
  const explicitTargetSerialsBySource = new Map<number, number[]>();
  explicitEdges.forEach((edge) => {
    const matched = edge.sourceNodeKey.match(/^triggerSource:(\d+)$/);
    const sourceSerial = matched == null ? 0 : Number(matched[1]);
    const targetMatched = edge.targetNodeKey.match(/^core:(\d+)$/);
    const targetSerial = targetMatched == null ? 0 : Number(targetMatched[1]);
    if (sourceSerial <= 0 || targetSerial <= 0) {
      return;
    }
    const currentTargets = explicitTargetSerialsBySource.get(sourceSerial) ?? [];
    currentTargets.push(targetSerial);
    explicitTargetSerialsBySource.set(sourceSerial, currentTargets);
  });

  const channelCoreSerialsByChannel = new Map<number, number[]>();
  nodes.forEach((node) => {
    if (node.type !== 'core' || node.connectionMode !== 'channel' || node.channel <= 0) {
      return;
    }
    const currentValues = channelCoreSerialsByChannel.get(node.channel) ?? [];
    currentValues.push(node.serial);
    channelCoreSerialsByChannel.set(node.channel, currentValues);
  });

  const edges: GraphSnapshotBundle['edges'] = [];
  nodes
    .filter((node) => node.type === 'triggerSource')
    .forEach((sourceNode) => {
      const nextTargetSerials =
        sourceNode.connectionMode === 'channel' && sourceNode.channel > 0
          ? normalizeTargetSerials(channelCoreSerialsByChannel.get(sourceNode.channel) ?? [])
          : normalizeTargetSerials(
              (explicitTargetSerialsBySource.get(sourceNode.serial) ?? []).filter((targetSerial) => {
                const targetNode = nodeByKey.get(createGraphNodeKey('core', targetSerial));
                return targetNode?.connectionMode !== 'channel';
              }),
            );
      nextTargetSerials.forEach((targetCoreSerial) => {
        const sourceNodeKey = createGraphNodeKey('triggerSource', sourceNode.serial);
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
    if (operation.type === 'SetNodeChannel') {
      changedNodeKeys.add(createGraphNodeKey(operation.nodeType, operation.serial));
      return;
    }
    const sourceNodeKey = createGraphNodeKey('triggerSource', operation.triggerSourceSerial);
    changedNodeKeys.add(sourceNodeKey);
    const baseTargets = resolveExplicitSerialBaseTargetSerials(
      baseGraphBundle,
      graphDraft,
      operation.triggerSourceSerial,
    );
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
        alias: updatedNodeState.alias ?? node.alias,
        displayText: updatedNodeState.displayText ?? node.displayText,
        connectionMode: updatedNodeState.connectionMode ?? node.connectionMode,
        channel: updatedNodeState.channel ?? node.channel,
        sourceRevision: updatedNodeState.sourceRevision ?? node.sourceRevision,
        coreRevision: updatedNodeState.coreRevision ?? node.coreRevision,
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

export async function submitGraphSave(
  graphDraft: GraphDraft,
  requestMode: string,
): Promise<GraphWriteResponse> {
  const response = await fetch('./api/graph/save', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: buildGraphWriteRequestPayload(graphDraft, requestMode),
  });
  const payload = await response.json();
  return parseGraphWriteResponse(payload);
}

export async function previewGraphSave(
  graphDraft: GraphDraft,
  requestMode: string,
): Promise<GraphWriteResponse> {
  const response = await fetch('./api/graph/preview', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: buildGraphWriteRequestPayload(graphDraft, requestMode),
  });
  const payload = await response.json();
  return parseGraphWriteResponse(payload);
}

function buildGraphWriteRequestPayload(
  graphDraft: GraphDraft,
  requestMode: string,
): string {
  return JSON.stringify({
    draftId: graphDraft.draftId,
    baseSnapshotId: graphDraft.baseSnapshotId,
    mode: requestMode,
    baseGraphRevision: graphDraft.baseGraphRevision,
    operations: graphDraft.operations,
  });
}
