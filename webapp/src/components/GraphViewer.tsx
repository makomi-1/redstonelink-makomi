import { useEffect, useMemo, useRef, useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  Position,
  SelectionMode,
  type Edge,
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
  type XYPosition,
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

const GRAPH_NODE_WIDTH = 232;
const GRAPH_NODE_HEIGHT = 60;
const AUTO_LAYOUT_START_X = 72;
const AUTO_LAYOUT_START_Y = 64;
const COMPONENT_LAYER_GAP_X = 364;
const COMPONENT_LANE_COLUMN_GAP_X = 70;
const COMPONENT_NODE_GAP_Y = 104;
const COMPONENT_BLOCK_GAP_X = 156;
const COMPONENT_BLOCK_GAP_Y = 172;
const AUTO_LAYOUT_MAX_ROW_WIDTH = 1960;
const COMPONENT_LANE_MIN_ROW_COUNT = 3;
const COMPONENT_LANE_MAX_ROW_COUNT = 7;
const LANE_JITTER_X = 18;
const LANE_JITTER_Y = 14;
const LANE_COLUMN_STAGGER_Y = 14;
const COMPONENT_STAGGER_X = 18;
const COMPONENT_STAGGER_Y = 22;
const SHARED_CORE_GROUP_MIN_SOURCE_COUNT = 1;
const SHARED_CORE_GROUP_MIN_CORE_COUNT = 2;
const NODE_CENTER_OFFSET_X = GRAPH_NODE_WIDTH / 2;
const NODE_CENTER_OFFSET_Y = GRAPH_NODE_HEIGHT / 2;
const AGGREGATE_OUTLINE_PADDING_X = 28;
const AGGREGATE_OUTLINE_PADDING_Y = 28;

type GraphFlowNodeData = {
  label?: JSX.Element;
  canvasNodeKey: string;
};
type GraphFlowNode = Node<GraphFlowNodeData>;
type SavePhase = 'idle' | 'saving' | 'conflict' | 'error';
type GraphEditMode = 'view' | 'add' | 'remove' | 'replace';
type GraphSearchTypeFilter = 'all' | GraphNodeTypeToken;
type GraphSidebarPanel = 'details' | 'isolated' | 'batch';
type DraftEdgeDiffState = 'base' | 'added' | 'removed';
type GraphCanvasActualNode = {
  kind: 'actual';
  nodeKey: string;
  graphNode: GraphNodeInfo;
};
type GraphCanvasAggregateNode = {
  kind: 'aggregate';
  nodeKey: string;
  signatureKey: string;
  sourceNodeKeys: string[];
  sourceSerials: number[];
  coreNodeKeys: string[];
  coreSerials: number[];
  coreCount: number;
  anchorCoreSerial: number;
  expanded: boolean;
};
type GraphCanvasNodeInfo = GraphCanvasActualNode | GraphCanvasAggregateNode;
type GraphCanvasEdgeInfo = {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  kind: 'actual' | 'aggregate';
  diffState: DraftEdgeDiffState;
};
type GraphLayoutComponent = {
  width: number;
  height: number;
  positions: Map<string, XYPosition>;
  anchorNode: GraphCanvasNodeInfo | null;
};
type GraphCanvasView = {
  canvasNodes: GraphCanvasNodeInfo[];
  canvasEdges: GraphCanvasEdgeInfo[];
  layoutEdges: GraphCanvasEdgeInfo[];
  hiddenActualEdgeKeys: Set<string>;
  visibleActualNodeKeys: Set<string>;
  isolatedTriggerSourceNodes: GraphNodeInfo[];
  isolatedCoreNodes: GraphNodeInfo[];
  aggregateNodes: GraphCanvasAggregateNode[];
};
type GraphDraftDiff = {
  changedNodeKeys: Set<string>;
  addedEdgeKeys: Set<string>;
  removedEdges: GraphSnapshotBundle['edges'];
};

type GraphViewerProps = {
  graphBundle: GraphSnapshotBundle;
  graphFileName: string;
  onDirtyStateChange?: (dirty: boolean) => void;
};

function AggregateOutlineNode(_: NodeProps<GraphFlowNodeData>): JSX.Element {
  return <div className="graph-aggregate-outline-node" />;
}

const graphNodeTypes = {
  aggregateOutline: AggregateOutlineNode,
};

function buildNodeLabel(node: GraphNodeInfo): JSX.Element {
  return (
    <div className="graph-node-label is-compact">
      <strong className="graph-node-title">{node.displayText}</strong>
    </div>
  );
}

function buildAggregateNodeLabel(aggregateNode: GraphCanvasAggregateNode): JSX.Element {
  return (
    <div className="graph-node-label">
      <strong className="graph-node-title">
        {aggregateNode.expanded
          ? `${aggregateNode.coreCount} grouped cores`
          : `+${aggregateNode.coreCount} grouped cores`}
      </strong>
      <span className="graph-node-meta">
        {aggregateNode.sourceSerials.length} triggerSources ·{' '}
        {aggregateNode.expanded ? '已展开，仅展开节点' : '点击展开节点'}
      </span>
    </div>
  );
}

function formatSearchTypeLabel(searchTypeFilter: GraphSearchTypeFilter): string {
  if (searchTypeFilter === 'all') {
    return '全部';
  }
  return searchTypeFilter;
}

function buildSearchResultLabel(node: GraphNodeInfo): string {
  return `${node.type} #${node.serial} · ${node.displayText}`;
}

function buildAggregateOutlineFlowNodes(
  aggregateNodes: GraphCanvasAggregateNode[],
  positionedNodes: GraphFlowNode[],
): GraphFlowNode[] {
  const positionedNodeByKey = new Map(
    positionedNodes.map((node) => [String(node.id), node] as const),
  );
  return aggregateNodes.flatMap((aggregateNode) => {
    if (!aggregateNode.expanded) {
      return [];
    }
    const memberNodes = [
      positionedNodeByKey.get(aggregateNode.nodeKey),
      ...aggregateNode.coreNodeKeys.map((nodeKey) => positionedNodeByKey.get(nodeKey)),
    ].filter((node): node is GraphFlowNode => node != null);
    if (memberNodes.length <= 1) {
      return [];
    }
    const minX =
      Math.min(...memberNodes.map((node) => node.position.x)) - AGGREGATE_OUTLINE_PADDING_X;
    const minY =
      Math.min(...memberNodes.map((node) => node.position.y)) - AGGREGATE_OUTLINE_PADDING_Y;
    const maxX =
      Math.max(...memberNodes.map((node) => node.position.x + GRAPH_NODE_WIDTH)) +
      AGGREGATE_OUTLINE_PADDING_X;
    const maxY =
      Math.max(...memberNodes.map((node) => node.position.y + GRAPH_NODE_HEIGHT)) +
      AGGREGATE_OUTLINE_PADDING_Y;
    return [
      {
        id: `aggregate-outline:${aggregateNode.nodeKey}`,
        type: 'aggregateOutline',
        position: {
          x: minX,
          y: minY,
        },
        data: {
          canvasNodeKey: aggregateNode.nodeKey,
        },
        draggable: false,
        selectable: false,
        focusable: false,
        style: {
          width: maxX - minX,
          height: maxY - minY,
          pointerEvents: 'none',
        },
      },
    ];
  });
}

function sameAggregateOutlineFlowNodes(left: GraphFlowNode[], right: GraphFlowNode[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((leftNode, index) => {
    const rightNode = right[index];
    if (!rightNode) {
      return false;
    }
    return (
      String(leftNode.id) === String(rightNode.id) &&
      leftNode.position.x === rightNode.position.x &&
      leftNode.position.y === rightNode.position.y &&
      Number(leftNode.style?.width ?? 0) === Number(rightNode.style?.width ?? 0) &&
      Number(leftNode.style?.height ?? 0) === Number(rightNode.style?.height ?? 0)
    );
  });
}

function matchesSearchType(
  node: GraphNodeInfo,
  searchTypeFilter: GraphSearchTypeFilter,
): boolean {
  return searchTypeFilter === 'all' || node.type === searchTypeFilter;
}

function matchesSearch(node: GraphNodeInfo, query: string): boolean {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return false;
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
  draftChangedNodeKeys: Set<string>,
): React.CSSProperties {
  const selected = selectedNodeKey === node.nodeKey;
  const matched = matchedNodeKeys.has(node.nodeKey);
  const dimmed = hasSearch && !matched;
  const selectedAsSource = selectedEditSourceNodeKeys.has(node.nodeKey);
  const selectedAsTarget = selectedEditTargetNodeKeys.has(node.nodeKey);
  const draftChanged = draftChangedNodeKeys.has(node.nodeKey);
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
  const borderColor =
    selected || selectedAsSource || selectedAsTarget
      ? selectionAccent
      : draftChanged
        ? '#8ef0b8'
        : 'rgba(255, 214, 191, 0.22)';
  return {
    minWidth: GRAPH_NODE_WIDTH,
    borderRadius: 16,
    border: `1px solid ${borderColor}`,
    background: draftChanged
      ? `linear-gradient(180deg, rgba(142, 240, 184, 0.16), rgba(142, 240, 184, 0.07)), ${baseBackground}`
      : baseBackground,
    boxShadow:
      selected || selectedAsSource || selectedAsTarget
        ? `0 0 0 1px ${selectionAccent} inset, 0 12px 24px rgba(6, 10, 18, 0.24)`
        : draftChanged
          ? '0 0 0 1px rgba(142, 240, 184, 0.4) inset'
        : 'none',
    color: '#fff4eb',
    opacity: dimmed ? 0.34 : 1,
  };
}

function buildAggregateNodeStyle(
  aggregateNode: GraphCanvasAggregateNode,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
): React.CSSProperties {
  const selected = selectedNodeKey === aggregateNode.nodeKey;
  const expanded = aggregateNode.expanded;
  const matched =
    aggregateNode.sourceNodeKeys.some((nodeKey) => matchedNodeKeys.has(nodeKey)) ||
    aggregateNode.coreNodeKeys.some((nodeKey) => matchedNodeKeys.has(nodeKey));
  const dimmed = hasSearch && !matched;
  const borderColor =
    selected || expanded ? '#8ad8ff' : 'rgba(140, 213, 255, 0.42)';
  return {
    minWidth: GRAPH_NODE_WIDTH,
    borderRadius: 16,
    border: `1px ${expanded ? 'solid' : 'dashed'} ${borderColor}`,
    background:
      'linear-gradient(180deg, rgba(98, 198, 255, 0.18), rgba(83, 148, 255, 0.08))',
    boxShadow: selected || expanded
      ? '0 0 0 1px rgba(140, 213, 255, 0.5) inset, 0 12px 24px rgba(6, 10, 18, 0.22)'
      : '0 0 0 1px rgba(140, 213, 255, 0.16) inset',
    color: '#effbff',
    opacity: dimmed ? 0.3 : 1,
  };
}

function buildEdgeStyle(
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  edge: Edge,
  diffState: DraftEdgeDiffState,
): Edge {
  const matched = matchedNodeKeys.has(edge.source) || matchedNodeKeys.has(edge.target);
  const edgeOpacity =
    hasSearch && !matched
      ? 0.2
      : diffState === 'removed'
        ? 0.72
        : diffState === 'added'
          ? 0.96
          : 0.88;
  return {
    ...edge,
    style: {
      stroke: diffState === 'base' ? '#ffc296' : '#8ef0b8',
      strokeWidth: diffState === 'base' ? 2.2 : 2.6,
      strokeDasharray: diffState === 'removed' ? '8 5' : undefined,
      opacity: edgeOpacity,
    },
  };
}

function buildAggregateEdgeStyle(
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  edge: Edge,
): Edge {
  const matched = matchedNodeKeys.has(edge.source) || matchedNodeKeys.has(edge.target);
  return {
    ...edge,
    style: {
      stroke: '#8ad8ff',
      strokeWidth: 2.1,
      strokeDasharray: '10 5',
      opacity: hasSearch && !matched ? 0.22 : 0.76,
    },
  };
}

function compareNodeType(left: GraphNodeTypeToken, right: GraphNodeTypeToken): number {
  if (left === right) {
    return 0;
  }
  return left === 'triggerSource' ? -1 : 1;
}

function toggleStringSelection(currentValues: string[], targetValue: string): string[] {
  if (currentValues.includes(targetValue)) {
    return currentValues.filter((value) => value !== targetValue);
  }
  return [...currentValues, targetValue].sort((left, right) => left.localeCompare(right));
}

function compareGraphNodeIdentity(left: GraphNodeInfo, right: GraphNodeInfo): number {
  if (left.serial !== right.serial) {
    return left.serial - right.serial;
  }
  const typeOrder = compareNodeType(left.type, right.type);
  if (typeOrder !== 0) {
    return typeOrder;
  }
  return left.nodeKey.localeCompare(right.nodeKey);
}

function buildAggregateNodeKey(signatureKey: string): string {
  return `aggregate:${signatureKey}`;
}

function resolveCanvasNodeLaneType(node: GraphCanvasNodeInfo): GraphNodeTypeToken {
  return node.kind === 'actual' ? node.graphNode.type : 'core';
}

function resolveCanvasNodeSortSerial(node: GraphCanvasNodeInfo): number {
  if (node.kind === 'actual') {
    return node.graphNode.serial;
  }
  return node.anchorCoreSerial;
}

function compareCanvasNodeIdentity(left: GraphCanvasNodeInfo, right: GraphCanvasNodeInfo): number {
  const serialOrder = resolveCanvasNodeSortSerial(left) - resolveCanvasNodeSortSerial(right);
  if (serialOrder !== 0) {
    return serialOrder;
  }
  const laneOrder = compareNodeType(resolveCanvasNodeLaneType(left), resolveCanvasNodeLaneType(right));
  if (laneOrder !== 0) {
    return laneOrder;
  }
  if (left.kind !== right.kind) {
    return left.kind === 'aggregate' ? -1 : 1;
  }
  return left.nodeKey.localeCompare(right.nodeKey);
}

function buildEdgeCountByNodeKey(graphBundle: GraphSnapshotBundle): Map<string, number> {
  const edgeCountByNodeKey = new Map<string, number>(
    graphBundle.nodes.map((node) => [node.nodeKey, 0]),
  );
  graphBundle.edges.forEach((edge) => {
    edgeCountByNodeKey.set(edge.sourceNodeKey, (edgeCountByNodeKey.get(edge.sourceNodeKey) ?? 0) + 1);
    edgeCountByNodeKey.set(edge.targetNodeKey, (edgeCountByNodeKey.get(edge.targetNodeKey) ?? 0) + 1);
  });
  return edgeCountByNodeKey;
}

function buildTargetSourcesByNodeKey(graphBundle: GraphSnapshotBundle): Map<string, GraphNodeInfo[]> {
  const nodeByKey = new Map(graphBundle.nodes.map((node) => [node.nodeKey, node] as const));
  const sourcesByTargetNodeKey = new Map<string, GraphNodeInfo[]>();
  graphBundle.edges.forEach((edge) => {
    const sourceNode = nodeByKey.get(edge.sourceNodeKey);
    if (sourceNode == null || sourceNode.type !== 'triggerSource') {
      return;
    }
    const currentSources = sourcesByTargetNodeKey.get(edge.targetNodeKey) ?? [];
    currentSources.push(sourceNode);
    sourcesByTargetNodeKey.set(edge.targetNodeKey, currentSources);
  });
  sourcesByTargetNodeKey.forEach((sources, targetNodeKey) => {
    sourcesByTargetNodeKey.set(
      targetNodeKey,
      [...sources].sort(compareGraphNodeIdentity),
    );
  });
  return sourcesByTargetNodeKey;
}

function buildGraphCanvasView(
  effectiveGraphBundle: GraphSnapshotBundle,
  forcedVisibleNodeKeys: Set<string>,
  expandedAggregateNodeKeys: Set<string>,
): GraphCanvasView {
  const originalEdgeCountByNodeKey = buildEdgeCountByNodeKey(effectiveGraphBundle);
  const targetSourcesByNodeKey = buildTargetSourcesByNodeKey(effectiveGraphBundle);
  const aggregateNodes: GraphCanvasAggregateNode[] = [];
  const sharedCoreGroupsBySignature = new Map<
    string,
    {
      sourceNodes: GraphNodeInfo[];
      coreNodes: GraphNodeInfo[];
    }
  >();
  effectiveGraphBundle.nodes
    .filter((node) => node.type === 'core')
    .sort(compareGraphNodeIdentity)
    .forEach((coreNode) => {
      const sourceNodes = targetSourcesByNodeKey.get(coreNode.nodeKey) ?? [];
      if (sourceNodes.length < SHARED_CORE_GROUP_MIN_SOURCE_COUNT) {
        return;
      }
      const signatureKey = sourceNodes.map((sourceNode) => sourceNode.nodeKey).join('|');
      const currentGroup = sharedCoreGroupsBySignature.get(signatureKey);
      if (currentGroup == null) {
        sharedCoreGroupsBySignature.set(signatureKey, {
          sourceNodes,
          coreNodes: [coreNode],
        });
        return;
      }
      currentGroup.coreNodes.push(coreNode);
    });

  const expandedAggregateCoreNodeKeys = new Set<string>();
  const hiddenActualEdgeKeys = new Set<string>();
  sharedCoreGroupsBySignature.forEach((group, signatureKey) => {
    if (group.coreNodes.length < SHARED_CORE_GROUP_MIN_CORE_COUNT) {
      return;
    }
    const sourceNodes = [...group.sourceNodes].sort(compareGraphNodeIdentity);
    const coreNodes = [...group.coreNodes].sort(compareGraphNodeIdentity);
    const aggregateNodeKey = buildAggregateNodeKey(signatureKey);
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      coreNodes.some((coreNode) => forcedVisibleNodeKeys.has(coreNode.nodeKey));
    const aggregateNode: GraphCanvasAggregateNode = {
      kind: 'aggregate',
      nodeKey: aggregateNodeKey,
      signatureKey,
      sourceNodeKeys: sourceNodes.map((node) => node.nodeKey),
      sourceSerials: sourceNodes.map((node) => node.serial),
      coreNodeKeys: coreNodes.map((node) => node.nodeKey),
      coreSerials: coreNodes.map((node) => node.serial),
      coreCount: coreNodes.length,
      anchorCoreSerial: coreNodes[0]?.serial ?? 0,
      expanded,
    };
    aggregateNodes.push(aggregateNode);
    aggregateNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
        hiddenActualEdgeKeys.add(`${sourceNodeKey}->${coreNodeKey}`);
      });
    });
    if (expanded) {
      aggregateNode.coreNodeKeys.forEach((nodeKey) => expandedAggregateCoreNodeKeys.add(nodeKey));
    }
  });

  const phaseVisibleActualEdges = effectiveGraphBundle.edges.filter(
    (edge) => !hiddenActualEdgeKeys.has(edge.edgeKey),
  );
  const phaseVisibleActualEdgeCountByNodeKey = new Map<string, number>();
  phaseVisibleActualEdges.forEach((edge) => {
    phaseVisibleActualEdgeCountByNodeKey.set(
      edge.sourceNodeKey,
      (phaseVisibleActualEdgeCountByNodeKey.get(edge.sourceNodeKey) ?? 0) + 1,
    );
    phaseVisibleActualEdgeCountByNodeKey.set(
      edge.targetNodeKey,
      (phaseVisibleActualEdgeCountByNodeKey.get(edge.targetNodeKey) ?? 0) + 1,
    );
  });
  const phaseVisibleActualNodeKeys = new Set<string>();
  effectiveGraphBundle.nodes.forEach((node) => {
    if (
      (phaseVisibleActualEdgeCountByNodeKey.get(node.nodeKey) ?? 0) > 0 ||
      forcedVisibleNodeKeys.has(node.nodeKey)
    ) {
      phaseVisibleActualNodeKeys.add(node.nodeKey);
    }
  });
  aggregateNodes.forEach((aggregateNode) => {
    aggregateNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      phaseVisibleActualNodeKeys.add(sourceNodeKey);
    });
    if (aggregateNode.expanded) {
      aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
        phaseVisibleActualNodeKeys.add(coreNodeKey);
      });
    }
  });

  const phaseCanvasNodes: GraphCanvasNodeInfo[] = [
    ...effectiveGraphBundle.nodes
      .filter((node) => phaseVisibleActualNodeKeys.has(node.nodeKey))
      .map((node) => ({
        kind: 'actual' as const,
        nodeKey: node.nodeKey,
        graphNode: node,
      })),
    ...aggregateNodes,
  ].sort(compareCanvasNodeIdentity);
  const phaseCanvasEdges: GraphCanvasEdgeInfo[] = [
    ...phaseVisibleActualEdges
      .filter(
        (edge) =>
          phaseVisibleActualNodeKeys.has(edge.sourceNodeKey) &&
          phaseVisibleActualNodeKeys.has(edge.targetNodeKey),
      )
      .map((edge) => ({
        edgeKey: edge.edgeKey,
        sourceNodeKey: edge.sourceNodeKey,
        targetNodeKey: edge.targetNodeKey,
        kind: 'actual' as const,
        diffState: 'base' as DraftEdgeDiffState,
      })),
  ];
  aggregateNodes.forEach((aggregateNode) => {
    aggregateNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      phaseCanvasEdges.push({
        edgeKey: `aggregate-edge:${sourceNodeKey}:${aggregateNode.nodeKey}`,
        sourceNodeKey,
        targetNodeKey: aggregateNode.nodeKey,
        kind: 'aggregate',
        diffState: 'base',
      });
    });
  });
  const visibleCanvasNodeKeys = new Set<string>();
  phaseCanvasEdges.forEach((edge) => {
    visibleCanvasNodeKeys.add(edge.sourceNodeKey);
    visibleCanvasNodeKeys.add(edge.targetNodeKey);
  });
  aggregateNodes.forEach((aggregateNode) => {
    if (!aggregateNode.expanded) {
      return;
    }
    visibleCanvasNodeKeys.add(aggregateNode.nodeKey);
    aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
      visibleCanvasNodeKeys.add(coreNodeKey);
    });
  });

  const visibleActualNodeKeys = new Set<string>();
  const canvasNodes: GraphCanvasNodeInfo[] = [
    ...effectiveGraphBundle.nodes
      .filter(
        (node) => visibleCanvasNodeKeys.has(node.nodeKey) || forcedVisibleNodeKeys.has(node.nodeKey),
      )
      .map((node) => ({
        kind: 'actual' as const,
        nodeKey: node.nodeKey,
        graphNode: node,
      })),
    ...aggregateNodes.filter(
      (aggregateNode) =>
        visibleCanvasNodeKeys.has(aggregateNode.nodeKey) ||
        forcedVisibleNodeKeys.has(aggregateNode.nodeKey),
    ),
  ].sort(compareCanvasNodeIdentity);
  canvasNodes.forEach((node) => {
    if (node.kind === 'actual') {
      visibleActualNodeKeys.add(node.nodeKey);
    }
  });

  const canvasNodeKeySet = new Set(canvasNodes.map((node) => node.nodeKey));
  const canvasEdges: GraphCanvasEdgeInfo[] = phaseCanvasEdges.filter(
    (edge) =>
      canvasNodeKeySet.has(edge.sourceNodeKey) && canvasNodeKeySet.has(edge.targetNodeKey),
  );
  const layoutEdges = [...canvasEdges];
  aggregateNodes.forEach((aggregateNode) => {
    if (!aggregateNode.expanded || !canvasNodeKeySet.has(aggregateNode.nodeKey)) {
      return;
    }
    aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
      if (!canvasNodeKeySet.has(coreNodeKey)) {
        return;
      }
      layoutEdges.push({
        edgeKey: `aggregate-layout:${aggregateNode.nodeKey}:${coreNodeKey}`,
        sourceNodeKey: aggregateNode.nodeKey,
        targetNodeKey: coreNodeKey,
        kind: 'aggregate',
        diffState: 'base',
      });
    });
  });

  return {
    canvasNodes,
    canvasEdges,
    layoutEdges,
    hiddenActualEdgeKeys,
    visibleActualNodeKeys,
    isolatedTriggerSourceNodes: effectiveGraphBundle.nodes
      .filter(
        (node) =>
          node.type === 'triggerSource' &&
          (originalEdgeCountByNodeKey.get(node.nodeKey) ?? 0) === 0,
      )
      .sort(compareGraphNodeIdentity),
    isolatedCoreNodes: effectiveGraphBundle.nodes
      .filter(
        (node) =>
          node.type === 'core' &&
          (originalEdgeCountByNodeKey.get(node.nodeKey) ?? 0) === 0,
      )
      .sort(compareGraphNodeIdentity),
    aggregateNodes: canvasNodes.filter(
      (node): node is GraphCanvasAggregateNode => node.kind === 'aggregate',
    ),
  };
}

