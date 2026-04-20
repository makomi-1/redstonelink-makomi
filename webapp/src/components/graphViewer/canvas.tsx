import type { CSSProperties } from "react";
import {
  Position,
  type Edge,
  type NodeProps,
  type XYPosition,
} from "reactflow";
import type {
  GraphNodeInfo,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
} from "../../graphTypes";
import { pickLocalizedText, type AppLanguage } from "../../app/i18n";
import type {
  DraftEdgeDiffState,
  GraphCanvasAggregateNode,
  GraphCanvasChannelHubNode,
  GraphCanvasEdgeInfo,
  GraphCanvasNodeInfo,
  GraphCanvasView,
  GraphDisplayMode,
  GraphDraftDiff,
  GraphEditMode,
  GraphFlowNode,
  GraphFlowNodeData,
  GraphLayoutComponent,
} from "./types";
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
} from "./types";

function AggregateOutlineNode(_: NodeProps<GraphFlowNodeData>): JSX.Element {
  return <div className="graph-aggregate-outline-node" />;
}

export const graphNodeTypes = {
  aggregateOutline: AggregateOutlineNode,
};

function buildCanvasNodeTitle(node: GraphNodeInfo): string {
  const normalizedAlias = node.alias.trim();
  return normalizedAlias
    ? `#${node.serial} (${normalizedAlias})`
    : `#${node.serial}`;
}

function buildNodeLabel(node: GraphNodeInfo): JSX.Element {
  return (
    <div className="graph-node-label is-compact">
      <strong className="graph-node-title">{buildCanvasNodeTitle(node)}</strong>
    </div>
  );
}

function buildAggregateNodeLabel(
  aggregateNode: GraphCanvasAggregateNode,
  language: AppLanguage,
): JSX.Element {
  const memberLabel =
    aggregateNode.aggregateRole === "core"
      ? pickLocalizedText(language, "聚合 cores", "grouped cores")
      : pickLocalizedText(language, "聚合 triggerSources", "grouped triggerSources");
  const connectedLabel =
    aggregateNode.aggregateRole === "core"
      ? "triggerSources"
      : "cores";
  return (
    <div className="graph-node-label">
      <strong className="graph-node-title">
        {aggregateNode.expanded
          ? `${aggregateNode.memberCount} ${memberLabel}`
          : `+${aggregateNode.memberCount} ${memberLabel}`}
      </strong>
      <span className="graph-node-meta">
        {aggregateNode.connectedSerials.length} {connectedLabel} ·{" "}
        {aggregateNode.expanded
          ? pickLocalizedText(language, "已展开，仅展开节点", "expanded, showing members")
          : pickLocalizedText(language, "点击展开节点", "click to expand")}
      </span>
    </div>
  );
}

function buildChannelHubNodeLabel(
  channelHubNode: GraphCanvasChannelHubNode,
  language: AppLanguage,
): JSX.Element {
  return (
    <div className="graph-node-label">
      <strong className="graph-node-title">
        channel #{channelHubNode.channel}
      </strong>
      <span className="graph-node-meta">
        {channelHubNode.sourceSerials.length} triggerSources ·{" "}
        {channelHubNode.coreSerials.length} cores
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
      ...aggregateNode.memberNodeKeys.map((nodeKey) =>
        positionedNodeByKey.get(nodeKey),
      ),
    ].filter((node): node is GraphFlowNode => node != null);
    if (memberNodes.length <= 1) {
      return [];
    }
    const minX =
      Math.min(...memberNodes.map((node) => node.position.x)) -
      AGGREGATE_OUTLINE_PADDING_X;
    const minY =
      Math.min(...memberNodes.map((node) => node.position.y)) -
      AGGREGATE_OUTLINE_PADDING_Y;
    const maxX =
      Math.max(
        ...memberNodes.map((node) => node.position.x + GRAPH_NODE_WIDTH),
      ) + AGGREGATE_OUTLINE_PADDING_X;
    const maxY =
      Math.max(
        ...memberNodes.map((node) => node.position.y + GRAPH_NODE_HEIGHT),
      ) + AGGREGATE_OUTLINE_PADDING_Y;
    return [
      {
        id: `aggregate-outline:${aggregateNode.nodeKey}`,
        type: "aggregateOutline",
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
          pointerEvents: "none",
        },
      },
    ];
  });
}

export function sameAggregateOutlineFlowNodes(
  left: GraphFlowNode[],
  right: GraphFlowNode[],
): boolean {
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
      Number(leftNode.style?.width ?? 0) ===
        Number(rightNode.style?.width ?? 0) &&
      Number(leftNode.style?.height ?? 0) ===
        Number(rightNode.style?.height ?? 0)
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
  const selectedAsSource = selectedEditSourceNodeKeys.has(node.nodeKey);
  const selectedAsTarget = selectedEditTargetNodeKeys.has(node.nodeKey);
  const draftChanged = draftChangedNodeKeys.has(node.nodeKey);
  const accent =
    node.type === "triggerSource"
      ? "var(--graph-node-trigger-accent)"
      : "var(--graph-node-core-accent)";
  const baseBackground =
    node.type === "triggerSource"
      ? "var(--graph-node-trigger-surface)"
      : "var(--graph-node-core-surface)";
  const selectionAccent = selectedAsSource
    ? "var(--graph-node-source-selection)"
    : selectedAsTarget
      ? "var(--graph-node-target-selection)"
      : accent;
  const borderColor =
    selected || selectedAsSource || selectedAsTarget
      ? selectionAccent
      : draftChanged
        ? "var(--graph-node-draft-accent)"
        : "var(--graph-node-base-border)";
  return {
    minWidth: GRAPH_NODE_WIDTH,
    borderRadius: "var(--graph-node-radius)",
    border: `1px solid ${borderColor}`,
    background: draftChanged
      ? `linear-gradient(180deg, color-mix(in srgb, var(--graph-node-draft-accent) 18%, transparent), color-mix(in srgb, var(--graph-node-draft-accent) 7%, transparent)), ${baseBackground}`
      : baseBackground,
    boxShadow:
      selected || selectedAsSource || selectedAsTarget
        ? `0 0 0 1px ${selectionAccent} inset, 0 12px 24px rgba(6, 10, 18, 0.24)`
        : draftChanged
          ? "0 0 0 1px color-mix(in srgb, var(--graph-node-draft-accent) 40%, transparent) inset"
          : "none",
    color: "var(--graph-node-text)",
    opacity: 1,
  };
}

