import { useEffect, useMemo, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  Position,
  type Edge,
  type Node,
  type ReactFlowInstance,
  useEdgesState,
  useNodesState,
} from 'reactflow';
import 'reactflow/dist/style.css';
import type {
  GraphDraft,
  GraphNodeInfo,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
  GraphUpdatedNodeState,
  GraphWriteResponse,
  ReplaceTriggerSourceTargetsOperation,
  RenameNodeAliasOperation,
} from '../graphTypes';
import {
  createGraphNodeKey,
  createInitialGraphDraft,
  parseGraphDraft,
  parseGraphWriteResponse,
  serializeGraphDraft,
} from '../graphTypes';

const TRIGGER_SOURCE_X = 80;
const CORE_X = 430;
const LANE_START_Y = 64;
const LANE_GAP_Y = 92;
const NODE_CENTER_OFFSET_X = 116;
const NODE_CENTER_OFFSET_Y = 30;

type GraphFlowNode = Node<{ label: JSX.Element }>;
type SavePhase = 'idle' | 'saving' | 'conflict' | 'error';
type GraphEditMode = 'view' | 'add' | 'remove' | 'replace';

type GraphViewerProps = {
  graphBundle: GraphSnapshotBundle;
  graphFileName: string;
  onDirtyStateChange?: (dirty: boolean) => void;
};

function buildNodeLabel(node: GraphNodeInfo): JSX.Element {
  return (
    <div className="graph-node-label">
      <span className="graph-node-eyebrow">{node.type}</span>
      <strong className="graph-node-title">{node.displayText}</strong>
      <span className="graph-node-meta">
        #{node.serial} · {node.online ? 'online' : 'offline'} · {node.active ? 'active' : 'idle'}
      </span>
    </div>
  );
}

function matchesSearch(node: GraphNodeInfo, query: string): boolean {
  if (!query) {
    return true;
  }
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return true;
  }
  return [
    node.nodeKey,
    node.displayText,
    node.alias,
    node.type,
    node.connectionMode,
    String(node.serial),
    String(node.channel),
  ].some((value) => value.toLowerCase().includes(normalizedQuery));
}

function buildNodeStyle(
  node: GraphNodeInfo,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  selectedEditSourceNodeKeys: Set<string>,
  selectedEditTargetNodeKeys: Set<string>,
): React.CSSProperties {
  const selected = selectedNodeKey === node.nodeKey;
  const matched = matchedNodeKeys.has(node.nodeKey);
  const dimmed = hasSearch && !matched;
  const selectedAsSource = selectedEditSourceNodeKeys.has(node.nodeKey);
  const selectedAsTarget = selectedEditTargetNodeKeys.has(node.nodeKey);
  const accent = node.type === 'triggerSource' ? '#ffb894' : '#8cd5ff';
  const baseBackground =
    node.type === 'triggerSource'
      ? 'rgba(255, 165, 122, 0.12)'
      : 'rgba(95, 196, 255, 0.12)';
  const selectionAccent = selectedAsSource
    ? '#ff8a4f'
    : selectedAsTarget
      ? '#5dd4ff'
      : accent;
  return {
    minWidth: 232,
    borderRadius: 16,
    border: `1px solid ${selected || selectedAsSource || selectedAsTarget ? selectionAccent : 'rgba(255, 214, 191, 0.22)'}`,
    background: baseBackground,
    boxShadow:
      selected || selectedAsSource || selectedAsTarget
        ? `0 0 0 1px ${selectionAccent} inset, 0 12px 24px rgba(6, 10, 18, 0.24)`
        : 'none',
    color: '#fff4eb',
    opacity: dimmed ? 0.34 : 1,
  };
}

function buildEdgeStyle(hasSearch: boolean, matchedNodeKeys: Set<string>, edge: Edge): Edge {
  const matched = matchedNodeKeys.has(edge.source) || matchedNodeKeys.has(edge.target);
  return {
    ...edge,
    style: {
      stroke: '#ffc296',
      strokeWidth: 2.2,
      opacity: hasSearch && !matched ? 0.2 : 0.88,
    },
  };
}