function buildCanvasAdjacency(
  nodes: GraphCanvasNodeInfo[],
  edges: GraphCanvasEdgeInfo[],
): Map<string, Set<string>> {
  const adjacency = new Map<string, Set<string>>(
    nodes.map((node) => [node.nodeKey, new Set<string>()]),
  );
  edges.forEach((edge) => {
    const sourceAdjacency = adjacency.get(edge.sourceNodeKey);
    const targetAdjacency = adjacency.get(edge.targetNodeKey);
    if (sourceAdjacency == null || targetAdjacency == null) {
      return;
    }
    sourceAdjacency.add(edge.targetNodeKey);
    targetAdjacency.add(edge.sourceNodeKey);
  });
  return adjacency;
}

/**
 * 按无向连通关系拆分图，用于把互不相连的局部网络分成独立布局块。
 */
function collectConnectedComponents(
  nodes: GraphCanvasNodeInfo[],
  edges: GraphCanvasEdgeInfo[],
): GraphCanvasNodeInfo[][] {
  const adjacency = buildCanvasAdjacency(nodes, edges);
  const nodeByKey = new Map(nodes.map((node) => [node.nodeKey, node] as const));
  const visitedNodeKeys = new Set<string>();
  const components: GraphCanvasNodeInfo[][] = [];
  const orderedSeedNodes = [...nodes].sort(compareCanvasNodeIdentity);

  orderedSeedNodes.forEach((seedNode) => {
    if (visitedNodeKeys.has(seedNode.nodeKey)) {
      return;
    }
    const queue = [seedNode.nodeKey];
    const componentNodes: GraphCanvasNodeInfo[] = [];
    visitedNodeKeys.add(seedNode.nodeKey);
    while (queue.length > 0) {
      const currentNodeKey = queue.shift();
      if (currentNodeKey == null) {
        continue;
      }
      const currentNode = nodeByKey.get(currentNodeKey);
      if (currentNode != null) {
        componentNodes.push(currentNode);
      }
      const neighborNodeKeys = Array.from(adjacency.get(currentNodeKey) ?? []).sort();
      neighborNodeKeys.forEach((neighborNodeKey) => {
        if (visitedNodeKeys.has(neighborNodeKey)) {
          return;
        }
        visitedNodeKeys.add(neighborNodeKey);
        queue.push(neighborNodeKey);
      });
    }
    componentNodes.sort(compareCanvasNodeIdentity);
    components.push(componentNodes);
  });

  return components.sort((left, right) => {
    const leftAnchor = left[0];
    const rightAnchor = right[0];
    if (leftAnchor == null || rightAnchor == null) {
      return left.length - right.length;
    }
    const anchorOrder = compareCanvasNodeIdentity(leftAnchor, rightAnchor);
    if (anchorOrder !== 0) {
      return anchorOrder;
    }
    return left.length - right.length;
  });
}