function buildAggregateNodeStyle(
  aggregateNode: GraphCanvasAggregateNode,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  draftChangedCanvasNodeKeys: Set<string>,
): CSSProperties {
  const selected = selectedNodeKey === aggregateNode.nodeKey;
  const expanded = aggregateNode.expanded;
  const draftChanged = draftChangedCanvasNodeKeys.has(aggregateNode.nodeKey);
  const borderColor =
    selected || expanded
      ? "var(--graph-aggregate-accent)"
      : draftChanged
        ? "var(--graph-node-draft-accent)"
        : "color-mix(in srgb, var(--graph-aggregate-accent) 42%, transparent)";
  return {
    minWidth: GRAPH_NODE_WIDTH,
    borderRadius: "var(--graph-aggregate-node-radius)",
    border: `1px ${expanded ? "solid" : "dashed"} ${borderColor}`,
    background: draftChanged
      ? `linear-gradient(180deg, color-mix(in srgb, var(--graph-node-draft-accent) 16%, transparent), color-mix(in srgb, var(--graph-node-draft-accent) 7%, transparent)), var(--graph-aggregate-surface)`
      : "var(--graph-aggregate-surface)",
    boxShadow:
      selected || expanded
        ? "0 0 0 1px color-mix(in srgb, var(--graph-aggregate-accent) 50%, transparent) inset, 0 12px 24px rgba(6, 10, 18, 0.22)"
        : draftChanged
          ? "0 0 0 1px color-mix(in srgb, var(--graph-node-draft-accent) 36%, transparent) inset"
        : "0 0 0 1px color-mix(in srgb, var(--graph-aggregate-accent) 16%, transparent) inset",
    color: "var(--graph-node-text)",
    opacity: 1,
  };
}

function buildChannelHubNodeStyle(
  channelHubNode: GraphCanvasChannelHubNode,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  draftChangedCanvasNodeKeys: Set<string>,
): CSSProperties {
  const selected = selectedNodeKey === channelHubNode.nodeKey;
  const draftChanged = draftChangedCanvasNodeKeys.has(channelHubNode.nodeKey);
  const borderColor = selected
    ? "var(--graph-channel-accent)"
    : draftChanged
      ? "var(--graph-node-draft-accent)"
      : "color-mix(in srgb, var(--graph-channel-accent) 56%, transparent)";
  return {
    minWidth: GRAPH_NODE_WIDTH,
    borderRadius: "var(--graph-channel-node-radius)",
    border: `1px solid ${borderColor}`,
    background: draftChanged
      ? `linear-gradient(180deg, color-mix(in srgb, var(--graph-node-draft-accent) 14%, transparent), color-mix(in srgb, var(--graph-node-draft-accent) 5%, transparent)), var(--graph-channel-surface)`
      : "var(--graph-channel-surface)",
    boxShadow: selected
      ? "0 0 0 1px color-mix(in srgb, var(--graph-channel-accent) 56%, transparent) inset, 0 12px 24px rgba(6, 10, 18, 0.22)"
      : draftChanged
        ? "0 0 0 1px color-mix(in srgb, var(--graph-node-draft-accent) 36%, transparent) inset"
      : "0 0 0 1px color-mix(in srgb, var(--graph-channel-accent) 18%, transparent) inset",
    color: "var(--graph-node-text)",
    opacity: 1,
  };
}

function buildEdgeStyle(
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  edge: Edge,
  diffState: DraftEdgeDiffState,
): Edge {
  const edgeOpacity =
    diffState === "removed" ? 0.72 : diffState === "added" ? 0.96 : 0.88;
  return {
    ...edge,
    style: {
      stroke:
        diffState === "base"
          ? "var(--graph-edge-base)"
          : "var(--graph-edge-added)",
      strokeWidth: diffState === "base" ? 2.2 : 2.6,
      strokeDasharray: diffState === "removed" ? "8 5" : undefined,
      opacity: edgeOpacity,
    },
  };
}

function buildAggregateEdgeStyle(
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  edge: Edge,
): Edge {
  return {
    ...edge,
    style: {
      stroke: "var(--graph-edge-aggregate)",
      strokeWidth: 2.1,
      strokeDasharray: "10 5",
      opacity: 0.76,
    },
  };
}

function buildChannelEdgeStyle(
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  edge: Edge,
): Edge {
  return {
    ...edge,
    style: {
      stroke: "var(--graph-edge-channel)",
      strokeWidth: 2.2,
      opacity: 0.82,
    },
  };
}

function compareNodeType(
  left: GraphNodeTypeToken,
  right: GraphNodeTypeToken,
): number {
  if (left === right) {
    return 0;
  }
  return left === "triggerSource" ? -1 : 1;
}

type GraphCanvasLaneType = GraphNodeTypeToken | "channelHub";

function compareCanvasLaneType(
  left: GraphCanvasLaneType,
  right: GraphCanvasLaneType,
): number {
  const orderByType: Record<GraphCanvasLaneType, number> = {
    triggerSource: 0,
    channelHub: 1,
    core: 2,
  };
  return orderByType[left] - orderByType[right];
}

export function toggleStringSelection(
  currentValues: string[],
  targetValue: string,
): string[] {
  if (currentValues.includes(targetValue)) {
    return currentValues.filter((value) => value !== targetValue);
  }
  return [...currentValues, targetValue].sort((left, right) =>
    left.localeCompare(right),
  );
}

function compareGraphNodeIdentity(
  left: GraphNodeInfo,
  right: GraphNodeInfo,
): number {
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
  aggregateRole: GraphCanvasAggregateNode["aggregateRole"],
  signatureKey: string,
): string {
  return `aggregate:${aggregateRole}:${signatureKey}`;
}

function buildChannelHubNodeKey(channel: number): string {
  return `channelHub:${Math.max(0, Math.trunc(channel))}`;
}

function buildChannelCanvasEdgeKey(
  sourceNodeKey: string,
  targetNodeKey: string,
): string {
  return `channel-edge:${sourceNodeKey}:${targetNodeKey}`;
}

function hasAggregateAutoExpandMember(
  memberNodeKeys: string[],
  aggregateAutoExpandNodeKeys: Set<string>,
): boolean {
  return memberNodeKeys.some((nodeKey) =>
    aggregateAutoExpandNodeKeys.has(nodeKey),
  );
}

function resolveCanvasNodeLaneType(
  node: GraphCanvasNodeInfo,
): GraphCanvasLaneType {
  if (node.kind === "actual") {
    return node.graphNode.type;
  }
  if (node.kind === "channelHub") {
    return "channelHub";
  }
  return node.aggregateRole;
}

function resolveCanvasNodeSortSerial(node: GraphCanvasNodeInfo): number {
  if (node.kind === "actual") {
    return node.graphNode.serial;
  }
  if (node.kind === "channelHub") {
    return node.channel;
  }
  return node.anchorSerial;
}

