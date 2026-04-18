import type { CSSProperties } from 'react';
import ReactFlow, { Position, type Edge, type NodeProps, type XYPosition } from 'reactflow';
import type {
  GraphNodeInfo,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
} from '../../graphTypes';
import type {
  DraftEdgeDiffState,
  GraphCanvasAggregateNode,
  GraphCanvasEdgeInfo,
  GraphCanvasNodeInfo,
  GraphCanvasView,
  GraphDraftDiff,
  GraphEditMode,
  GraphFlowNode,
  GraphFlowNodeData,
  GraphLayoutComponent,
} from './types';
import {
  AGGREGATE_OUTLINE_PADDING_X,
  AGGREGATE_OUTLINE_PADDING_Y,
  AUTO_LAYOUT_MAX_ROW_WIDTH,
  AUTO_LAYOUT_START_X,
  AUTO_LAYOUT_START_Y,
  COMPONENT_BLOCK_GAP_X,
  COMPONENT_BLOCK_GAP_Y,
  COMPONENT_LANE_COLUMN_GAP_X,
  COMPONENT_LANE_MAX_ROW_COUNT,
  COMPONENT_LANE_MIN_ROW_COUNT,
  COMPONENT_LAYER_GAP_X,
  COMPONENT_NODE_GAP_Y,
  COMPONENT_STAGGER_X,
  COMPONENT_STAGGER_Y,
  GRAPH_NODE_HEIGHT,
  GRAPH_NODE_WIDTH,
  LANE_COLUMN_STAGGER_Y,
  LANE_JITTER_X,
  LANE_JITTER_Y,
  SHARED_CORE_GROUP_MIN_CORE_COUNT,
  SHARED_CORE_GROUP_MIN_SOURCE_COUNT,
  SHARED_TRIGGER_SOURCE_GROUP_MIN_SOURCE_COUNT,
  SHARED_TRIGGER_SOURCE_GROUP_MIN_TARGET_COUNT,
} from './types';

function AggregateOutlineNode(_: NodeProps<GraphFlowNodeData>): JSX.Element {
  return <div className="graph-aggregate-outline-node" />;
}

export const graphNodeTypes = {
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
  const memberLabel =
    aggregateNode.aggregateRole === 'core'
      ? 'grouped cores'
      : 'grouped triggerSources';
  const connectedLabel =
    aggregateNode.aggregateRole === 'core'
      ? 'triggerSources'
      : 'cores';
  return (
    <div className="graph-node-label">
      <strong className="graph-node-title">
        {aggregateNode.expanded
          ? `${aggregateNode.memberCount} ${memberLabel}`
          : `+${aggregateNode.memberCount} ${memberLabel}`}
      </strong>
      <span className="graph-node-meta">
        {aggregateNode.connectedSerials.length} {connectedLabel} ·{' '}
        {aggregateNode.expanded ? '已展开，仅展开节点' : '点击展开节点'}
      </span>
    </div>
  );
}