/**
 * 通过多列分组提升横向利用率，避免展开后整块拓扑被拉成单列长条。
 */
function resolveLaneRowCount(nodeCount: number): number {
  if (nodeCount <= 0) {
    return 0;
  }
  return Math.min(
    nodeCount,
    Math.max(
      COMPONENT_LANE_MIN_ROW_COUNT,
      Math.min(
        COMPONENT_LANE_MAX_ROW_COUNT,
        Math.ceil(Math.sqrt(nodeCount * 1.35)),
      ),
    ),
  );
}

/**
 * 为布局生成稳定但不完全规则的偏移，减少边在长段上的完全重叠。
 */
function hashLayoutKey(layoutKey: string): number {
  let hash = 2166136261;
  for (let index = 0; index < layoutKey.length; index++) {
    hash ^= layoutKey.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function resolveLaneJitter(nodeKey: string, columnIndex: number, rowIndex: number): XYPosition {
  const hash = hashLayoutKey(`${nodeKey}:${columnIndex}:${rowIndex}`);
  const driftX = (hash % (LANE_JITTER_X * 2 + 1)) - LANE_JITTER_X;
  const driftY = (((hash >>> 8) % (LANE_JITTER_Y * 2 + 1)) - LANE_JITTER_Y);
  const columnStaggerY = columnIndex % 2 === 0 ? 0 : LANE_COLUMN_STAGGER_Y;
  const rowBiasY = rowIndex % 2 === 0 ? -4 : 6;
  return {
    x: driftX,
    y: driftY + columnStaggerY + rowBiasY,
  };
}

function normalizeLayoutPositions(
  rawPositions: Map<string, XYPosition>,
): Pick<GraphLayoutComponent, 'positions' | 'width' | 'height'> {
  if (rawPositions.size === 0) {
    return {
      width: 0,
      height: 0,
      positions: new Map<string, XYPosition>(),
    };
  }
  const positionValues = Array.from(rawPositions.values());
  const minX = Math.min(...positionValues.map((position) => position.x));
  const minY = Math.min(...positionValues.map((position) => position.y));
  const normalizedPositions = new Map<string, XYPosition>();
  let maxX = 0;
  let maxY = 0;
  rawPositions.forEach((position, nodeKey) => {
    const normalizedPosition = {
      x: position.x - minX,
      y: position.y - minY,
    };
    normalizedPositions.set(nodeKey, normalizedPosition);
    maxX = Math.max(maxX, normalizedPosition.x);
    maxY = Math.max(maxY, normalizedPosition.y);
  });
  return {
    width: GRAPH_NODE_WIDTH + maxX,
    height: GRAPH_NODE_HEIGHT + maxY,
    positions: normalizedPositions,
  };
}

function resolveComponentOffset(nodeKey: string, componentIndex: number): XYPosition {
  const hash = hashLayoutKey(`${nodeKey}:component:${componentIndex}`);
  return {
    x: (hash % 3) * COMPONENT_STAGGER_X,
    y: ((hash >>> 7) % 3) * COMPONENT_STAGGER_Y,
  };
}

function buildLaneLayout(
  nodes: GraphCanvasNodeInfo[],
  allowJitter = true,
): GraphLayoutComponent {
  const rawPositions = new Map<string, XYPosition>();
  if (nodes.length === 0) {
    return {
      width: 0,
      height: 0,
      positions: rawPositions,
      anchorNode: null,
    };
  }
  const rowCount = resolveLaneRowCount(nodes.length);
  const columnCount = Math.ceil(nodes.length / rowCount);
  const columnSpanX = GRAPH_NODE_WIDTH + COMPONENT_LANE_COLUMN_GAP_X;
  nodes.forEach((node, index) => {
    const columnIndex = Math.floor(index / rowCount);
    const rowIndex = index % rowCount;
    const laneJitter = allowJitter
      ? resolveLaneJitter(node.nodeKey, columnIndex, rowIndex)
      : { x: 0, y: 0 };
    rawPositions.set(node.nodeKey, {
      x: columnIndex * columnSpanX + laneJitter.x,
      y: rowIndex * COMPONENT_NODE_GAP_Y + laneJitter.y,
    });
  });
  const normalizedLayout = normalizeLayoutPositions(rawPositions);
  return {
    width: Math.max(
      GRAPH_NODE_WIDTH + Math.max(0, columnCount - 1) * columnSpanX,
      normalizedLayout.width,
    ),
    height: Math.max(
      GRAPH_NODE_HEIGHT + Math.max(0, rowCount - 1) * COMPONENT_NODE_GAP_Y,
      normalizedLayout.height,
    ),
    positions: normalizedLayout.positions,
    anchorNode: nodes[0] ?? null,
  };
}

function resolveNodeAnchorStats(connectedOrders: number[]): {
  averageOrder: number;
  minimumOrder: number;
} {
  if (connectedOrders.length === 0) {
    return {
      averageOrder: Number.MAX_SAFE_INTEGER,
      minimumOrder: Number.MAX_SAFE_INTEGER,
    };
  }
  return {
    averageOrder:
      connectedOrders.reduce((totalOrder, currentOrder) => totalOrder + currentOrder, 0) /
      connectedOrders.length,
    minimumOrder: Math.min(...connectedOrders),
  };
}

function buildComponentLayout(
  componentNodes: GraphCanvasNodeInfo[],
  componentEdges: GraphCanvasEdgeInfo[],
): GraphLayoutComponent {
  const hasExpandedAggregateNode = componentNodes.some(
    (node) => node.kind === 'aggregate' && node.expanded,
  );
  const triggerSourceNodes = componentNodes
    .filter((node) => resolveCanvasNodeLaneType(node) === 'triggerSource')
    .sort(compareCanvasNodeIdentity);
  const sourceOrderByNodeKey = new Map(
    triggerSourceNodes.map((node, index) => [node.nodeKey, index] as const),
  );
  const connectedSourceOrdersByNodeKey = new Map<string, number[]>();
  componentEdges.forEach((edge) => {
    const directSourceOrder = sourceOrderByNodeKey.get(edge.sourceNodeKey);
    const inheritedSourceOrders =
      directSourceOrder == null ? connectedSourceOrdersByNodeKey.get(edge.sourceNodeKey) ?? [] : [];
    const nextSourceOrders =
      directSourceOrder == null ? inheritedSourceOrders : [directSourceOrder, ...inheritedSourceOrders];
    if (nextSourceOrders.length === 0) {
      return;
    }
    const currentOrders = connectedSourceOrdersByNodeKey.get(edge.targetNodeKey) ?? [];
    connectedSourceOrdersByNodeKey.set(edge.targetNodeKey, [...currentOrders, ...nextSourceOrders]);
  });
  const coreNodes = componentNodes
    .filter((node) => resolveCanvasNodeLaneType(node) === 'core')
    .sort((left, right) => {
      const leftAnchorStats = resolveNodeAnchorStats(
        connectedSourceOrdersByNodeKey.get(left.nodeKey) ?? [],
      );
      const rightAnchorStats = resolveNodeAnchorStats(
        connectedSourceOrdersByNodeKey.get(right.nodeKey) ?? [],
      );
      if (leftAnchorStats.averageOrder !== rightAnchorStats.averageOrder) {
        return leftAnchorStats.averageOrder - rightAnchorStats.averageOrder;
      }
      if (leftAnchorStats.minimumOrder !== rightAnchorStats.minimumOrder) {
        return leftAnchorStats.minimumOrder - rightAnchorStats.minimumOrder;
      }
      return compareCanvasNodeIdentity(left, right);
    });
  const triggerSourceLaneLayout = buildLaneLayout(triggerSourceNodes);
  const coreLaneLayout = buildLaneLayout(coreNodes, !hasExpandedAggregateNode);
  const laneHeight = Math.max(triggerSourceLaneLayout.height, coreLaneLayout.height, GRAPH_NODE_HEIGHT);
  const triggerSourceOffsetY =
    triggerSourceLaneLayout.height === 0 ? 0 : (laneHeight - triggerSourceLaneLayout.height) / 2;
  const coreOffsetY = coreLaneLayout.height === 0 ? 0 : (laneHeight - coreLaneLayout.height) / 2;
  const hasDualLayer = triggerSourceNodes.length > 0 && coreNodes.length > 0;
  const coreX = hasDualLayer ? triggerSourceLaneLayout.width + COMPONENT_LAYER_GAP_X : 0;
  const positions = new Map<string, XYPosition>();

  triggerSourceLaneLayout.positions.forEach((position, nodeKey) => {
    positions.set(nodeKey, {
      x: position.x,
      y: triggerSourceOffsetY + position.y,
    });
  });
  coreLaneLayout.positions.forEach((position, nodeKey) => {
    positions.set(nodeKey, {
      x: coreX + position.x,
      y: coreOffsetY + position.y,
    });
  });

  return {
    width: hasDualLayer
      ? triggerSourceLaneLayout.width + COMPONENT_LAYER_GAP_X + coreLaneLayout.width
      : Math.max(triggerSourceLaneLayout.width, coreLaneLayout.width, GRAPH_NODE_WIDTH),
    height: laneHeight,
    positions,
    anchorNode: componentNodes[0] ?? null,
  };
}

/**
 * 将多个连通分量按块流式铺排到画布上，避免整张图被挤成全局两列。
 */
function buildAutoLayoutPositions(
  canvasNodes: GraphCanvasNodeInfo[],
  layoutEdges: GraphCanvasEdgeInfo[],
): Map<string, XYPosition> {
  const positionByNodeKey = new Map<string, XYPosition>();
  let currentX = AUTO_LAYOUT_START_X;
  let currentY = AUTO_LAYOUT_START_Y;
  let currentRowHeight = 0;

  collectConnectedComponents(canvasNodes, layoutEdges)
    .map((componentNodes) => {
      const componentNodeKeys = new Set(componentNodes.map((node) => node.nodeKey));
      const componentEdges = layoutEdges.filter(
        (edge) =>
          componentNodeKeys.has(edge.sourceNodeKey) && componentNodeKeys.has(edge.targetNodeKey),
      );
      return buildComponentLayout(componentNodes, componentEdges);
    })
    .sort((left, right) => {
      if (left.anchorNode == null || right.anchorNode == null) {
        return left.width - right.width;
      }
      return compareCanvasNodeIdentity(left.anchorNode, right.anchorNode);
    })
    .forEach((component, componentIndex) => {
      const needsWrap =
        currentX > AUTO_LAYOUT_START_X &&
        currentX + component.width > AUTO_LAYOUT_MAX_ROW_WIDTH;
      if (needsWrap) {
        currentX = AUTO_LAYOUT_START_X;
        currentY += currentRowHeight + COMPONENT_BLOCK_GAP_Y;
        currentRowHeight = 0;
      }
      const componentOffset = resolveComponentOffset(
        component.anchorNode?.nodeKey ?? `component:${componentIndex}`,
        componentIndex,
      );
      component.positions.forEach((position, nodeKey) => {
        positionByNodeKey.set(nodeKey, {
          x: currentX + componentOffset.x + position.x,
          y: currentY + componentOffset.y + position.y,
        });
      });
      currentX += component.width + componentOffset.x + COMPONENT_BLOCK_GAP_X;
      currentRowHeight = Math.max(currentRowHeight, component.height + componentOffset.y);
    });

  return positionByNodeKey;
}

function buildGraphFlowNodes(
  canvasNodes: GraphCanvasNodeInfo[],
  positionByNodeKey: Map<string, XYPosition>,
  editMode: GraphEditMode,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  selectedEditSourceNodeKeys: Set<string>,
  selectedEditTargetNodeKeys: Set<string>,
  draftChangedNodeKeys: Set<string>,
): GraphFlowNode[] {
  return canvasNodes.map((canvasNode) => {
    const nodeKey = canvasNode.nodeKey;
    return {
      id: nodeKey,
      position:
        positionByNodeKey.get(nodeKey) ?? {
          x: AUTO_LAYOUT_START_X,
          y: AUTO_LAYOUT_START_Y,
        },
      data: {
        label:
          canvasNode.kind === 'aggregate'
            ? buildAggregateNodeLabel(canvasNode)
            : buildNodeLabel(canvasNode.graphNode),
        canvasNodeKey: nodeKey,
      },
      draggable: true,
      selectable: canvasNode.kind === 'actual' && editMode !== 'view',
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      style:
        canvasNode.kind === 'aggregate'
          ? buildAggregateNodeStyle(
              canvasNode,
              selectedNodeKey,
              hasSearch,
              matchedNodeKeys,
            )
          : buildNodeStyle(
              canvasNode.graphNode,
              selectedNodeKey,
              hasSearch,
              matchedNodeKeys,
              selectedEditSourceNodeKeys,
              selectedEditTargetNodeKeys,
              draftChangedNodeKeys,
            ),
    };
  });
}

function buildEdges(
  baseGraphBundle: GraphSnapshotBundle,
  effectiveGraphBundle: GraphSnapshotBundle,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  draftDiff: GraphDraftDiff,
  graphCanvasView: GraphCanvasView,
): Edge[] {
  const edges: Edge[] = graphCanvasView.canvasEdges.map((edge) => {
    const baseEdge: Edge = {
      id: edge.edgeKey,
      source: edge.sourceNodeKey,
      target: edge.targetNodeKey,
      animated: false,
    };
    if (edge.kind === 'aggregate') {
      return buildAggregateEdgeStyle(hasSearch, matchedNodeKeys, baseEdge);
    }
    return buildEdgeStyle(
      hasSearch,
      matchedNodeKeys,
      baseEdge,
      draftDiff.addedEdgeKeys.has(edge.edgeKey) ? 'added' : 'base',
    );
  });
  draftDiff.removedEdges
    .filter(
      (edge) =>
        !graphCanvasView.hiddenActualEdgeKeys.has(edge.edgeKey) &&
        graphCanvasView.visibleActualNodeKeys.has(edge.sourceNodeKey) &&
        graphCanvasView.visibleActualNodeKeys.has(edge.targetNodeKey),
    )
    .forEach((edge) => {
      edges.push(
        buildEdgeStyle(
          hasSearch,
          matchedNodeKeys,
          {
            id: `removed:${edge.edgeKey}`,
            source: edge.sourceNodeKey,
            target: edge.targetNodeKey,
            animated: false,
          },
          'removed',
        ),
      );
    });
  return edges;
}

function buildDraftFileName(graphFileName: string, snapshotId: string): string {
  const normalizedBaseName = graphFileName.trim()
    ? graphFileName.replace(/\.json\.gz$/i, '')
    : snapshotId;
  return `draft-${normalizedBaseName}.json`;
}

function sameGraphDraft(left: GraphDraft, right: GraphDraft): boolean {
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

function sameNumberArray(left: number[], right: number[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((value, index) => value === right[index]);
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

function buildDraftDiff(
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
  const [searchDraftText, setSearchDraftText] = useState('');
  const [appliedSearchText, setAppliedSearchText] = useState('');
  const [searchDraftTypeFilter, setSearchDraftTypeFilter] =
    useState<GraphSearchTypeFilter>('all');
  const [appliedSearchTypeFilter, setAppliedSearchTypeFilter] =
    useState<GraphSearchTypeFilter>('all');
  const [selectedNodeKey, setSelectedNodeKey] = useState<string>('');
  const [activeSidebarPanel, setActiveSidebarPanel] = useState<GraphSidebarPanel>('details');
  const [editMode, setEditMode] = useState<GraphEditMode>('view');
  const [selectedEditSourceSerials, setSelectedEditSourceSerials] = useState<number[]>([]);
  const [selectedEditTargetSerials, setSelectedEditTargetSerials] = useState<number[]>([]);
  const [undoableGraphDraft, setUndoableGraphDraft] = useState<GraphDraft | null>(null);
  const [expandedAggregateNodeKeys, setExpandedAggregateNodeKeys] = useState<string[]>([]);
  const [pinnedIsolatedNodeKeys, setPinnedIsolatedNodeKeys] = useState<string[]>([]);
  const [pendingAggregateFocusNodeKey, setPendingAggregateFocusNodeKey] = useState('');
  const [pendingFocusNodeKey, setPendingFocusNodeKey] = useState('');
  const [pendingStructureLayoutReset, setPendingStructureLayoutReset] = useState(false);
  const [pendingStructureViewportFit, setPendingStructureViewportFit] = useState(false);
  const [reactFlowInstance, setReactFlowInstance] = useState<ReactFlowInstance | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [aggregateOutlineNodes, setAggregateOutlineNodes] = useState<GraphFlowNode[]>([]);
  const [aggregateOutlineSuspended, setAggregateOutlineSuspended] = useState(false);
  const suspendCanvasSelectionSyncRef = useRef(false);
  const resumeCanvasSelectionSyncFrameRef = useRef<number | null>(null);
  const selectionPreviewActiveRef = useRef(false);
  const pendingSelectionNodeKeysRef = useRef<string[]>([]);
  const finalizeSelectionFrameRef = useRef<number | null>(null);
  const aggregateOutlineSuspendDepthRef = useRef(0);

  const draftFileName = useMemo(
    () => buildDraftFileName(graphFileName, graphBundle.snapshotId),
    [graphBundle.snapshotId, graphFileName],
  );
  const effectiveGraphBundle = useMemo(
    () => applyDraftToGraph(baseGraphBundle, graphDraft),
    [baseGraphBundle, graphDraft],
  );
  const draftDiff = useMemo(
    () => buildDraftDiff(baseGraphBundle, effectiveGraphBundle, graphDraft),
    [baseGraphBundle, effectiveGraphBundle, graphDraft],
  );
  const nodeByKey = useMemo(
    () => new Map(effectiveGraphBundle.nodes.map((node) => [node.nodeKey, node])),
    [effectiveGraphBundle.nodes],
  );
  const edgeCountByNodeKey = useMemo(
    () => buildEdgeCountByNodeKey(effectiveGraphBundle),
    [effectiveGraphBundle],
  );
  const hasSearch = appliedSearchText.trim().length > 0;
  const matchedNodes = useMemo(
    () =>
      hasSearch
        ? effectiveGraphBundle.nodes.filter(
            (node) =>
              matchesSearchType(node, appliedSearchTypeFilter) &&
              matchesSearch(node, appliedSearchText),
          )
        : [],
    [appliedSearchText, appliedSearchTypeFilter, effectiveGraphBundle.nodes, hasSearch],
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
  const selectedNodeHasNonPinnedVisibilityReason = useMemo(() => {
    if (!selectedNodeKey || !nodeByKey.has(selectedNodeKey)) {
      return false;
    }
    return (
      (edgeCountByNodeKey.get(selectedNodeKey) ?? 0) > 0 ||
      matchedNodeKeys.has(selectedNodeKey) ||
      draftDiff.changedNodeKeys.has(selectedNodeKey)
    );
  }, [
    draftDiff.changedNodeKeys,
    edgeCountByNodeKey,
    matchedNodeKeys,
    nodeByKey,
    selectedNodeKey,
  ]);
  const forcedVisibleNodeKeys = useMemo(() => {
    const nextKeys = new Set<string>();
    if (selectedNodeKey) {
      nextKeys.add(selectedNodeKey);
    }
    pinnedIsolatedNodeKeys.forEach((nodeKey) => nextKeys.add(nodeKey));
    matchedNodeKeys.forEach((nodeKey) => nextKeys.add(nodeKey));
    draftDiff.changedNodeKeys.forEach((nodeKey) => nextKeys.add(nodeKey));
    return nextKeys;
  }, [
    draftDiff.changedNodeKeys,
    matchedNodeKeys,
    nodeByKey,
    pinnedIsolatedNodeKeys,
    selectedNodeKey,
  ]);
  const expandedAggregateNodeKeySet = useMemo(
    () => new Set(expandedAggregateNodeKeys),
    [expandedAggregateNodeKeys],
  );
  const graphCanvasView = useMemo(
    () =>
      buildGraphCanvasView(
        effectiveGraphBundle,
        forcedVisibleNodeKeys,
        expandedAggregateNodeKeySet,
      ),
    [
      effectiveGraphBundle,
      expandedAggregateNodeKeySet,
      forcedVisibleNodeKeys,
    ],
  );
  const autoLayoutPositions = useMemo(
    () => buildAutoLayoutPositions(graphCanvasView.canvasNodes, graphCanvasView.layoutEdges),
    [graphCanvasView.canvasNodes, graphCanvasView.layoutEdges],
  );
  const pinnedIsolatedNodeCount = useMemo(
    () =>
      pinnedIsolatedNodeKeys.filter((nodeKey) => (edgeCountByNodeKey.get(nodeKey) ?? 0) === 0).length,
    [edgeCountByNodeKey, pinnedIsolatedNodeKeys],
  );
  const hasCanvasNodes = graphCanvasView.canvasNodes.length > 0;
  const displayNodes = useMemo(
    () => (aggregateOutlineSuspended ? nodes : [...aggregateOutlineNodes, ...nodes]),
    [aggregateOutlineNodes, aggregateOutlineSuspended, nodes],
  );
  const hasPendingSearchChanges =
    searchDraftText !== appliedSearchText ||
    searchDraftTypeFilter !== appliedSearchTypeFilter;
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
    setActiveSidebarPanel('details');
    setEditMode('view');
    setSelectedEditSourceSerials([]);
    setSelectedEditTargetSerials([]);
    setUndoableGraphDraft(null);
    setExpandedAggregateNodeKeys([]);
    setPinnedIsolatedNodeKeys([]);
    setPendingAggregateFocusNodeKey('');
    setPendingFocusNodeKey('');
    setAggregateOutlineNodes([]);
    aggregateOutlineSuspendDepthRef.current = 0;
    setAggregateOutlineSuspended(false);
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
    const initialNodeKey =
      graphCanvasView.canvasNodes.find((node) => node.kind === 'actual')?.nodeKey ??
      effectiveGraphBundle.nodes[0]?.nodeKey ??
      '';
    setSelectedNodeKey(initialNodeKey);
    setNodes(
      buildGraphFlowNodes(
        graphCanvasView.canvasNodes,
        autoLayoutPositions,
        editMode,
        initialNodeKey,
        false,
        new Set<string>(),
        new Set<string>(),
        new Set<string>(),
        new Set<string>(),
      ),
    );
    setEdges(
      buildEdges(
        baseGraphBundle,
        effectiveGraphBundle,
        false,
        new Set<string>(),
        draftDiff,
        graphCanvasView,
      ),
    );
  }, [draftLoading, graphBundle.snapshotId, setEdges, setNodes]);

  useEffect(() => {
    if (draftLoading || aggregateOutlineSuspended) {
      return;
    }
    const nextOutlineNodes = buildAggregateOutlineFlowNodes(graphCanvasView.aggregateNodes, nodes);
    setAggregateOutlineNodes((currentNodes) =>
      sameAggregateOutlineFlowNodes(currentNodes, nextOutlineNodes) ? currentNodes : nextOutlineNodes,
    );
  }, [aggregateOutlineSuspended, draftLoading, graphCanvasView.aggregateNodes, nodes]);

  useEffect(() => {
    const shouldResetPositions = pendingStructureLayoutReset;
    setNodes((currentNodes) => {
      const existingPositionByNodeKey = new Map(
        shouldResetPositions ? [] : currentNodes.map((node) => [node.id, node.position] as const),
      );
      const existingSelectedByNodeKey = new Map(
        currentNodes.map((node) => [node.id, Boolean(node.selected)] as const),
      );
      return buildGraphFlowNodes(
        graphCanvasView.canvasNodes,
        autoLayoutPositions,
        editMode,
        selectedNodeKey,
        hasSearch,
        matchedNodeKeys,
        selectedEditSourceNodeKeys,
        selectedEditTargetNodeKeys,
        draftDiff.changedNodeKeys,
      ).map((node) => ({
        ...node,
        position: existingPositionByNodeKey.get(node.id) ?? node.position,
        selected: existingSelectedByNodeKey.get(node.id) ?? false,
      }));
    });
    setEdges(
      buildEdges(
        baseGraphBundle,
        effectiveGraphBundle,
        hasSearch,
        matchedNodeKeys,
        draftDiff,
        graphCanvasView,
      ),
    );
    if (shouldResetPositions) {
      setPendingStructureLayoutReset(false);
    }
  }, [
    autoLayoutPositions,
    baseGraphBundle,
    draftDiff,
    editMode,
    effectiveGraphBundle,
    graphCanvasView,
    hasSearch,
    matchedNodeKeys,
    pendingStructureLayoutReset,
    selectedNodeKey,
    selectedEditSourceNodeKeys,
    selectedEditTargetNodeKeys,
    setEdges,
    setNodes,
  ]);

  useEffect(() => {
    if (draftLoading || !reactFlowInstance || graphCanvasView.canvasNodes.length === 0) {
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
    graphCanvasView.canvasNodes.length,
    graphBundle.snapshotId,
    reactFlowInstance,
  ]);

  useEffect(() => {
    if (
      draftLoading ||
      pendingStructureLayoutReset ||
      !pendingStructureViewportFit ||
      !reactFlowInstance ||
      nodes.length === 0
    ) {
      return;
    }
    const frameId = window.requestAnimationFrame(() => {
      reactFlowInstance.fitView({ padding: 0.18, duration: 260 });
      setPendingStructureViewportFit(false);
    });
    return () => {
      window.cancelAnimationFrame(frameId);
    };
  }, [
    draftLoading,
    nodes,
    pendingStructureLayoutReset,
    pendingStructureViewportFit,
    reactFlowInstance,
  ]);

  useEffect(() => {
    if (
      draftLoading ||
      pendingStructureLayoutReset ||
      !pendingAggregateFocusNodeKey ||
      !reactFlowInstance ||
      nodes.length === 0
    ) {
      return;
    }
    const expandedAggregateNode = graphCanvasView.aggregateNodes.find(
      (aggregateNode) =>
        aggregateNode.nodeKey === pendingAggregateFocusNodeKey && aggregateNode.expanded,
    );
    if (!expandedAggregateNode) {
      setPendingAggregateFocusNodeKey('');
      return;
    }
    const targetNodeIds = [
      expandedAggregateNode.nodeKey,
      ...expandedAggregateNode.coreNodeKeys,
    ].filter((nodeId) => nodes.some((node) => node.id === nodeId));
    if (targetNodeIds.length === 0) {
      setPendingAggregateFocusNodeKey('');
      return;
    }
    const frameId = window.requestAnimationFrame(() => {
      reactFlowInstance.fitView({
        nodes: targetNodeIds.map((nodeId) => ({ id: nodeId })),
        padding: 0.28,
        minZoom: 0.72,
        maxZoom: 1.22,
        duration: 260,
      });
      setPendingAggregateFocusNodeKey('');
    });
    return () => {
      window.cancelAnimationFrame(frameId);
    };
  }, [
    draftLoading,
    graphCanvasView.aggregateNodes,
    nodes,
    pendingAggregateFocusNodeKey,
    pendingStructureLayoutReset,
    reactFlowInstance,
  ]);

  useEffect(() => {
    if (!pendingFocusNodeKey || !reactFlowInstance) {
      return;
    }
    const currentNode = nodes.find((node) => node.id === pendingFocusNodeKey);
    if (!currentNode) {
      return;
    }
    reactFlowInstance.setCenter(
      currentNode.position.x + NODE_CENTER_OFFSET_X,
      currentNode.position.y + NODE_CENTER_OFFSET_Y,
      { zoom: 1.12, duration: 260 },
    );
    setPendingFocusNodeKey('');
  }, [nodes, pendingFocusNodeKey, reactFlowInstance]);

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
    return () => {
      if (resumeCanvasSelectionSyncFrameRef.current != null) {
        window.cancelAnimationFrame(resumeCanvasSelectionSyncFrameRef.current);
      }
      if (finalizeSelectionFrameRef.current != null) {
        window.cancelAnimationFrame(finalizeSelectionFrameRef.current);
      }
      aggregateOutlineSuspendDepthRef.current = 0;
    };
  }, []);

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
    setPendingFocusNodeKey(nodeKey);
  }

  function requestStructureLayoutRefresh(options?: {
    focusAggregateNodeKey?: string;
    fitViewport?: boolean;
  }) {
    setPendingFocusNodeKey('');
    setPendingAggregateFocusNodeKey(options?.focusAggregateNodeKey ?? '');
    setPendingStructureLayoutReset(true);
    setPendingStructureViewportFit(options?.fitViewport ?? true);
  }

  function clearSelectionPreviewState() {
    selectionPreviewActiveRef.current = false;
    pendingSelectionNodeKeysRef.current = [];
    if (finalizeSelectionFrameRef.current != null) {
      window.cancelAnimationFrame(finalizeSelectionFrameRef.current);
      finalizeSelectionFrameRef.current = null;
    }
  }

  /**
   * 包围框只在静止态展示，拖拽热路径里先临时移除，结束后再按最终位置恢复。
   */
  function suspendAggregateOutlineRendering() {
    aggregateOutlineSuspendDepthRef.current += 1;
    if (aggregateOutlineSuspendDepthRef.current === 1) {
      setAggregateOutlineSuspended(true);
    }
  }

  /**
   * 成对恢复包围框渲染，避免节点拖拽和选中组拖拽事件重叠时提前恢复。
   */
  function resumeAggregateOutlineRendering() {
    if (aggregateOutlineSuspendDepthRef.current === 0) {
      setAggregateOutlineSuspended(false);
      return;
    }
    aggregateOutlineSuspendDepthRef.current -= 1;
    if (aggregateOutlineSuspendDepthRef.current === 0) {
      setAggregateOutlineSuspended(false);
    }
  }

  function resolveSelectableActualNodeKeys(nodeKeys: Iterable<string>): string[] {
    const nextNodeKeys = new Set<string>();
    for (const nodeKey of nodeKeys) {
      if (!nodeByKey.has(nodeKey)) {
        continue;
      }
      nextNodeKeys.add(nodeKey);
    }
    return [...nextNodeKeys].sort((left, right) => left.localeCompare(right));
  }

  /**
   * 只从 ReactFlow 当前节点状态里提取真实 triggerSource/core 选区，过滤聚合块与包围盒等辅助节点。
   */
  function collectSelectableActualNodeKeysFromCanvasNodes(
    canvasNodes: Pick<GraphFlowNode, 'id' | 'selected'>[],
  ): string[] {
    return resolveSelectableActualNodeKeys(
      canvasNodes
        .filter((canvasNode) => Boolean(canvasNode.selected))
        .map((canvasNode) => String(canvasNode.id)),
    );
  }

  /**
   * 框选预览阶段直接消费 ReactFlow 的 select 增量，避免展开块存在时再依赖 onSelectionChange 猜选区。
   */
  function applySelectionPreviewNodeChanges(changes: NodeChange[]) {
    if (!selectionPreviewActiveRef.current) {
      return;
    }
    const nextNodeKeySet = new Set(pendingSelectionNodeKeysRef.current);
    let changed = false;
    changes.forEach((change) => {
      if (change.type !== 'select') {
        return;
      }
      const nodeKey = String(change.id);
      if (!nodeByKey.has(nodeKey)) {
        return;
      }
      changed = true;
      if (change.selected) {
        nextNodeKeySet.add(nodeKey);
        return;
      }
      nextNodeKeySet.delete(nodeKey);
    });
    if (!changed) {
      return;
    }
    pendingSelectionNodeKeysRef.current = [...nextNodeKeySet].sort((left, right) =>
      left.localeCompare(right),
    );
  }

  /**
   * 将最终确认的真实节点集合写回 ReactFlow 受控 selected，避免拖框预览态直接污染业务高亮。
   */
  function applyCanvasSelectedNodeKeys(nodeKeys: Iterable<string>) {
    const selectedNodeKeySet = new Set(resolveSelectableActualNodeKeys(nodeKeys));
    setNodes((currentNodes) =>
      currentNodes.map((node) => {
        const nextSelected = selectedNodeKeySet.has(String(node.id));
        return Boolean(node.selected) === nextSelected
          ? node
          : {
              ...node,
              selected: nextSelected,
            };
      }),
    );
  }

  /**
   * 拦截浏览器默认的中键自动滚屏，让中键只作用于 graph 画布内部平移。
   */
  function handleCanvasMiddleMouseEvent(event: React.MouseEvent<HTMLDivElement>) {
    if (event.button !== 1) {
      return;
    }
    event.preventDefault();
  }

  /**
   * 程序化清空选区时，短暂忽略 ReactFlow 回流的旧选中事件，避免清空后立即被重新写回。
   */
  function temporarilySuspendCanvasSelectionSync() {
    suspendCanvasSelectionSyncRef.current = true;
    if (resumeCanvasSelectionSyncFrameRef.current != null) {
      window.cancelAnimationFrame(resumeCanvasSelectionSyncFrameRef.current);
    }
    resumeCanvasSelectionSyncFrameRef.current = window.requestAnimationFrame(() => {
      resumeCanvasSelectionSyncFrameRef.current = window.requestAnimationFrame(() => {
        suspendCanvasSelectionSyncRef.current = false;
        resumeCanvasSelectionSyncFrameRef.current = null;
      });
    });
  }

  /**
   * 清空选择时要同时拦住内部选中变更，否则旧选区会把批量编辑集合重新写回。
   */
  function handleCanvasNodesChange(changes: NodeChange[]) {
    if (selectionPreviewActiveRef.current) {
      applySelectionPreviewNodeChanges(changes);
    }
    if (!suspendCanvasSelectionSyncRef.current && !selectionPreviewActiveRef.current) {
      onNodesChange(changes);
      return;
    }
    const filteredChanges = changes.filter((change) => change.type !== 'select');
    if (filteredChanges.length === 0) {
      return;
    }
    onNodesChange(filteredChanges);
  }

  /**
   * 将 ReactFlow 的当前框选结果同步为批量编辑集合，仅保留真实 triggerSource/core 节点。
   */
  function applyBatchSelectionFromNodeKeys(nodeKeys: Iterable<string>) {
    const nextSourceSerials = new Set<number>();
    const nextTargetSerials = new Set<number>();
    for (const nodeKey of nodeKeys) {
      const graphNode = nodeByKey.get(nodeKey);
      if (!graphNode) {
        continue;
      }
      if (graphNode.type === 'triggerSource') {
        nextSourceSerials.add(graphNode.serial);
        continue;
      }
      nextTargetSerials.add(graphNode.serial);
    }
    const normalizedSourceSerials = [...nextSourceSerials].sort((left, right) => left - right);
    const normalizedTargetSerials = [...nextTargetSerials].sort((left, right) => left - right);
    setSelectedEditSourceSerials((currentValues) =>
      sameNumberArray(currentValues, normalizedSourceSerials)
        ? currentValues
        : normalizedSourceSerials,
    );
    setSelectedEditTargetSerials((currentValues) =>
      sameNumberArray(currentValues, normalizedTargetSerials)
        ? currentValues
        : normalizedTargetSerials,
    );
  }

  function handleSelectionPreviewStart() {
    if (editMode === 'view') {
      return;
    }
    clearSelectionPreviewState();
    selectionPreviewActiveRef.current = true;
    pendingSelectionNodeKeysRef.current = collectSelectableActualNodeKeysFromCanvasNodes(nodes);
  }

  function handleSelectionPreviewEnd() {
    if (editMode === 'view') {
      return;
    }
    if (!selectionPreviewActiveRef.current) {
      return;
    }
    if (finalizeSelectionFrameRef.current != null) {
      window.cancelAnimationFrame(finalizeSelectionFrameRef.current);
    }
    finalizeSelectionFrameRef.current = window.requestAnimationFrame(() => {
      const finalNodeKeys = [...pendingSelectionNodeKeysRef.current];
      selectionPreviewActiveRef.current = false;
      finalizeSelectionFrameRef.current = null;
      pendingSelectionNodeKeysRef.current = [];
      applyCanvasSelectedNodeKeys(finalNodeKeys);
      applyBatchSelectionFromNodeKeys(finalNodeKeys);
    });
  }

  function applyLocalDraftChange(nextDraft: GraphDraft): boolean {
    if (sameGraphDraft(graphDraft, nextDraft)) {
      return false;
    }
    setUndoableGraphDraft(graphDraft);
    setGraphDraft(nextDraft);
    return true;
  }

  function handleUndoDraft() {
    if (!undoableGraphDraft) {
      return;
    }
    setDraftPersistError('');
    if (savePhase === 'error' || savePhase === 'conflict') {
      setSavePhase('idle');
    }
    setGraphDraft(undoableGraphDraft);
    setUndoableGraphDraft(null);
    setSaveMessage('已撤回最近一步本地草稿。');
  }

  function handleApplySearch() {
    setAppliedSearchText(searchDraftText);
    setAppliedSearchTypeFilter(searchDraftTypeFilter);
  }

  function handleClearSearch() {
    setSearchDraftText('');
    setAppliedSearchText('');
    setSearchDraftTypeFilter('all');
    setAppliedSearchTypeFilter('all');
  }

  function handleAutoLayout() {
    setPendingAggregateFocusNodeKey('');
    setNodes((currentNodes) => {
      const existingSelectedByNodeKey = new Map(
        currentNodes.map((node) => [node.id, Boolean(node.selected)] as const),
      );
      return buildGraphFlowNodes(
        graphCanvasView.canvasNodes,
        autoLayoutPositions,
        editMode,
        selectedNodeKey,
        hasSearch,
        matchedNodeKeys,
        selectedEditSourceNodeKeys,
        selectedEditTargetNodeKeys,
        draftDiff.changedNodeKeys,
      ).map((node) => ({
        ...node,
        selected: existingSelectedByNodeKey.get(node.id) ?? false,
      }));
    });
    window.requestAnimationFrame(() => {
      reactFlowInstance?.fitView({ padding: 0.18, duration: 260 });
    });
  }

  function handleEditModeChange(nextEditMode: GraphEditMode) {
    setEditMode(nextEditMode);
    if (nextEditMode === 'view') {
      clearSelectionPreviewState();
      temporarilySuspendCanvasSelectionSync();
      setSelectedEditSourceSerials([]);
      setSelectedEditTargetSerials([]);
      applyCanvasSelectedNodeKeys([]);
      setActiveSidebarPanel((currentPanel) =>
        currentPanel === 'batch' ? 'details' : currentPanel,
      );
      return;
    }
    setActiveSidebarPanel('batch');
  }

  function handleClearBatchSelection() {
    clearSelectionPreviewState();
    temporarilySuspendCanvasSelectionSync();
    setSelectedEditSourceSerials([]);
    setSelectedEditTargetSerials([]);
    applyCanvasSelectedNodeKeys([]);
  }

  function handleToggleExpandedAggregateNode(nodeKey: string) {
    setExpandedAggregateNodeKeys((currentValues) => toggleStringSelection(currentValues, nodeKey));
  }

  function handleCanvasNodeClick(nodeKey: string) {
    if (nodeKey.startsWith('aggregate:')) {
      setSelectedNodeKey(nodeKey);
      const willExpand = !expandedAggregateNodeKeySet.has(nodeKey);
      handleToggleExpandedAggregateNode(nodeKey);
      requestStructureLayoutRefresh(
        willExpand
          ? {
              focusAggregateNodeKey: nodeKey,
              fitViewport: false,
            }
          : undefined,
      );
      return;
    }
    if (!nodeByKey.has(nodeKey)) {
      return;
    }
    setSelectedNodeKey(nodeKey);
    if (editMode === 'view') {
      setActiveSidebarPanel('details');
      return;
    }
  }

  function handleCanvasNodeDoubleClick(nodeKey: string) {
    if (nodeKey.startsWith('aggregate:')) {
      focusNode(nodeKey);
      return;
    }
    focusNode(nodeKey);
  }

  function handleRevealIsolatedNode(nodeKey: string) {
    setPinnedIsolatedNodeKeys((currentValues) =>
      currentValues.includes(nodeKey)
        ? currentValues
        : [...currentValues, nodeKey].sort((left, right) => left.localeCompare(right)),
    );
    focusNode(nodeKey);
  }

  function handleClearPinnedIsolatedNodes() {
    // 清空临时显示时，同时回收仅依赖该集合保活的孤立节点选中态。
    if (
      selectedNodeKey &&
      pinnedIsolatedNodeKeys.includes(selectedNodeKey) &&
      !selectedNodeHasNonPinnedVisibilityReason
    ) {
      setSelectedNodeKey('');
      setPendingFocusNodeKey('');
    }
    setPinnedIsolatedNodeKeys([]);
  }

  function handleGraphNodeClick(nodeKey: string) {
    handleCanvasNodeClick(nodeKey);
  }

  function handleAliasChange(nodeType: GraphNodeTypeToken, serial: number, alias: string) {
    setDraftPersistError('');
    if (savePhase === 'error' || savePhase === 'conflict') {
      setSavePhase('idle');
    }
    applyLocalDraftChange(
      upsertAliasDraft(graphDraft, baseGraphBundle, nodeType, serial, alias),
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
    const nextDraft = applyBatchEditToDraft(
      graphDraft,
      baseGraphBundle,
      editMode,
      selectedEditSourceSerials,
      selectedEditTargetSerials,
    );
    if (
      applyLocalDraftChange(nextDraft)
    ) {
      setSaveMessage(
        `已将 ${formatEditModeLabel(editMode)} 操作写入本地草稿，点击 Save 后才会回传游戏真值。`,
      );
    }
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
      setUndoableGraphDraft(null);
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
          <div className="graph-search-panel">
            <label className="recording-file-field graph-search-field">
              <span>搜索节点</span>
              <input
                className="graph-search-input"
                type="text"
                value={searchDraftText}
                onChange={(event) => setSearchDraftText(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    handleApplySearch();
                  }
                }}
                placeholder="按别名、序号、nodeKey、连接模式搜索"
              />
            </label>
            <div className="graph-search-filter-row">
              <span className="graph-search-filter-label">类型</span>
              <div className="chip-group">
                {(['all', 'triggerSource', 'core'] as GraphSearchTypeFilter[]).map(
                  (filterValue) => (
                    <button
                      key={filterValue}
                      type="button"
                      className={`metric-chip${searchDraftTypeFilter === filterValue ? ' is-active' : ''}`}
                      onClick={() => setSearchDraftTypeFilter(filterValue)}
                    >
                      {formatSearchTypeLabel(filterValue)}
                    </button>
                  ),
                )}
              </div>
            </div>
            <div className="graph-search-filter-row">
              <span className="graph-search-filter-label">
                {hasPendingSearchChanges ? '搜索条件未应用' : '搜索条件已应用'}
              </span>
              <div className="graph-editor-actions">
                <button
                  type="button"
                  className="action-button"
                  onClick={handleApplySearch}
                  disabled={!hasPendingSearchChanges}
                >
                  应用搜索
                </button>
                <button
                  type="button"
                  className="action-button"
                  onClick={handleClearSearch}
                  disabled={
                    searchDraftText.length === 0 &&
                    appliedSearchText.length === 0 &&
                    searchDraftTypeFilter === 'all' &&
                    appliedSearchTypeFilter === 'all'
                  }
                >
                  清空搜索
                </button>
              </div>
            </div>
          </div>
          <div className="graph-editor-actions">
            <span className={statusClassName}>{statusText}</span>
            <button
              type="button"
              className="action-button"
              disabled={undoableGraphDraft == null}
              onClick={handleUndoDraft}
            >
              撤回一步草稿
            </button>
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
          点击聚合块，可展开或收起对应的局部 core 集合。
          搜索条件会在回车或点击“应用搜索”后刷新画布。
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
                  {buildSearchResultLabel(node)}
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
                <dt>Canvas</dt>
                <dd>{graphCanvasView.canvasNodes.length}</dd>
              </div>
              <div>
                <dt>Edges</dt>
                <dd>{effectiveGraphBundle.edges.length}</dd>
              </div>
              <div>
                <dt>Groups</dt>
                <dd>{graphCanvasView.aggregateNodes.length}</dd>
              </div>
              <div>
                <dt>Revision</dt>
                <dd>{baseGraphBundle.graphRevision}</dd>
              </div>
            </dl>
          </div>
          <div className="graph-canvas-legend" aria-label="graph legend">
            <span className="graph-legend-item">
              <span className="graph-legend-swatch is-trigger-source" />
              triggerSource
            </span>
            <span className="graph-legend-item">
              <span className="graph-legend-swatch is-core" />
              core
            </span>
            <span className="graph-legend-item">
              <span className="graph-legend-swatch is-aggregate" />
              aggregate
            </span>
            <span className="graph-legend-item">
              <span className="graph-legend-swatch is-aggregate-outline" />
              expanded aggregate area
            </span>
          </div>
          <div
            className="graph-canvas"
            onMouseDownCapture={handleCanvasMiddleMouseEvent}
            onAuxClick={handleCanvasMiddleMouseEvent}
          >
            {draftLoading ? (
              <div className="graph-empty-overlay">
                <p className="empty-state">正在加载 graph draft...</p>
              </div>
            ) : null}
            {!draftLoading && !hasCanvasNodes ? (
              <div className="graph-empty-overlay">
                <p className="empty-state">
                  {effectiveGraphBundle.nodes.length === 0
                    ? '当前图快照没有可展示节点。'
                    : '当前主画布没有默认可展示的拓扑块，可在右侧孤立节点池中选择节点。'}
                </p>
              </div>
            ) : null}
            <ReactFlow
              nodes={displayNodes}
              edges={edges}
              onNodesChange={handleCanvasNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={(_, node) => handleGraphNodeClick(String(node.id))}
              onNodeDoubleClick={(_, node) => handleCanvasNodeDoubleClick(String(node.id))}
              onPaneClick={() => setSelectedNodeKey('')}
              onSelectionChange={({ nodes: selectedNodes }) => {
                if (editMode === 'view' || suspendCanvasSelectionSyncRef.current) {
                  return;
                }
                if (selectionPreviewActiveRef.current) {
                  return;
                }
                const selectedNodeKeys = resolveSelectableActualNodeKeys(
                  selectedNodes.map((selectedNode) => String(selectedNode.id)),
                );
                applyBatchSelectionFromNodeKeys(selectedNodeKeys);
              }}
              onSelectionStart={handleSelectionPreviewStart}
              onSelectionEnd={handleSelectionPreviewEnd}
              onNodeDragStart={suspendAggregateOutlineRendering}
              onNodeDragStop={resumeAggregateOutlineRendering}
              onSelectionDragStart={suspendAggregateOutlineRendering}
              onSelectionDragStop={resumeAggregateOutlineRendering}
              onMoveStart={suspendAggregateOutlineRendering}
              onMoveEnd={resumeAggregateOutlineRendering}
              onInit={setReactFlowInstance}
              nodeTypes={graphNodeTypes}
              fitView
              fitViewOptions={{ padding: 0.18 }}
              minZoom={0.2}
              maxZoom={2.2}
              nodesConnectable={false}
              elementsSelectable={editMode !== 'view'}
              selectionOnDrag={editMode !== 'view'}
              selectionMode={SelectionMode.Full}
              multiSelectionKeyCode={['Meta', 'Control', 'Shift']}
              panOnDrag={[1]}
              zoomOnScroll
            >
              <Background color="rgba(255, 214, 191, 0.12)" gap={24} size={1} />
              <MiniMap
                pannable
                zoomable
                nodeColor={(node) =>
                  String(node.id).startsWith('aggregate-outline:')
                    ? 'transparent'
                    : String(node.id).startsWith('triggerSource:')
                    ? 'rgba(255, 181, 140, 0.82)'
                    : String(node.id).startsWith('aggregate:')
                      ? 'rgba(140, 213, 255, 0.86)'
                      : 'rgba(108, 230, 255, 0.8)'
                }
                maskColor="rgba(10, 12, 18, 0.28)"
              />
              <Controls />
            </ReactFlow>
          </div>
        </div>

        <aside className="graph-detail-card">
          <header className="card-header graph-detail-card-header">
            <div>
              <span className="section-tag">Details</span>
              <h2>节点详情与编辑</h2>
            </div>
            <div className="chip-group graph-detail-tabs">
              {(
                [
                  ['details', '详情'],
                  ['isolated', '孤立节点池'],
                  ['batch', '批量编辑'],
                ] as [GraphSidebarPanel, string][]
              ).map(([panelKey, panelLabel]) => (
                <button
                  key={panelKey}
                  type="button"
                  className={`metric-chip${activeSidebarPanel === panelKey ? ' is-active' : ''}`}
                  onClick={() => setActiveSidebarPanel(panelKey)}
                >
                  {panelLabel}
                </button>
              ))}
            </div>
          </header>

          {activeSidebarPanel === 'isolated' ? (
            <section className="graph-isolated-panel">
              <div className="graph-isolated-panel-header">
                <div>
                  <strong>孤立节点池</strong>
                  <p className="graph-batch-editor-caption">
                    默认不进入主画布。点击后会临时拉回画布并聚焦；搜索、草稿差异和编辑选择也会强制显示。
                  </p>
                </div>
                <div className="graph-isolated-panel-actions">
                  <span className="graph-isolated-panel-count">
                    当前临时显示 {pinnedIsolatedNodeCount} 个
                  </span>
                  <button
                    type="button"
                    className="action-button"
                    disabled={pinnedIsolatedNodeCount === 0}
                    onClick={handleClearPinnedIsolatedNodes}
                  >
                    清空临时显示
                  </button>
                </div>
              </div>
              <div className="graph-batch-selection-grid">
                <section className="graph-target-editor">
                  <div className="graph-target-editor-header">
                    <strong>Isolated TriggerSources</strong>
                    <span>数量 {graphCanvasView.isolatedTriggerSourceNodes.length}</span>
                  </div>
                  <div className="graph-target-list">
                    {graphCanvasView.isolatedTriggerSourceNodes.length === 0 ? (
                      <p className="empty-state">当前没有孤立的 triggerSource。</p>
                    ) : (
                      graphCanvasView.isolatedTriggerSourceNodes.map((isolatedNode) => (
                        <button
                          key={isolatedNode.nodeKey}
                          type="button"
                          className={`graph-target-item graph-target-chip${pinnedIsolatedNodeKeys.includes(isolatedNode.nodeKey) ? ' is-selected' : ''}`}
                          onClick={() => handleRevealIsolatedNode(isolatedNode.nodeKey)}
                        >
                          {isolatedNode.displayText}
                        </button>
                      ))
                    )}
                  </div>
                </section>
                <section className="graph-target-editor">
                  <div className="graph-target-editor-header">
                    <strong>Isolated Cores</strong>
                    <span>数量 {graphCanvasView.isolatedCoreNodes.length}</span>
                  </div>
                  <div className="graph-target-list">
                    {graphCanvasView.isolatedCoreNodes.length === 0 ? (
                      <p className="empty-state">当前没有孤立的 core。</p>
                    ) : (
                      graphCanvasView.isolatedCoreNodes.map((isolatedNode) => (
                        <button
                          key={isolatedNode.nodeKey}
                          type="button"
                          className={`graph-target-item graph-target-chip${pinnedIsolatedNodeKeys.includes(isolatedNode.nodeKey) ? ' is-selected' : ''}`}
                          onClick={() => handleRevealIsolatedNode(isolatedNode.nodeKey)}
                        >
                          {isolatedNode.displayText}
                        </button>
                      ))
                    )}
                  </div>
                </section>
              </div>
            </section>
          ) : null}

          {activeSidebarPanel === 'batch' ? (
            editMode !== 'view' ? (
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
                      <span>点击或框选 triggerSource，`Ctrl/Shift` 可追加多选。</span>
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
                      <span>点击或框选 core，`Ctrl/Shift` 可追加多选。</span>
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
                  <button
                    type="button"
                    className="action-button"
                    onClick={handleClearBatchSelection}
                  >
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
            ) : (
              <p className="empty-state graph-detail-empty">
                当前是查看模式。切换到 `add`、`remove` 或 `replace` 后，这里会显示批量编辑面板。
              </p>
            )
          ) : null}

          {activeSidebarPanel === 'details' ? (
            !selectedNode ? (
              <p className="empty-state graph-detail-empty">
                点击图中的一个节点后，这里会显示其结构字段、revision，以及第一版可编辑字段。
              </p>
            ) : (
              <div className="graph-detail-section">
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
                  <div>
                    <dt>Structure Checksum</dt>
                    <dd>{effectiveGraphBundle.structureChecksum.slice(0, 12)}</dd>
                  </div>
                </dl>
                {selectedNode.type === 'triggerSource' ? (
                  <section className="graph-target-editor">
                    <div className="graph-target-editor-header">
                      <strong>Current Target Cores</strong>
                      <span>
                        这里展示当前草稿视角下的有效目标集合；批量编辑请使用右侧批量编辑页签。
                      </span>
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
              </div>
            )
          ) : null}
        </aside>
      </div>
    </section>
  );
}