function compareCanvasNodeIdentity(
  left: GraphCanvasNodeInfo,
  right: GraphCanvasNodeInfo,
): number {
  const serialOrder =
    resolveCanvasNodeSortSerial(left) - resolveCanvasNodeSortSerial(right);
  if (serialOrder !== 0) {
    return serialOrder;
  }
  const laneOrder = compareCanvasLaneType(
    resolveCanvasNodeLaneType(left),
    resolveCanvasNodeLaneType(right),
  );
  if (laneOrder !== 0) {
    return laneOrder;
  }
  if (left.kind !== right.kind) {
    const orderByKind: Record<GraphCanvasNodeInfo["kind"], number> = {
      actual: 0,
      channelHub: 1,
      aggregate: 2,
    };
    return orderByKind[left.kind] - orderByKind[right.kind];
  }
  return left.nodeKey.localeCompare(right.nodeKey);
}

/**
 * 聚合块除了显式展开，还需要根据最终进入画布的成员节点推断视觉展开态，
 * 否则成员已出现但聚合块文案仍显示“未展开”。
 */
function resolveVisualExpandedAggregateNodes(
  aggregateNodes: GraphCanvasAggregateNode[],
  expansionVisibleNodeKeys: Set<string>,
): GraphCanvasAggregateNode[] {
  return aggregateNodes.map((aggregateNode) => {
    const visuallyExpanded =
      aggregateNode.expanded ||
      aggregateNode.memberNodeKeys.some((memberNodeKey) =>
        expansionVisibleNodeKeys.has(memberNodeKey),
      );
    if (visuallyExpanded === aggregateNode.expanded) {
      return aggregateNode;
    }
    return {
      ...aggregateNode,
      expanded: visuallyExpanded,
    };
  });
}

/**
 * 统一收敛聚合块的最终显示口径：
 * 先按边和强制显示集合确定候选可见节点，再补上视觉展开态对应的聚合块与成员。
 */
function resolveCanvasAggregateVisibility(
  aggregateNodes: GraphCanvasAggregateNode[],
  phaseCanvasEdges: GraphCanvasEdgeInfo[],
  forcedVisibleNodeKeys: Set<string>,
  aggregateAutoExpandNodeKeys: Set<string>,
  promoteVisibleMembersToExpanded = true,
): {
  aggregateNodes: GraphCanvasAggregateNode[];
  visibleCanvasNodeKeys: Set<string>;
} {
  const visibleCanvasNodeKeys = new Set<string>();
  phaseCanvasEdges.forEach((edge) => {
    visibleCanvasNodeKeys.add(edge.sourceNodeKey);
    visibleCanvasNodeKeys.add(edge.targetNodeKey);
  });
  const expansionVisibleNodeKeys = new Set(visibleCanvasNodeKeys);
  aggregateAutoExpandNodeKeys.forEach((nodeKey) => {
    expansionVisibleNodeKeys.add(nodeKey);
  });
  forcedVisibleNodeKeys.forEach((nodeKey) => {
    visibleCanvasNodeKeys.add(nodeKey);
  });
  const resolvedAggregateNodes = promoteVisibleMembersToExpanded
    ? resolveVisualExpandedAggregateNodes(
        aggregateNodes,
        expansionVisibleNodeKeys,
      )
    : aggregateNodes;
  resolvedAggregateNodes.forEach((aggregateNode) => {
    if (!aggregateNode.expanded) {
      return;
    }
    visibleCanvasNodeKeys.add(aggregateNode.nodeKey);
    aggregateNode.memberNodeKeys.forEach((memberNodeKey) => {
      visibleCanvasNodeKeys.add(memberNodeKey);
    });
  });
  return {
    aggregateNodes: resolvedAggregateNodes,
    visibleCanvasNodeKeys,
  };
}

export function buildEdgeCountByNodeKey(
  graphBundle: GraphSnapshotBundle,
): Map<string, number> {
  const edgeCountByNodeKey = new Map<string, number>(
    graphBundle.nodes.map((node) => [node.nodeKey, 0]),
  );
  graphBundle.edges.forEach((edge) => {
    edgeCountByNodeKey.set(
      edge.sourceNodeKey,
      (edgeCountByNodeKey.get(edge.sourceNodeKey) ?? 0) + 1,
    );
    edgeCountByNodeKey.set(
      edge.targetNodeKey,
      (edgeCountByNodeKey.get(edge.targetNodeKey) ?? 0) + 1,
    );
  });
  return edgeCountByNodeKey;
}