export function buildAggregateOutlineFlowNodes(
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
      ...aggregateNode.memberNodeKeys.map((nodeKey) => positionedNodeByKey.get(nodeKey)),
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

export function sameAggregateOutlineFlowNodes(left: GraphFlowNode[], right: GraphFlowNode[]): boolean {
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

function buildNodeStyle(
  node: GraphNodeInfo,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  selectedEditSourceNodeKeys: Set<string>,
  selectedEditTargetNodeKeys: Set<string>,
  draftChangedNodeKeys: Set<string>,
): CSSProperties {
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
): CSSProperties {
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

export function toggleStringSelection(currentValues: string[], targetValue: string): string[] {
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

function buildAggregateNodeKey(
  aggregateRole: GraphCanvasAggregateNode['aggregateRole'],
  signatureKey: string,
): string {
  return `aggregate:${aggregateRole}:${signatureKey}`;
}

function resolveCanvasNodeLaneType(node: GraphCanvasNodeInfo): GraphNodeTypeToken {
  return node.kind === 'actual' ? node.graphNode.type : node.aggregateRole;
}

function resolveCanvasNodeSortSerial(node: GraphCanvasNodeInfo): number {
  if (node.kind === 'actual') {
    return node.graphNode.serial;
  }
  return node.anchorSerial;
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

export function buildEdgeCountByNodeKey(graphBundle: GraphSnapshotBundle): Map<string, number> {
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

function buildSourceTargetsByNodeKey(graphBundle: GraphSnapshotBundle): Map<string, GraphNodeInfo[]> {
  const nodeByKey = new Map(graphBundle.nodes.map((node) => [node.nodeKey, node] as const));
  const targetsBySourceNodeKey = new Map<string, GraphNodeInfo[]>();
  graphBundle.edges.forEach((edge) => {
    const targetNode = nodeByKey.get(edge.targetNodeKey);
    if (targetNode == null || targetNode.type !== 'core') {
      return;
    }
    const currentTargets = targetsBySourceNodeKey.get(edge.sourceNodeKey) ?? [];
    currentTargets.push(targetNode);
    targetsBySourceNodeKey.set(edge.sourceNodeKey, currentTargets);
  });
  targetsBySourceNodeKey.forEach((targets, sourceNodeKey) => {
    targetsBySourceNodeKey.set(
      sourceNodeKey,
      [...targets].sort(compareGraphNodeIdentity),
    );
  });
  return targetsBySourceNodeKey;
}

export function buildGraphCanvasView(
  effectiveGraphBundle: GraphSnapshotBundle,
  forcedVisibleNodeKeys: Set<string>,
  expandedAggregateNodeKeys: Set<string>,
): GraphCanvasView {
  const originalEdgeCountByNodeKey = buildEdgeCountByNodeKey(effectiveGraphBundle);
  const targetSourcesByNodeKey = buildTargetSourcesByNodeKey(effectiveGraphBundle);
  const sourceTargetsByNodeKey = buildSourceTargetsByNodeKey(effectiveGraphBundle);
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

  const hiddenActualEdgeKeys = new Set<string>();
  const aggregatedCoreNodeKeys = new Set<string>();
  sharedCoreGroupsBySignature.forEach((group, signatureKey) => {
    if (group.coreNodes.length < SHARED_CORE_GROUP_MIN_CORE_COUNT) {
      return;
    }
    const sourceNodes = [...group.sourceNodes].sort(compareGraphNodeIdentity);
    const coreNodes = [...group.coreNodes].sort(compareGraphNodeIdentity);
    const aggregateNodeKey = buildAggregateNodeKey('core', signatureKey);
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      coreNodes.some((coreNode) => forcedVisibleNodeKeys.has(coreNode.nodeKey));
    const aggregateNode: GraphCanvasAggregateNode = {
      kind: 'aggregate',
      aggregateRole: 'core',
      nodeKey: aggregateNodeKey,
      signatureKey,
      sourceNodeKeys: sourceNodes.map((node) => node.nodeKey),
      sourceSerials: sourceNodes.map((node) => node.serial),
      coreNodeKeys: coreNodes.map((node) => node.nodeKey),
      coreSerials: coreNodes.map((node) => node.serial),
      memberNodeKeys: coreNodes.map((node) => node.nodeKey),
      memberSerials: coreNodes.map((node) => node.serial),
      connectedNodeKeys: sourceNodes.map((node) => node.nodeKey),
      connectedSerials: sourceNodes.map((node) => node.serial),
      memberCount: coreNodes.length,
      anchorSerial: coreNodes[0]?.serial ?? 0,
      expanded,
    };
    aggregateNodes.push(aggregateNode);
    aggregateNode.coreNodeKeys.forEach((nodeKey) => aggregatedCoreNodeKeys.add(nodeKey));
    aggregateNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
        hiddenActualEdgeKeys.add(`${sourceNodeKey}->${coreNodeKey}`);
      });
    });
  });

  const sharedTriggerSourceGroupsBySignature = new Map<
    string,
    {
      sourceNodes: GraphNodeInfo[];
      coreNodes: GraphNodeInfo[];
    }
  >();
  effectiveGraphBundle.nodes
    .filter((node) => node.type === 'triggerSource')
    .sort(compareGraphNodeIdentity)
    .forEach((sourceNode) => {
      const coreNodes = sourceTargetsByNodeKey.get(sourceNode.nodeKey) ?? [];
      if (coreNodes.length < SHARED_TRIGGER_SOURCE_GROUP_MIN_TARGET_COUNT) {
        return;
      }
      if (coreNodes.some((coreNode) => aggregatedCoreNodeKeys.has(coreNode.nodeKey))) {
        return;
      }
      const signatureKey = coreNodes.map((coreNode) => coreNode.nodeKey).join('|');
      const currentGroup = sharedTriggerSourceGroupsBySignature.get(signatureKey);
      if (currentGroup == null) {
        sharedTriggerSourceGroupsBySignature.set(signatureKey, {
          sourceNodes: [sourceNode],
          coreNodes,
        });
        return;
      }
      currentGroup.sourceNodes.push(sourceNode);
    });

  sharedTriggerSourceGroupsBySignature.forEach((group, signatureKey) => {
    if (group.sourceNodes.length < SHARED_TRIGGER_SOURCE_GROUP_MIN_SOURCE_COUNT) {
      return;
    }
    const sourceNodes = [...group.sourceNodes].sort(compareGraphNodeIdentity);
    const coreNodes = [...group.coreNodes].sort(compareGraphNodeIdentity);
    const aggregateNodeKey = buildAggregateNodeKey('triggerSource', signatureKey);
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      sourceNodes.some((sourceNode) => forcedVisibleNodeKeys.has(sourceNode.nodeKey));
    const aggregateNode: GraphCanvasAggregateNode = {
      kind: 'aggregate',
      aggregateRole: 'triggerSource',
      nodeKey: aggregateNodeKey,
      signatureKey,
      sourceNodeKeys: sourceNodes.map((node) => node.nodeKey),
      sourceSerials: sourceNodes.map((node) => node.serial),
      coreNodeKeys: coreNodes.map((node) => node.nodeKey),
      coreSerials: coreNodes.map((node) => node.serial),
      memberNodeKeys: sourceNodes.map((node) => node.nodeKey),
      memberSerials: sourceNodes.map((node) => node.serial),
      connectedNodeKeys: coreNodes.map((node) => node.nodeKey),
      connectedSerials: coreNodes.map((node) => node.serial),
      memberCount: sourceNodes.length,
      anchorSerial: sourceNodes[0]?.serial ?? 0,
      expanded,
    };
    aggregateNodes.push(aggregateNode);
    aggregateNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
        hiddenActualEdgeKeys.add(`${sourceNodeKey}->${coreNodeKey}`);
      });
    });
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
    aggregateNode.connectedNodeKeys.forEach((connectedNodeKey) => {
      phaseVisibleActualNodeKeys.add(connectedNodeKey);
    });
    if (aggregateNode.expanded) {
      aggregateNode.memberNodeKeys.forEach((memberNodeKey) => {
        phaseVisibleActualNodeKeys.add(memberNodeKey);
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
    if (aggregateNode.aggregateRole === 'core') {
      aggregateNode.connectedNodeKeys.forEach((sourceNodeKey) => {
        phaseCanvasEdges.push({
          edgeKey: `aggregate-edge:${sourceNodeKey}:${aggregateNode.nodeKey}`,
          sourceNodeKey,
          targetNodeKey: aggregateNode.nodeKey,
          kind: 'aggregate',
          diffState: 'base',
        });
      });
      return;
    }
    aggregateNode.connectedNodeKeys.forEach((coreNodeKey) => {
      phaseCanvasEdges.push({
        edgeKey: `aggregate-edge:${aggregateNode.nodeKey}:${coreNodeKey}`,
        sourceNodeKey: aggregateNode.nodeKey,
        targetNodeKey: coreNodeKey,
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
    aggregateNode.memberNodeKeys.forEach((memberNodeKey) => {
      visibleCanvasNodeKeys.add(memberNodeKey);
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
    aggregateNode.memberNodeKeys.forEach((memberNodeKey) => {
      if (!canvasNodeKeySet.has(memberNodeKey)) {
        return;
      }
      layoutEdges.push({
        edgeKey: `aggregate-layout:${aggregateNode.nodeKey}:${memberNodeKey}`,
        sourceNodeKey: aggregateNode.nodeKey,
        targetNodeKey: memberNodeKey,
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
export function buildAutoLayoutPositions(
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

export function buildGraphFlowNodes(
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

export function buildEdges(
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