function buildAutoLayoutNodes(
  nodes: GraphNodeInfo[],
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  selectedEditSourceNodeKeys: Set<string>,
  selectedEditTargetNodeKeys: Set<string>,
): GraphFlowNode[] {
  let triggerSourceIndex = 0;
  let coreIndex = 0;
  return nodes.map((node) => {
    const rowIndex = node.type === 'triggerSource' ? triggerSourceIndex++ : coreIndex++;
    return {
      id: node.nodeKey,
      position: {
        x: node.type === 'triggerSource' ? TRIGGER_SOURCE_X : CORE_X,
        y: LANE_START_Y + rowIndex * LANE_GAP_Y,
      },
      data: {
        label: buildNodeLabel(node),
      },
      draggable: true,
      selectable: true,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      style: buildNodeStyle(
        node,
        selectedNodeKey,
        hasSearch,
        matchedNodeKeys,
        selectedEditSourceNodeKeys,
        selectedEditTargetNodeKeys,
      ),
    };
  });
}

function buildEdges(
  graphBundle: GraphSnapshotBundle,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
): Edge[] {
  return graphBundle.edges.map((edge) =>
    buildEdgeStyle(hasSearch, matchedNodeKeys, {
      id: edge.edgeKey,
      source: edge.sourceNodeKey,
      target: edge.targetNodeKey,
      animated: false,
      label: edge.kind,
    }),
  );
}