function buildTargetSourcesByNodeKey(
  graphBundle: GraphSnapshotBundle,
): Map<string, GraphNodeInfo[]> {
  const nodeByKey = new Map(
    graphBundle.nodes.map((node) => [node.nodeKey, node] as const),
  );
  const sourcesByTargetNodeKey = new Map<string, GraphNodeInfo[]>();
  graphBundle.edges.forEach((edge) => {
    const sourceNode = nodeByKey.get(edge.sourceNodeKey);
    if (sourceNode == null || sourceNode.type !== "triggerSource") {
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

function buildSourceTargetsByNodeKey(
  graphBundle: GraphSnapshotBundle,
): Map<string, GraphNodeInfo[]> {
  const nodeByKey = new Map(
    graphBundle.nodes.map((node) => [node.nodeKey, node] as const),
  );
  const targetsBySourceNodeKey = new Map<string, GraphNodeInfo[]>();
  graphBundle.edges.forEach((edge) => {
    const targetNode = nodeByKey.get(edge.targetNodeKey);
    if (targetNode == null || targetNode.type !== "core") {
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

function buildSerialGraphCanvasView(
  effectiveGraphBundle: GraphSnapshotBundle,
  forcedVisibleNodeKeys: Set<string>,
  expandedAggregateNodeKeys: Set<string>,
  aggregateAutoExpandNodeKeys: Set<string> = forcedVisibleNodeKeys,
): GraphCanvasView {
  const serialNodes = effectiveGraphBundle.nodes.filter(
    (node) => node.connectionMode === "serial",
  );
  const serialNodeKeySet = new Set(serialNodes.map((node) => node.nodeKey));
  const serialEdges = effectiveGraphBundle.edges.filter(
    (edge) =>
      serialNodeKeySet.has(edge.sourceNodeKey) &&
      serialNodeKeySet.has(edge.targetNodeKey),
  );
  const serialGraphBundle: GraphSnapshotBundle = {
    ...effectiveGraphBundle,
    nodes: serialNodes,
    edges: serialEdges,
    stats: {
      ...effectiveGraphBundle.stats,
      nodeCount: serialNodes.length,
      edgeCount: serialEdges.length,
      triggerSourceCount: serialNodes.filter(
        (node) => node.type === "triggerSource",
      ).length,
      coreCount: serialNodes.filter((node) => node.type === "core").length,
    },
  };
  const originalEdgeCountByNodeKey =
    buildEdgeCountByNodeKey(serialGraphBundle);
  const targetSourcesByNodeKey = buildTargetSourcesByNodeKey(serialGraphBundle);
  const sourceTargetsByNodeKey = buildSourceTargetsByNodeKey(serialGraphBundle);
  const actualCanvasNodes: GraphCanvasNodeInfo[] = serialGraphBundle.nodes.map(
    (node) => ({
      kind: "actual",
      nodeKey: node.nodeKey,
      graphNode: node,
    }),
  );
  const actualNodeByKey = new Map(
    serialGraphBundle.nodes.map((node) => [node.nodeKey, node] as const),
  );
  const coreAggregateNodes: GraphCanvasAggregateNode[] = [];
  const triggerSourceAggregateNodes: GraphCanvasAggregateNode[] = [];
  const sharedCoreGroupsBySignature = new Map<
    string,
    {
      sourceNodes: GraphNodeInfo[];
      coreNodes: GraphNodeInfo[];
    }
  >();
  serialGraphBundle.nodes
    .filter((node) => node.type === "core")
    .sort(compareGraphNodeIdentity)
    .forEach((coreNode) => {
      const sourceNodes = targetSourcesByNodeKey.get(coreNode.nodeKey) ?? [];
      if (sourceNodes.length < SHARED_CORE_GROUP_MIN_SOURCE_COUNT) {
        return;
      }
      const signatureKey = sourceNodes
        .map((sourceNode) => sourceNode.nodeKey)
        .join("|");
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
  sharedCoreGroupsBySignature.forEach((group, signatureKey) => {
    if (group.coreNodes.length < SHARED_CORE_GROUP_MIN_CORE_COUNT) {
      return;
    }
    const sourceNodes = [...group.sourceNodes].sort(compareGraphNodeIdentity);
    const coreNodes = [...group.coreNodes].sort(compareGraphNodeIdentity);
    const aggregateNodeKey = buildAggregateNodeKey("core", signatureKey);
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      hasAggregateAutoExpandMember(
        coreNodes.map((coreNode) => coreNode.nodeKey),
        aggregateAutoExpandNodeKeys,
      );
    const aggregateNode: GraphCanvasAggregateNode = {
      kind: "aggregate",
      aggregateRole: "core",
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
    coreAggregateNodes.push(aggregateNode);
    aggregateNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      aggregateNode.coreNodeKeys.forEach((coreNodeKey) => {
        hiddenActualEdgeKeys.add(`${sourceNodeKey}->${coreNodeKey}`);
      });
    });
  });

  const phaseOneVisibleActualEdges = serialGraphBundle.edges.filter(
    (edge) => !hiddenActualEdgeKeys.has(edge.edgeKey),
  );
  const phaseOneCanvasEdges: GraphCanvasEdgeInfo[] = [
    ...phaseOneVisibleActualEdges.map((edge) => ({
      edgeKey: edge.edgeKey,
      sourceNodeKey: edge.sourceNodeKey,
      targetNodeKey: edge.targetNodeKey,
      kind: "actual" as const,
      diffState: "base" as DraftEdgeDiffState,
    })),
  ];
  coreAggregateNodes.forEach((aggregateNode) => {
    aggregateNode.connectedNodeKeys.forEach((sourceNodeKey) => {
      phaseOneCanvasEdges.push({
        edgeKey: `aggregate-edge:${sourceNodeKey}:${aggregateNode.nodeKey}`,
        sourceNodeKey,
        targetNodeKey: aggregateNode.nodeKey,
        kind: "aggregate",
        diffState: "base",
      });
    });
  });
  const phaseOneCanvasNodeByKey = new Map<string, GraphCanvasNodeInfo>([
    ...actualCanvasNodes.map((node) => [node.nodeKey, node] as const),
    ...coreAggregateNodes.map((node) => [node.nodeKey, node] as const),
  ]);
  const phaseOneConnectedTargetKeysBySourceNodeKey = new Map<
    string,
    Set<string>
  >();
  phaseOneCanvasEdges.forEach((edge) => {
    const sourceNode = actualNodeByKey.get(edge.sourceNodeKey);
    if (sourceNode == null || sourceNode.type !== "triggerSource") {
      return;
    }
    const currentTargetKeys =
      phaseOneConnectedTargetKeysBySourceNodeKey.get(sourceNode.nodeKey) ??
      new Set<string>();
    currentTargetKeys.add(edge.targetNodeKey);
    phaseOneConnectedTargetKeysBySourceNodeKey.set(
      sourceNode.nodeKey,
      currentTargetKeys,
    );
  });

  const sharedTriggerSourceGroupsBySignature = new Map<
    string,
    {
      sourceNodes: GraphNodeInfo[];
      connectedNodeKeys: string[];
    }
  >();
  serialGraphBundle.nodes
    .filter((node) => node.type === "triggerSource")
    .sort(compareGraphNodeIdentity)
    .forEach((sourceNode) => {
      const connectedCanvasNodes = Array.from(
        phaseOneConnectedTargetKeysBySourceNodeKey.get(sourceNode.nodeKey) ?? [],
      )
        .map((nodeKey) => phaseOneCanvasNodeByKey.get(nodeKey) ?? null)
        .filter((node): node is GraphCanvasNodeInfo => node != null)
        .sort(compareCanvasNodeIdentity);
      if (
        connectedCanvasNodes.length < SHARED_TRIGGER_SOURCE_GROUP_MIN_TARGET_COUNT
      ) {
        return;
      }
      const connectedNodeKeys = connectedCanvasNodes.map((node) => node.nodeKey);
      const signatureKey = connectedNodeKeys.join("|");
      const currentGroup =
        sharedTriggerSourceGroupsBySignature.get(signatureKey);
      if (currentGroup == null) {
        sharedTriggerSourceGroupsBySignature.set(signatureKey, {
          sourceNodes: [sourceNode],
          connectedNodeKeys,
        });
        return;
      }
      currentGroup.sourceNodes.push(sourceNode);
    });

  const hiddenPhaseTwoCanvasEdgeKeys = new Set<string>();
  sharedTriggerSourceGroupsBySignature.forEach((group, signatureKey) => {
    if (
      group.sourceNodes.length < SHARED_TRIGGER_SOURCE_GROUP_MIN_SOURCE_COUNT
    ) {
      return;
    }
    const sourceNodes = [...group.sourceNodes].sort(compareGraphNodeIdentity);
    const connectedCanvasNodes = group.connectedNodeKeys
      .map((nodeKey) => phaseOneCanvasNodeByKey.get(nodeKey) ?? null)
      .filter((node): node is GraphCanvasNodeInfo => node != null)
      .sort(compareCanvasNodeIdentity);
    if (
      connectedCanvasNodes.length < SHARED_TRIGGER_SOURCE_GROUP_MIN_TARGET_COUNT
    ) {
      return;
    }
    const coreNodesByKey = new Map<string, GraphNodeInfo>();
    sourceNodes.forEach((sourceNode) => {
      const coreNodes = sourceTargetsByNodeKey.get(sourceNode.nodeKey) ?? [];
      coreNodes.forEach((coreNode) => {
        coreNodesByKey.set(coreNode.nodeKey, coreNode);
        hiddenActualEdgeKeys.add(`${sourceNode.nodeKey}->${coreNode.nodeKey}`);
      });
      connectedCanvasNodes.forEach((connectedCanvasNode) => {
        hiddenPhaseTwoCanvasEdgeKeys.add(
          `${sourceNode.nodeKey}->${connectedCanvasNode.nodeKey}`,
        );
      });
    });
    const coreNodes = Array.from(coreNodesByKey.values()).sort(
      compareGraphNodeIdentity,
    );
    const aggregateNodeKey = buildAggregateNodeKey(
      "triggerSource",
      signatureKey,
    );
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      hasAggregateAutoExpandMember(
        sourceNodes.map((sourceNode) => sourceNode.nodeKey),
        aggregateAutoExpandNodeKeys,
      );
    const aggregateNode: GraphCanvasAggregateNode = {
      kind: "aggregate",
      aggregateRole: "triggerSource",
      nodeKey: aggregateNodeKey,
      signatureKey,
      sourceNodeKeys: sourceNodes.map((node) => node.nodeKey),
      sourceSerials: sourceNodes.map((node) => node.serial),
      coreNodeKeys: coreNodes.map((node) => node.nodeKey),
      coreSerials: coreNodes.map((node) => node.serial),
      memberNodeKeys: sourceNodes.map((node) => node.nodeKey),
      memberSerials: sourceNodes.map((node) => node.serial),
      connectedNodeKeys: connectedCanvasNodes.map((node) => node.nodeKey),
      connectedSerials: coreNodes.map((node) => node.serial),
      memberCount: sourceNodes.length,
      anchorSerial: sourceNodes[0]?.serial ?? 0,
      expanded,
    };
    triggerSourceAggregateNodes.push(aggregateNode);
  });

  const phaseCanvasEdges: GraphCanvasEdgeInfo[] = phaseOneCanvasEdges.filter(
    (edge) =>
      !hiddenPhaseTwoCanvasEdgeKeys.has(
        `${edge.sourceNodeKey}->${edge.targetNodeKey}`,
      ),
  );
  triggerSourceAggregateNodes.forEach((aggregateNode) => {
    aggregateNode.connectedNodeKeys.forEach((connectedNodeKey) => {
      phaseCanvasEdges.push({
        edgeKey: `aggregate-edge:${aggregateNode.nodeKey}:${connectedNodeKey}`,
        sourceNodeKey: aggregateNode.nodeKey,
        targetNodeKey: connectedNodeKey,
        kind: "aggregate",
        diffState: "base",
      });
    });
  });
  const {
    aggregateNodes,
    visibleCanvasNodeKeys,
  } = resolveCanvasAggregateVisibility(
    [...coreAggregateNodes, ...triggerSourceAggregateNodes],
    phaseCanvasEdges,
    forcedVisibleNodeKeys,
    aggregateAutoExpandNodeKeys,
  );

  const visibleActualNodeKeys = new Set<string>();
  const canvasNodes: GraphCanvasNodeInfo[] = [
    ...actualCanvasNodes.filter((node) => visibleCanvasNodeKeys.has(node.nodeKey)),
    ...aggregateNodes.filter((aggregateNode) =>
      visibleCanvasNodeKeys.has(aggregateNode.nodeKey),
    ),
  ].sort(compareCanvasNodeIdentity);
  canvasNodes.forEach((node) => {
    if (node.kind === "actual") {
      visibleActualNodeKeys.add(node.nodeKey);
    }
  });

  const canvasNodeKeySet = new Set(canvasNodes.map((node) => node.nodeKey));
  const canvasEdges: GraphCanvasEdgeInfo[] = phaseCanvasEdges.filter(
    (edge) =>
      canvasNodeKeySet.has(edge.sourceNodeKey) &&
      canvasNodeKeySet.has(edge.targetNodeKey),
  );
  const layoutEdges = [...canvasEdges];
  aggregateNodes.forEach((aggregateNode) => {
    if (
      !aggregateNode.expanded ||
      !canvasNodeKeySet.has(aggregateNode.nodeKey)
    ) {
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
        kind: "aggregate",
        diffState: "base",
      });
    });
  });

  return {
    displayMode: "serial",
    canvasNodes,
    canvasEdges,
    layoutEdges,
    hiddenActualEdgeKeys,
    visibleActualNodeKeys,
    isolatedTriggerSourceNodes: serialGraphBundle.nodes
      .filter(
        (node) =>
          node.type === "triggerSource" &&
          (originalEdgeCountByNodeKey.get(node.nodeKey) ?? 0) === 0,
      )
      .sort(compareGraphNodeIdentity),
    isolatedCoreNodes: serialGraphBundle.nodes
      .filter(
        (node) =>
          node.type === "core" &&
          (originalEdgeCountByNodeKey.get(node.nodeKey) ?? 0) === 0,
      )
      .sort(compareGraphNodeIdentity),
    aggregateNodes: canvasNodes.filter(
      (node): node is GraphCanvasAggregateNode => node.kind === "aggregate",
    ),
    channelHubNodes: [],
  };
}

function buildChannelGraphCanvasView(
  effectiveGraphBundle: GraphSnapshotBundle,
  forcedVisibleNodeKeys: Set<string>,
  expandedAggregateNodeKeys: Set<string>,
  aggregateAutoExpandNodeKeys: Set<string> = forcedVisibleNodeKeys,
): GraphCanvasView {
  const channelMemberNodes = effectiveGraphBundle.nodes
    .filter((node) => node.connectionMode === "channel" && node.channel > 0)
    .sort((left, right) => {
      if (left.channel !== right.channel) {
        return left.channel - right.channel;
      }
      return compareGraphNodeIdentity(left, right);
    });
  const channelGroups = new Map<
    number,
    {
      triggerSources: GraphNodeInfo[];
      cores: GraphNodeInfo[];
    }
  >();
  channelMemberNodes.forEach((node) => {
    const currentGroup = channelGroups.get(node.channel) ?? {
      triggerSources: [],
      cores: [],
    };
    if (node.type === "triggerSource") {
      currentGroup.triggerSources.push(node);
    } else {
      currentGroup.cores.push(node);
    }
    channelGroups.set(node.channel, currentGroup);
  });

  const channelHubNodes: GraphCanvasChannelHubNode[] = Array.from(
    channelGroups.entries(),
  )
    .sort(([leftChannel], [rightChannel]) => leftChannel - rightChannel)
    .map(([channel, group]) => {
      const sortedTriggerSources = [...group.triggerSources].sort(
        compareGraphNodeIdentity,
      );
      const sortedCores = [...group.cores].sort(compareGraphNodeIdentity);
      const memberNodeKeys = [
        ...sortedTriggerSources.map((node) => node.nodeKey),
        ...sortedCores.map((node) => node.nodeKey),
      ];
      return {
        kind: "channelHub",
        nodeKey: buildChannelHubNodeKey(channel),
        channel,
        sourceNodeKeys: sortedTriggerSources.map((node) => node.nodeKey),
        sourceSerials: sortedTriggerSources.map((node) => node.serial),
        coreNodeKeys: sortedCores.map((node) => node.nodeKey),
        coreSerials: sortedCores.map((node) => node.serial),
        memberNodeKeys,
        memberCount: memberNodeKeys.length,
      };
    });

  const actualNodeByKey = new Map(
    channelMemberNodes.map((node) => [node.nodeKey, node] as const),
  );
  const actualCanvasNodes: GraphCanvasNodeInfo[] = channelMemberNodes.map(
    (node) => ({
      kind: "actual",
      nodeKey: node.nodeKey,
      graphNode: node,
    }),
  );
  const baseCanvasEdges: GraphCanvasEdgeInfo[] = [];
  channelHubNodes.forEach((channelHubNode) => {
    channelHubNode.sourceNodeKeys.forEach((sourceNodeKey) => {
      baseCanvasEdges.push({
        edgeKey: buildChannelCanvasEdgeKey(
          sourceNodeKey,
          channelHubNode.nodeKey,
        ),
        sourceNodeKey,
        targetNodeKey: channelHubNode.nodeKey,
        kind: "channel",
        diffState: "base",
      });
    });
    channelHubNode.coreNodeKeys.forEach((coreNodeKey) => {
      baseCanvasEdges.push({
        edgeKey: buildChannelCanvasEdgeKey(
          channelHubNode.nodeKey,
          coreNodeKey,
        ),
        sourceNodeKey: channelHubNode.nodeKey,
        targetNodeKey: coreNodeKey,
        kind: "channel",
        diffState: "base",
      });
    });
  });

  const aggregateNodes: GraphCanvasAggregateNode[] = [];
  const hiddenActualEdgeKeys = new Set<string>();

  channelHubNodes.forEach((channelHubNode) => {
    const coreNodes = channelHubNode.coreNodeKeys
      .map((nodeKey) => actualNodeByKey.get(nodeKey) ?? null)
      .filter((node): node is GraphNodeInfo => node != null)
      .sort(compareGraphNodeIdentity);
    // 频道模式允许“单侧成员”直接折叠成聚合块；
    // 只要本侧成员数达到门槛，就仍然通过 channelHub 挂载该聚合块。
    if (coreNodes.length < SHARED_CORE_GROUP_MIN_CORE_COUNT) {
      return;
    }
    const sourceNodes = channelHubNode.sourceNodeKeys
      .map((nodeKey) => actualNodeByKey.get(nodeKey) ?? null)
      .filter((node): node is GraphNodeInfo => node != null)
      .sort(compareGraphNodeIdentity);
    const aggregateNodeKey = buildAggregateNodeKey(
      "core",
      channelHubNode.nodeKey,
    );
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      hasAggregateAutoExpandMember(
        coreNodes.map((coreNode) => coreNode.nodeKey),
        aggregateAutoExpandNodeKeys,
      );
    aggregateNodes.push({
      kind: "aggregate",
      aggregateRole: "core",
      nodeKey: aggregateNodeKey,
      signatureKey: channelHubNode.nodeKey,
      sourceNodeKeys: sourceNodes.map((node) => node.nodeKey),
      sourceSerials: sourceNodes.map((node) => node.serial),
      coreNodeKeys: coreNodes.map((node) => node.nodeKey),
      coreSerials: coreNodes.map((node) => node.serial),
      memberNodeKeys: coreNodes.map((node) => node.nodeKey),
      memberSerials: coreNodes.map((node) => node.serial),
      connectedNodeKeys: [channelHubNode.nodeKey],
      connectedSerials: sourceNodes.map((node) => node.serial),
      memberCount: coreNodes.length,
      anchorSerial: coreNodes[0]?.serial ?? 0,
      expanded,
    });
    coreNodes.forEach((coreNode) => {
      hiddenActualEdgeKeys.add(
        buildChannelCanvasEdgeKey(channelHubNode.nodeKey, coreNode.nodeKey),
      );
    });
  });

  channelHubNodes.forEach((channelHubNode) => {
    const sourceNodes = channelHubNode.sourceNodeKeys
      .map((nodeKey) => actualNodeByKey.get(nodeKey) ?? null)
      .filter((node): node is GraphNodeInfo => node != null)
      .sort(compareGraphNodeIdentity);
    if (sourceNodes.length < SHARED_TRIGGER_SOURCE_GROUP_MIN_SOURCE_COUNT) {
      return;
    }
    const coreNodes = channelHubNode.coreNodeKeys
      .map((nodeKey) => actualNodeByKey.get(nodeKey) ?? null)
      .filter((node): node is GraphNodeInfo => node != null)
      .sort(compareGraphNodeIdentity);
    const aggregateNodeKey = buildAggregateNodeKey(
      "triggerSource",
      channelHubNode.nodeKey,
    );
    const expanded =
      expandedAggregateNodeKeys.has(aggregateNodeKey) ||
      hasAggregateAutoExpandMember(
        sourceNodes.map((sourceNode) => sourceNode.nodeKey),
        aggregateAutoExpandNodeKeys,
      );
    aggregateNodes.push({
      kind: "aggregate",
      aggregateRole: "triggerSource",
      nodeKey: aggregateNodeKey,
      signatureKey: channelHubNode.nodeKey,
      sourceNodeKeys: sourceNodes.map((node) => node.nodeKey),
      sourceSerials: sourceNodes.map((node) => node.serial),
      coreNodeKeys: coreNodes.map((node) => node.nodeKey),
      coreSerials: coreNodes.map((node) => node.serial),
      memberNodeKeys: sourceNodes.map((node) => node.nodeKey),
      memberSerials: sourceNodes.map((node) => node.serial),
      connectedNodeKeys: [channelHubNode.nodeKey],
      connectedSerials: coreNodes.map((node) => node.serial),
      memberCount: sourceNodes.length,
      anchorSerial: sourceNodes[0]?.serial ?? 0,
      expanded,
    });
    sourceNodes.forEach((sourceNode) => {
      hiddenActualEdgeKeys.add(
        buildChannelCanvasEdgeKey(sourceNode.nodeKey, channelHubNode.nodeKey),
      );
    });
  });

  const phaseCanvasEdges = baseCanvasEdges.filter(
    (edge) => !hiddenActualEdgeKeys.has(edge.edgeKey),
  );
  aggregateNodes.forEach((aggregateNode) => {
    if (aggregateNode.aggregateRole === "core") {
      aggregateNode.connectedNodeKeys.forEach((connectedNodeKey) => {
        phaseCanvasEdges.push({
          edgeKey: `aggregate-edge:${connectedNodeKey}:${aggregateNode.nodeKey}`,
          sourceNodeKey: connectedNodeKey,
          targetNodeKey: aggregateNode.nodeKey,
          kind: "aggregate",
          diffState: "base",
        });
      });
      return;
    }
    aggregateNode.connectedNodeKeys.forEach((connectedNodeKey) => {
      phaseCanvasEdges.push({
        edgeKey: `aggregate-edge:${aggregateNode.nodeKey}:${connectedNodeKey}`,
        sourceNodeKey: aggregateNode.nodeKey,
        targetNodeKey: connectedNodeKey,
        kind: "aggregate",
        diffState: "base",
      });
    });
  });

  const {
    aggregateNodes: resolvedAggregateNodes,
    visibleCanvasNodeKeys,
  } = resolveCanvasAggregateVisibility(
    aggregateNodes,
    phaseCanvasEdges,
    forcedVisibleNodeKeys,
    aggregateAutoExpandNodeKeys,
    false,
  );

  const visibleActualNodeKeys = new Set<string>();
  const canvasNodes: GraphCanvasNodeInfo[] = [
    ...actualCanvasNodes.filter(
      (node) => visibleCanvasNodeKeys.has(node.nodeKey),
    ),
    ...channelHubNodes.filter(
      (node) => visibleCanvasNodeKeys.has(node.nodeKey),
    ),
    ...resolvedAggregateNodes.filter((node) =>
      visibleCanvasNodeKeys.has(node.nodeKey),
    ),
  ].sort(compareCanvasNodeIdentity);
  canvasNodes.forEach((node) => {
    if (node.kind === "actual") {
      visibleActualNodeKeys.add(node.nodeKey);
    }
  });

  const canvasNodeKeySet = new Set(canvasNodes.map((node) => node.nodeKey));
  const canvasEdges = phaseCanvasEdges.filter(
    (edge) =>
      canvasNodeKeySet.has(edge.sourceNodeKey) &&
      canvasNodeKeySet.has(edge.targetNodeKey),
  );
  const layoutEdges = [...canvasEdges];
  resolvedAggregateNodes.forEach((aggregateNode) => {
    if (
      !aggregateNode.expanded ||
      !canvasNodeKeySet.has(aggregateNode.nodeKey)
    ) {
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
        kind: "aggregate",
        diffState: "base",
      });
    });
  });

  return {
    displayMode: "channel",
    canvasNodes,
    canvasEdges,
    layoutEdges,
    hiddenActualEdgeKeys,
    visibleActualNodeKeys,
    isolatedTriggerSourceNodes: [],
    isolatedCoreNodes: [],
    aggregateNodes: canvasNodes.filter(
      (node): node is GraphCanvasAggregateNode => node.kind === "aggregate",
    ),
    channelHubNodes: canvasNodes.filter(
      (node): node is GraphCanvasChannelHubNode => node.kind === "channelHub",
    ),
  };
}

export function buildGraphCanvasView(
  effectiveGraphBundle: GraphSnapshotBundle,
  forcedVisibleNodeKeys: Set<string>,
  expandedAggregateNodeKeys: Set<string>,
  displayMode: GraphDisplayMode,
  aggregateAutoExpandNodeKeys: Set<string> = forcedVisibleNodeKeys,
): GraphCanvasView {
  return displayMode === "channel"
    ? buildChannelGraphCanvasView(
        effectiveGraphBundle,
        forcedVisibleNodeKeys,
        expandedAggregateNodeKeys,
        aggregateAutoExpandNodeKeys,
      )
    : buildSerialGraphCanvasView(
        effectiveGraphBundle,
        forcedVisibleNodeKeys,
        expandedAggregateNodeKeys,
        aggregateAutoExpandNodeKeys,
      );
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
      const neighborNodeKeys = Array.from(
        adjacency.get(currentNodeKey) ?? [],
      ).sort();
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

function resolveLaneJitter(
  nodeKey: string,
  columnIndex: number,
  rowIndex: number,
): XYPosition {
  const hash = hashLayoutKey(`${nodeKey}:${columnIndex}:${rowIndex}`);
  const driftX = (hash % (LANE_JITTER_X * 2 + 1)) - LANE_JITTER_X;
  const driftY = ((hash >>> 8) % (LANE_JITTER_Y * 2 + 1)) - LANE_JITTER_Y;
  const columnStaggerY = columnIndex % 2 === 0 ? 0 : LANE_COLUMN_STAGGER_Y;
  const rowBiasY = rowIndex % 2 === 0 ? -4 : 6;
  return {
    x: driftX,
    y: driftY + columnStaggerY + rowBiasY,
  };
}

function normalizeLayoutPositions(
  rawPositions: Map<string, XYPosition>,
): Pick<GraphLayoutComponent, "positions" | "width" | "height"> {
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

function resolveComponentOffset(
  nodeKey: string,
  componentIndex: number,
): XYPosition {
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
      connectedOrders.reduce(
        (totalOrder, currentOrder) => totalOrder + currentOrder,
        0,
      ) / connectedOrders.length,
    minimumOrder: Math.min(...connectedOrders),
  };
}

function buildComponentLayout(
  componentNodes: GraphCanvasNodeInfo[],
  componentEdges: GraphCanvasEdgeInfo[],
): GraphLayoutComponent {
  const hasExpandedAggregateNode = componentNodes.some(
    (node) => node.kind === "aggregate" && node.expanded,
  );
  const compareChannelNodeOrder = (
    left: GraphCanvasNodeInfo,
    right: GraphCanvasNodeInfo,
  ): number => {
    const leftChannel =
      left.kind === "actual"
        ? left.graphNode.channel
        : left.kind === "channelHub"
          ? left.channel
          : 0;
    const rightChannel =
      right.kind === "actual"
        ? right.graphNode.channel
        : right.kind === "channelHub"
          ? right.channel
          : 0;
    if (leftChannel !== rightChannel) {
      return leftChannel - rightChannel;
    }
    return compareCanvasNodeIdentity(left, right);
  };
  const triggerSourceNodes = componentNodes
    .filter((node) => resolveCanvasNodeLaneType(node) === "triggerSource")
    .sort(compareCanvasNodeIdentity);
  const channelHubNodes = componentNodes
    .filter(
      (node): node is GraphCanvasChannelHubNode => node.kind === "channelHub",
    )
    .sort(compareChannelNodeOrder);
  if (channelHubNodes.length > 0) {
    const coreNodes = componentNodes
      .filter((node) => resolveCanvasNodeLaneType(node) === "core")
      .sort(compareChannelNodeOrder);
    const laneLayouts = [
      {
        layout: buildLaneLayout(triggerSourceNodes, false),
        nodes: triggerSourceNodes,
      },
      {
        layout: buildLaneLayout(channelHubNodes, false),
        nodes: channelHubNodes,
      },
      {
        layout: buildLaneLayout(coreNodes, false),
        nodes: coreNodes,
      },
    ].filter((lane) => lane.nodes.length > 0);
    const laneHeight = Math.max(
      ...laneLayouts.map((lane) => lane.layout.height),
      GRAPH_NODE_HEIGHT,
    );
    const positions = new Map<string, XYPosition>();
    let currentX = 0;
    laneLayouts.forEach((lane, laneIndex) => {
      const offsetY =
        lane.layout.height === 0 ? 0 : (laneHeight - lane.layout.height) / 2;
      lane.layout.positions.forEach((position, nodeKey) => {
        positions.set(nodeKey, {
          x: currentX + position.x,
          y: offsetY + position.y,
        });
      });
      currentX += lane.layout.width;
      if (laneIndex < laneLayouts.length - 1) {
        currentX += COMPONENT_LAYER_GAP_X;
      }
    });
    return {
      width: Math.max(currentX, GRAPH_NODE_WIDTH),
      height: laneHeight,
      positions,
      anchorNode: componentNodes[0] ?? null,
    };
  }
  const sourceOrderByNodeKey = new Map(
    triggerSourceNodes.map((node, index) => [node.nodeKey, index] as const),
  );
  const connectedSourceOrdersByNodeKey = new Map<string, number[]>();
  componentEdges.forEach((edge) => {
    const directSourceOrder = sourceOrderByNodeKey.get(edge.sourceNodeKey);
    const inheritedSourceOrders =
      directSourceOrder == null
        ? (connectedSourceOrdersByNodeKey.get(edge.sourceNodeKey) ?? [])
        : [];
    const nextSourceOrders =
      directSourceOrder == null
        ? inheritedSourceOrders
        : [directSourceOrder, ...inheritedSourceOrders];
    if (nextSourceOrders.length === 0) {
      return;
    }
    const currentOrders =
      connectedSourceOrdersByNodeKey.get(edge.targetNodeKey) ?? [];
    connectedSourceOrdersByNodeKey.set(edge.targetNodeKey, [
      ...currentOrders,
      ...nextSourceOrders,
    ]);
  });
  const coreNodes = componentNodes
    .filter((node) => resolveCanvasNodeLaneType(node) === "core")
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
  const triggerSourceLaneLayout = buildLaneLayout(
    triggerSourceNodes,
    !hasExpandedAggregateNode,
  );
  const coreLaneLayout = buildLaneLayout(coreNodes, !hasExpandedAggregateNode);
  const laneHeight = Math.max(
    triggerSourceLaneLayout.height,
    coreLaneLayout.height,
    GRAPH_NODE_HEIGHT,
  );
  const triggerSourceOffsetY =
    triggerSourceLaneLayout.height === 0
      ? 0
      : (laneHeight - triggerSourceLaneLayout.height) / 2;
  const coreOffsetY =
    coreLaneLayout.height === 0 ? 0 : (laneHeight - coreLaneLayout.height) / 2;
  const hasDualLayer = triggerSourceNodes.length > 0 && coreNodes.length > 0;
  const coreX = hasDualLayer
    ? triggerSourceLaneLayout.width + COMPONENT_LAYER_GAP_X
    : 0;
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
      ? triggerSourceLaneLayout.width +
        COMPONENT_LAYER_GAP_X +
        coreLaneLayout.width
      : Math.max(
          triggerSourceLaneLayout.width,
          coreLaneLayout.width,
          GRAPH_NODE_WIDTH,
        ),
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
      const componentNodeKeys = new Set(
        componentNodes.map((node) => node.nodeKey),
      );
      const componentEdges = layoutEdges.filter(
        (edge) =>
          componentNodeKeys.has(edge.sourceNodeKey) &&
          componentNodeKeys.has(edge.targetNodeKey),
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
      currentRowHeight = Math.max(
        currentRowHeight,
        component.height + componentOffset.y,
      );
    });

  return positionByNodeKey;
}

export function buildGraphFlowNodes(
  canvasNodes: GraphCanvasNodeInfo[],
  positionByNodeKey: Map<string, XYPosition>,
  language: AppLanguage,
  editMode: GraphEditMode,
  selectedNodeKey: string,
  hasSearch: boolean,
  matchedNodeKeys: Set<string>,
  selectedEditSourceNodeKeys: Set<string>,
  selectedEditTargetNodeKeys: Set<string>,
  draftChangedCanvasNodeKeys: Set<string>,
): GraphFlowNode[] {
  return canvasNodes.map((canvasNode) => {
    const nodeKey = canvasNode.nodeKey;
    return {
      id: nodeKey,
      position: positionByNodeKey.get(nodeKey) ?? {
        x: AUTO_LAYOUT_START_X,
        y: AUTO_LAYOUT_START_Y,
      },
      data: {
        label:
          canvasNode.kind === "aggregate"
            ? buildAggregateNodeLabel(canvasNode, language)
            : canvasNode.kind === "channelHub"
              ? buildChannelHubNodeLabel(canvasNode, language)
              : buildNodeLabel(canvasNode.graphNode),
        canvasNodeKey: nodeKey,
      },
      draggable: true,
      selectable: canvasNode.kind === "actual" && editMode !== "view",
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      style:
        canvasNode.kind === "aggregate"
          ? buildAggregateNodeStyle(
              canvasNode,
              selectedNodeKey,
              hasSearch,
              matchedNodeKeys,
              draftChangedCanvasNodeKeys,
            )
          : canvasNode.kind === "channelHub"
            ? buildChannelHubNodeStyle(
                canvasNode,
                selectedNodeKey,
                hasSearch,
                matchedNodeKeys,
                draftChangedCanvasNodeKeys,
              )
            : buildNodeStyle(
                canvasNode.graphNode,
                selectedNodeKey,
                hasSearch,
                matchedNodeKeys,
                selectedEditSourceNodeKeys,
                selectedEditTargetNodeKeys,
                draftChangedCanvasNodeKeys,
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
    if (edge.kind === "aggregate") {
      return buildAggregateEdgeStyle(hasSearch, matchedNodeKeys, baseEdge);
    }
    if (edge.kind === "channel") {
      return buildChannelEdgeStyle(hasSearch, matchedNodeKeys, baseEdge);
    }
    return buildEdgeStyle(
      hasSearch,
      matchedNodeKeys,
      baseEdge,
      draftDiff.addedEdgeKeys.has(edge.edgeKey) ? "added" : "base",
    );
  });
  if (graphCanvasView.displayMode === "serial") {
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
            "removed",
          ),
        );
      });
  }
  return edges;
}