function buildDraftFileName(graphFileName: string, snapshotId: string): string {
  const normalizedBaseName = graphFileName.trim()
    ? graphFileName.replace(/\.json\.gz$/i, '')
    : snapshotId;
  return `draft-${normalizedBaseName}.json`;
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

function sameNumberArray(left: number[], right: number[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
}

function toggleNumberSelection(currentValues: number[], targetValue: number): number[] {
  if (currentValues.includes(targetValue)) {
    return currentValues.filter((value) => value !== targetValue);
  }
  return [...currentValues, targetValue].sort((left, right) => left - right);
}

function formatEditModeLabel(editMode: GraphEditMode): string {
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

function formatEditModeInstruction(editMode: GraphEditMode): string {
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

function resolveEffectiveTargetSerials(
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

function upsertAliasDraft(
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
function applyBatchEditToDraft(
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

function applyDraftToGraph(
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

function applyUpdatedNodeStates(
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

async function loadDraft(draftFileName: string): Promise<GraphDraft | null> {
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

async function persistDraft(draftFileName: string, graphDraft: GraphDraft): Promise<void> {
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

async function submitGraphSave(graphDraft: GraphDraft): Promise<GraphWriteResponse> {
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

export default function GraphViewer({
  graphBundle,
  graphFileName,
  onDirtyStateChange,
}: GraphViewerProps) {
  const [baseGraphBundle, setBaseGraphBundle] = useState<GraphSnapshotBundle>(graphBundle);
  const [graphDraft, setGraphDraft] = useState<GraphDraft>(() =>
    createInitialGraphDraft(graphBundle),
  );
  const [draftLoading, setDraftLoading] = useState(false);
  const [draftError, setDraftError] = useState('');
  const [draftPersistError, setDraftPersistError] = useState('');
  const [savePhase, setSavePhase] = useState<SavePhase>('idle');
  const [saveMessage, setSaveMessage] = useState('');
  const [searchText, setSearchText] = useState('');
  const [selectedNodeKey, setSelectedNodeKey] = useState<string>('');
  const [editMode, setEditMode] = useState<GraphEditMode>('view');
  const [selectedEditSourceSerials, setSelectedEditSourceSerials] = useState<number[]>([]);
  const [selectedEditTargetSerials, setSelectedEditTargetSerials] = useState<number[]>([]);
  const [reactFlowInstance, setReactFlowInstance] = useState<ReactFlowInstance | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  const draftFileName = useMemo(
    () => buildDraftFileName(graphFileName, graphBundle.snapshotId),
    [graphBundle.snapshotId, graphFileName],
  );
  const effectiveGraphBundle = useMemo(
    () => applyDraftToGraph(baseGraphBundle, graphDraft),
    [baseGraphBundle, graphDraft],
  );
  const nodeByKey = useMemo(
    () => new Map(effectiveGraphBundle.nodes.map((node) => [node.nodeKey, node])),
    [effectiveGraphBundle.nodes],
  );
  const edgeCountByNodeKey = useMemo(() => {
    const counts = new Map<string, number>();
    effectiveGraphBundle.edges.forEach((edge) => {
      counts.set(edge.sourceNodeKey, (counts.get(edge.sourceNodeKey) ?? 0) + 1);
      counts.set(edge.targetNodeKey, (counts.get(edge.targetNodeKey) ?? 0) + 1);
    });
    return counts;
  }, [effectiveGraphBundle.edges]);
  const hasSearch = searchText.trim().length > 0;
  const matchedNodes = useMemo(
    () => effectiveGraphBundle.nodes.filter((node) => matchesSearch(node, searchText)),
    [effectiveGraphBundle.nodes, searchText],
  );
  const matchedNodeKeys = useMemo(
    () => new Set(matchedNodes.map((node) => node.nodeKey)),
    [matchedNodes],
  );
  const selectedNode = selectedNodeKey ? nodeByKey.get(selectedNodeKey) ?? null : null;
  const selectedEditSourceNodeKeys = useMemo(
    () => new Set(selectedEditSourceSerials.map((serial) => createGraphNodeKey('triggerSource', serial))),
    [selectedEditSourceSerials],
  );
  const selectedEditTargetNodeKeys = useMemo(
    () => new Set(selectedEditTargetSerials.map((serial) => createGraphNodeKey('core', serial))),
    [selectedEditTargetSerials],
  );
  const selectedEditSourceNodes = useMemo(
    () =>
      selectedEditSourceSerials
        .map((serial) => nodeByKey.get(createGraphNodeKey('triggerSource', serial)) ?? null)
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedEditSourceSerials],
  );
  const selectedEditTargetNodes = useMemo(
    () =>
      selectedEditTargetSerials
        .map((serial) => nodeByKey.get(createGraphNodeKey('core', serial)) ?? null)
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedEditTargetSerials],
  );
  const selectedTriggerSourceTargets =
    selectedNode?.type === 'triggerSource'
      ? resolveEffectiveTargetSerials(baseGraphBundle, graphDraft, selectedNode.serial)
      : [];
  const selectedTriggerSourceTargetNodes = selectedTriggerSourceTargets
    .map((serial) => nodeByKey.get(createGraphNodeKey('core', serial)) ?? null)
    .filter((node): node is GraphNodeInfo => node != null);
  const canApplyBatchEdit =
    editMode === 'replace'
      ? selectedEditSourceSerials.length > 0
      : editMode !== 'view' &&
        selectedEditSourceSerials.length > 0 &&
        selectedEditTargetSerials.length > 0;
  const statusClassName =
    savePhase === 'saving'
      ? 'status-pill is-waiting'
      : savePhase === 'conflict' || savePhase === 'error'
        ? 'status-pill is-error'
        : graphDraft.dirty
          ? 'status-pill is-waiting'
          : 'status-pill is-ready';
  const statusText =
    savePhase === 'saving'
      ? '保存中'
      : savePhase === 'conflict'
        ? '保存冲突'
        : savePhase === 'error'
          ? '保存失败'
          : graphDraft.dirty
            ? '未保存'
            : '已同步';

  useEffect(() => {
    let disposed = false;
    setDraftLoading(true);
    setDraftError('');
    setSavePhase('idle');
    setSaveMessage('');
    setEditMode('view');
    setSelectedEditSourceSerials([]);
    setSelectedEditTargetSerials([]);
    setBaseGraphBundle(graphBundle);
    setGraphDraft(createInitialGraphDraft(graphBundle));
    loadDraft(draftFileName)
      .then((loadedDraft) => {
        if (disposed) {
          return;
        }
        if (loadedDraft && loadedDraft.baseSnapshotId === graphBundle.snapshotId) {
          setGraphDraft({
            ...loadedDraft,
            dirty: loadedDraft.operations.length > 0,
          });
        } else {
          setGraphDraft(createInitialGraphDraft(graphBundle));
        }
      })
      .catch((error) => {
        if (disposed) {
          return;
        }
        setDraftError(error instanceof Error ? error.message : 'unknown error');
        setGraphDraft(createInitialGraphDraft(graphBundle));
      })
      .finally(() => {
        if (!disposed) {
          setDraftLoading(false);
        }
      });
    return () => {
      disposed = true;
    };
  }, [draftFileName, graphBundle.snapshotId]);

  useEffect(() => {
    if (draftLoading) {
      return;
    }
    const initialNodeKey = effectiveGraphBundle.nodes[0]?.nodeKey ?? '';
    setSelectedNodeKey(initialNodeKey);
    setNodes(
      buildAutoLayoutNodes(
        effectiveGraphBundle.nodes,
        initialNodeKey,
        false,
        new Set<string>(),
        new Set<string>(),
        new Set<string>(),
      ),
    );
    setEdges(buildEdges(effectiveGraphBundle, false, new Set<string>()));
  }, [draftLoading, graphBundle.snapshotId, setEdges, setNodes]);

  useEffect(() => {
    setNodes((currentNodes) => {
      const positionByNodeKey = new Map(
        currentNodes.map((node) => [node.id, node.position] as const),
      );
      return effectiveGraphBundle.nodes.map((node) => {
        const existingPosition = positionByNodeKey.get(node.nodeKey);
        const autoLayoutNode = buildAutoLayoutNodes(
          [node],
          selectedNodeKey,
          hasSearch,
          matchedNodeKeys,
          selectedEditSourceNodeKeys,
          selectedEditTargetNodeKeys,
        )[0];
        return {
          ...autoLayoutNode,
          position: existingPosition ?? autoLayoutNode.position,
        };
      });
    });
    setEdges(buildEdges(effectiveGraphBundle, hasSearch, matchedNodeKeys));
  }, [
    effectiveGraphBundle,
    hasSearch,
    matchedNodeKeys,
    selectedNodeKey,
    selectedEditSourceNodeKeys,
    selectedEditTargetNodeKeys,
    setEdges,
    setNodes,
  ]);

  useEffect(() => {
    if (draftLoading || !reactFlowInstance || effectiveGraphBundle.nodes.length === 0) {
      return;
    }
    // 等待画布容器完成本轮布局后再归位，避免首帧父容器尺寸尚未稳定导致图层不可见。
    const frameId = window.requestAnimationFrame(() => {
      reactFlowInstance.fitView({ padding: 0.18, duration: 260 });
    });
    return () => {
      window.cancelAnimationFrame(frameId);
    };
  }, [
    draftLoading,
    effectiveGraphBundle.nodes.length,
    graphBundle.snapshotId,
    reactFlowInstance,
  ]);

  useEffect(() => {
    if (onDirtyStateChange) {
      onDirtyStateChange(graphDraft.dirty);
    }
    return () => {
      if (onDirtyStateChange) {
        onDirtyStateChange(false);
      }
    };
  }, [graphDraft.dirty, onDirtyStateChange]);

  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!graphDraft.dirty) {
        return;
      }
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
    };
  }, [graphDraft.dirty]);

  useEffect(() => {
    if (draftLoading) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      void persistDraft(draftFileName, graphDraft)
        .then(() => setDraftPersistError(''))
        .catch((error) =>
          setDraftPersistError(error instanceof Error ? error.message : 'unknown error'),
        );
    }, 220);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [draftFileName, draftLoading, graphDraft]);

  function focusNode(nodeKey: string) {
    setSelectedNodeKey(nodeKey);
    const currentNode = nodes.find((node) => node.id === nodeKey);
    if (!currentNode || !reactFlowInstance) {
      return;
    }
    reactFlowInstance.setCenter(
      currentNode.position.x + NODE_CENTER_OFFSET_X,
      currentNode.position.y + NODE_CENTER_OFFSET_Y,
      { zoom: 1.12, duration: 260 },
    );
  }

  function handleAutoLayout() {
    setNodes(
      buildAutoLayoutNodes(
        effectiveGraphBundle.nodes,
        selectedNodeKey,
        hasSearch,
        matchedNodeKeys,
        selectedEditSourceNodeKeys,
        selectedEditTargetNodeKeys,
      ),
    );
    window.requestAnimationFrame(() => {
      reactFlowInstance?.fitView({ padding: 0.18, duration: 260 });
    });
  }

  function handleEditModeChange(nextEditMode: GraphEditMode) {
    setEditMode(nextEditMode);
    if (nextEditMode === 'view') {
      setSelectedEditSourceSerials([]);
      setSelectedEditTargetSerials([]);
    }
  }

  function handleClearBatchSelection() {
    setSelectedEditSourceSerials([]);
    setSelectedEditTargetSerials([]);
  }

  function handleGraphNodeClick(nodeKey: string) {
    setSelectedNodeKey(nodeKey);
    if (editMode === 'view') {
      return;
    }
    const clickedNode = nodeByKey.get(nodeKey);
    if (!clickedNode) {
      return;
    }
    if (clickedNode.type === 'triggerSource') {
      setSelectedEditSourceSerials((currentValues) =>
        toggleNumberSelection(currentValues, clickedNode.serial),
      );
      return;
    }
    setSelectedEditTargetSerials((currentValues) =>
      toggleNumberSelection(currentValues, clickedNode.serial),
    );
  }

  function handleAliasChange(nodeType: GraphNodeTypeToken, serial: number, alias: string) {
    setDraftPersistError('');
    if (savePhase === 'error' || savePhase === 'conflict') {
      setSavePhase('idle');
    }
    setGraphDraft((currentDraft) =>
      upsertAliasDraft(currentDraft, baseGraphBundle, nodeType, serial, alias),
    );
  }

  function handleApplyBatchEdit() {
    if (!canApplyBatchEdit || editMode === 'view') {
      return;
    }
    setDraftPersistError('');
    if (savePhase === 'error' || savePhase === 'conflict') {
      setSavePhase('idle');
    }
    setGraphDraft((currentDraft) =>
      applyBatchEditToDraft(
        currentDraft,
        baseGraphBundle,
        editMode,
        selectedEditSourceSerials,
        selectedEditTargetSerials,
      ),
    );
    setSaveMessage(
      `已将 ${formatEditModeLabel(editMode)} 操作写入本地草稿，点击 Save 后才会回传游戏真值。`,
    );
  }

  async function handleSave() {
    if (!graphDraft.dirty || savePhase === 'saving') {
      return;
    }
    setSavePhase('saving');
    setSaveMessage('');
    try {
      const graphWriteResponse = await submitGraphSave(graphDraft);
      if (graphWriteResponse.status === 'error') {
        setSavePhase('error');
        setSaveMessage(graphWriteResponse.message || 'graph 保存请求失败。');
        return;
      }
      if (graphWriteResponse.result === 'conflict') {
        setSavePhase('conflict');
        setSaveMessage(
          graphWriteResponse.message || '保存冲突：请重新导出 graph 文件后再试。',
        );
        return;
      }
      if (graphWriteResponse.result === 'rejected') {
        setSavePhase('error');
        setSaveMessage(graphWriteResponse.message || '保存被服务端拒绝。');
        return;
      }
      const nextBaseGraphBundle = applyUpdatedNodeStates(
        applyDraftToGraph(baseGraphBundle, graphDraft),
        graphWriteResponse,
      );
      setBaseGraphBundle(nextBaseGraphBundle);
      setGraphDraft(createInitialGraphDraft(nextBaseGraphBundle));
      setSavePhase('idle');
      setSaveMessage(graphWriteResponse.message || '已保存。');
    } catch (error) {
      setSavePhase('error');
      setSaveMessage(error instanceof Error ? error.message : 'unknown error');
    }
  }

  return (
    <section className="graph-viewer">
      <div className="graph-toolbar-block">
        <div className="graph-toolbar-row">
          <label className="recording-file-field graph-search-field">
            <span>搜索节点</span>
            <input
              className="graph-search-input"
              type="text"
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              placeholder="按别名、序号、nodeKey、连接模式搜索"
            />
          </label>
          <div className="graph-editor-actions">
            <span className={statusClassName}>{statusText}</span>
            <button type="button" className="action-button" onClick={handleAutoLayout}>
              重新布局
            </button>
            <button
              type="button"
              className="action-button"
              disabled={!graphDraft.dirty || savePhase === 'saving'}
              onClick={() => void handleSave()}
            >
              Save
            </button>
          </div>
        </div>
        <div className="recording-toolbar-row">
          <span className="chart-toolbar-label">编辑模式</span>
          <div className="chip-group">
            {(['view', 'add', 'remove', 'replace'] as GraphEditMode[]).map((modeValue) => (
              <button
                key={modeValue}
                type="button"
                className={`metric-chip${editMode === modeValue ? ' is-active' : ''}`}
                onClick={() => handleEditModeChange(modeValue)}
              >
                {formatEditModeLabel(modeValue)}
              </button>
            ))}
          </div>
        </div>
        <p className="chart-interaction-hint">
          鼠标滚轮缩放，拖动画布平移，拖拽节点只影响本地布局；双击节点会聚焦到该节点。
          {formatEditModeInstruction(editMode)} 网页修改只进入本地草稿，点击 Save 后才会回传游戏真值。
        </p>
        {saveMessage ? <p className="graph-editor-message">{saveMessage}</p> : null}
        {draftError ? <p className="error-text">加载本地 draft 失败：{draftError}</p> : null}
        {draftPersistError ? (
          <p className="error-text">写入本地 draft 失败：{draftPersistError}</p>
        ) : null}
        {hasSearch ? (
          <div className="graph-search-results">
            {matchedNodes.length === 0 ? (
              <span className="empty-state">没有命中当前搜索条件的节点。</span>
            ) : (
              matchedNodes.slice(0, 12).map((node) => (
                <button
                  key={node.nodeKey}
                  type="button"
                  className={`graph-search-chip${selectedNodeKey === node.nodeKey ? ' is-selected' : ''}`}
                  onClick={() => focusNode(node.nodeKey)}
                >
                  {node.displayText}
                </button>
              ))
            )}
          </div>
        ) : null}
      </div>

      <div className="graph-workspace">
        <div className="graph-canvas-card">
          <div className="graph-canvas-header">
            <div>
              <span className="section-tag">Serial Editor</span>
              <h3>显式保存拓扑图</h3>
            </div>
            <dl className="graph-inline-stats">
              <div>
                <dt>Nodes</dt>
                <dd>{effectiveGraphBundle.stats.nodeCount}</dd>
              </div>
              <div>
                <dt>Edges</dt>
                <dd>{effectiveGraphBundle.edges.length}</dd>
              </div>
              <div>
                <dt>Revision</dt>
                <dd>{baseGraphBundle.graphRevision}</dd>
              </div>
            </dl>
          </div>
          <div className="graph-canvas">
            {draftLoading ? (
              <div className="graph-empty-overlay">
                <p className="empty-state">正在加载 graph draft...</p>
              </div>
            ) : null}
            {effectiveGraphBundle.nodes.length === 0 ? (
              <div className="graph-empty-overlay">
                <p className="empty-state">当前图快照没有可展示节点。</p>
              </div>
            ) : null}
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={(_, node) => handleGraphNodeClick(String(node.id))}
              onNodeDoubleClick={(_, node) => focusNode(node.id)}
              onPaneClick={() => setSelectedNodeKey('')}
              onInit={setReactFlowInstance}
              fitView
              fitViewOptions={{ padding: 0.18 }}
              minZoom={0.2}
              maxZoom={2.2}
              nodesConnectable={false}
              elementsSelectable
              panOnDrag
              zoomOnScroll
            >
              <Background color="rgba(255, 214, 191, 0.12)" gap={24} size={1} />
              <MiniMap
                pannable
                zoomable
                nodeColor={(node) =>
                  String(node.id).startsWith('triggerSource:')
                    ? 'rgba(255, 181, 140, 0.82)'
                    : 'rgba(140, 213, 255, 0.82)'
                }
                maskColor="rgba(10, 12, 18, 0.28)"
              />
              <Controls />
            </ReactFlow>
          </div>
        </div>

        <aside className="graph-detail-card">
          <header className="card-header">
            <span className="section-tag">Details</span>
            <h2>节点详情与编辑</h2>
          </header>
          {editMode !== 'view' ? (
            <section className="graph-batch-editor">
              <div className="graph-batch-editor-header">
                <strong>{formatEditModeLabel(editMode)} 批量拓扑编辑</strong>
                <span>
                  已选来源 {selectedEditSourceSerials.length} 个 / 目标 {selectedEditTargetSerials.length}{' '}
                  个
                </span>
              </div>
              <p className="graph-batch-editor-caption">{formatEditModeInstruction(editMode)}</p>
              {editMode === 'replace' ? (
                <p className="graph-batch-editor-caption">
                  `replace` 模式允许来源集合为空目标，应用后可直接清空这些 triggerSource 的全部连接。
                </p>
              ) : null}
              <div className="graph-batch-selection-grid">
                <section className="graph-target-editor">
                  <div className="graph-target-editor-header">
                    <strong>Selected TriggerSources</strong>
                    <span>点击图中的 triggerSource 可加入或移出本次批量编辑。</span>
                  </div>
                  <div className="graph-target-list">
                    {selectedEditSourceNodes.length === 0 ? (
                      <p className="empty-state">当前还没有选中来源节点。</p>
                    ) : (
                      selectedEditSourceNodes.map((sourceNode) => (
                        <button
                          key={sourceNode.nodeKey}
                          type="button"
                          className="graph-target-item graph-target-chip"
                          onClick={() => focusNode(sourceNode.nodeKey)}
                        >
                          {sourceNode.displayText}
                        </button>
                      ))
                    )}
                  </div>
                </section>
                <section className="graph-target-editor">
                  <div className="graph-target-editor-header">
                    <strong>Selected Cores</strong>
                    <span>点击图中的 core 可加入或移出目标集合。</span>
                  </div>
                  <div className="graph-target-list">
                    {selectedEditTargetNodes.length === 0 ? (
                      <p className="empty-state">当前还没有选中目标节点。</p>
                    ) : (
                      selectedEditTargetNodes.map((targetNode) => (
                        <button
                          key={targetNode.nodeKey}
                          type="button"
                          className="graph-target-item graph-target-chip"
                          onClick={() => focusNode(targetNode.nodeKey)}
                        >
                          {targetNode.displayText}
                        </button>
                      ))
                    )}
                  </div>
                </section>
              </div>
              <div className="graph-batch-action-row">
                <button type="button" className="action-button" onClick={handleClearBatchSelection}>
                  清空选择
                </button>
                <button
                  type="button"
                  className="action-button"
                  disabled={!canApplyBatchEdit}
                  onClick={handleApplyBatchEdit}
                >
                  应用到草稿
                </button>
              </div>
            </section>
          ) : null}
          {!selectedNode ? (
            <p className="empty-state">
              点击图中的一个节点后，这里会显示其运行态、revision，以及第一版可编辑字段。
            </p>
          ) : (
            <>
              <div className="graph-detail-hero">
                <p className="eyebrow">{selectedNode.type}</p>
                <h3>{selectedNode.displayText}</h3>
                <p className="graph-detail-caption">{selectedNode.nodeKey}</p>
              </div>
              <label className="graph-editor-field">
                <span>Alias</span>
                <input
                  className="graph-editor-input"
                  type="text"
                  value={selectedNode.alias}
                  onChange={(event) =>
                    handleAliasChange(selectedNode.type, selectedNode.serial, event.target.value)
                  }
                  placeholder="输入节点别名，留空则清空"
                />
              </label>
              <dl className="preview-meta graph-detail-grid">
                <div>
                  <dt>Serial</dt>
                  <dd>{selectedNode.serial}</dd>
                </div>
                <div>
                  <dt>Online</dt>
                  <dd>{selectedNode.online ? 'true' : 'false'}</dd>
                </div>
                <div>
                  <dt>Active</dt>
                  <dd>{selectedNode.active ? 'true' : 'false'}</dd>
                </div>
                <div>
                  <dt>Input Power</dt>
                  <dd>{selectedNode.inputPower}</dd>
                </div>
                <div>
                  <dt>Output Power</dt>
                  <dd>{selectedNode.outputPower}</dd>
                </div>
                <div>
                  <dt>Connection Mode</dt>
                  <dd>{selectedNode.connectionMode}</dd>
                </div>
                <div>
                  <dt>Channel</dt>
                  <dd>{selectedNode.channel}</dd>
                </div>
                <div>
                  <dt>Source Revision</dt>
                  <dd>{selectedNode.sourceRevision}</dd>
                </div>
                <div>
                  <dt>Core Revision</dt>
                  <dd>{selectedNode.coreRevision}</dd>
                </div>
                <div>
                  <dt>Allocated</dt>
                  <dd>{selectedNode.allocated ? 'true' : 'false'}</dd>
                </div>
                <div>
                  <dt>Retired</dt>
                  <dd>{selectedNode.retired ? 'true' : 'false'}</dd>
                </div>
                <div>
                  <dt>Incident Edges</dt>
                  <dd>{edgeCountByNodeKey.get(selectedNode.nodeKey) ?? 0}</dd>
                </div>
              </dl>
              {selectedNode.type === 'triggerSource' ? (
                <section className="graph-target-editor">
                  <div className="graph-target-editor-header">
                    <strong>Current Target Cores</strong>
                    <span>这里展示当前草稿视角下的有效目标集合；批量编辑请使用上方模式工具栏。</span>
                  </div>
                  <div className="graph-target-list">
                    {selectedTriggerSourceTargetNodes.length === 0 ? (
                      <p className="empty-state">当前 triggerSource 在草稿视角下没有目标 core。</p>
                    ) : (
                      selectedTriggerSourceTargetNodes.map((coreNode) => (
                        <button
                          key={coreNode.nodeKey}
                          type="button"
                          className="graph-target-item graph-target-chip"
                          onClick={() => focusNode(coreNode.nodeKey)}
                        >
                          {coreNode.displayText}
                        </button>
                      ))
                    )}
                  </div>
                </section>
              ) : null}
              <div className="graph-flag-block">
                <strong>Capability Flags</strong>
                <div className="graph-flag-list">
                  {selectedNode.capabilityFlags.length === 0 ? (
                    <span className="graph-flag-chip">-</span>
                  ) : (
                    selectedNode.capabilityFlags.map((flag) => (
                      <span key={flag} className="graph-flag-chip">
                        {flag}
                      </span>
                    ))
                  )}
                </div>
              </div>
            </>
          )}
        </aside>
      </div>
    </section>
  );
}
