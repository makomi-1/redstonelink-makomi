import type { ReactNode } from "react";
import type { Node, XYPosition } from "reactflow";
import type {
  GraphNodeInfo,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
} from "../../graphTypes";

export const GRAPH_NODE_WIDTH = 232;
export const GRAPH_NODE_HEIGHT = 60;
export const AUTO_LAYOUT_START_X = 72;
export const AUTO_LAYOUT_START_Y = 64;
export const COMPONENT_LAYER_GAP_X = 364;
export const COMPONENT_LANE_COLUMN_GAP_X = 70;
export const COMPONENT_NODE_GAP_Y = 104;
export const COMPONENT_BLOCK_GAP_X = 156;
export const COMPONENT_BLOCK_GAP_Y = 172;
export const AUTO_LAYOUT_MAX_ROW_WIDTH = 1960;
export const COMPONENT_LANE_MIN_ROW_COUNT = 3;
export const COMPONENT_LANE_MAX_ROW_COUNT = 7;
export const LANE_JITTER_X = 18;
export const LANE_JITTER_Y = 14;
export const LANE_COLUMN_STAGGER_Y = 14;
export const COMPONENT_STAGGER_X = 18;
export const COMPONENT_STAGGER_Y = 22;
export const SHARED_CORE_GROUP_MIN_SOURCE_COUNT = 1;
export const SHARED_CORE_GROUP_MIN_CORE_COUNT = 2;
export const SHARED_TRIGGER_SOURCE_GROUP_MIN_TARGET_COUNT = 1;
export const SHARED_TRIGGER_SOURCE_GROUP_MIN_SOURCE_COUNT = 2;
export const NODE_CENTER_OFFSET_X = GRAPH_NODE_WIDTH / 2;
export const NODE_CENTER_OFFSET_Y = GRAPH_NODE_HEIGHT / 2;
export const AGGREGATE_OUTLINE_PADDING_X = 28;
export const AGGREGATE_OUTLINE_PADDING_Y = 28;

export type GraphFlowNodeData = {
  label?: ReactNode;
  canvasNodeKey: string;
};

export type GraphFlowNode = Node<GraphFlowNodeData>;
export type SavePhase = "idle" | "saving" | "conflict" | "error";
export type GraphEditMode = "view" | "add" | "remove" | "replace";
export type GraphDisplayMode = "serial" | "channel";
export type GraphDisplayContentMode = "topology";
export type GraphSearchTypeFilter = "all" | GraphNodeTypeToken;
export type GraphSidebarPanel = "details" | "isolated" | "crossMode" | "batch";
export type DraftEdgeDiffState = "base" | "added" | "removed";

export type GraphCanvasActualNode = {
  kind: "actual";
  nodeKey: string;
  graphNode: GraphNodeInfo;
};

export type GraphCanvasRepeaterNode = {
  kind: "repeater";
  nodeKey: string;
  serial: number;
  displayText: string;
  triggerSourceNodeKey: string;
  coreNodeKey: string;
  memberNodeKeys: string[];
  inputNodeKeys: string[];
  inputSerials: number[];
  outputNodeKeys: string[];
  outputSerials: number[];
  connectedNodeKeys: string[];
  expanded: boolean;
};

export type GraphAggregateRole = "core" | "triggerSource";

export type GraphCanvasAggregateNode = {
  kind: "aggregate";
  aggregateRole: GraphAggregateRole;
  nodeKey: string;
  signatureKey: string;
  sourceNodeKeys: string[];
  sourceSerials: number[];
  coreNodeKeys: string[];
  coreSerials: number[];
  memberNodeKeys: string[];
  memberSerials: number[];
  connectedNodeKeys: string[];
  connectedSerials: number[];
  memberCount: number;
  anchorSerial: number;
  expanded: boolean;
};

export type GraphCanvasChannelHubNode = {
  kind: "channelHub";
  nodeKey: string;
  channel: number;
  sourceNodeKeys: string[];
  sourceSerials: number[];
  coreNodeKeys: string[];
  coreSerials: number[];
  memberNodeKeys: string[];
  memberCount: number;
};

export type GraphCanvasNodeInfo =
  | GraphCanvasActualNode
  | GraphCanvasRepeaterNode
  | GraphCanvasAggregateNode
  | GraphCanvasChannelHubNode;

export type GraphCanvasEdgeInfo = {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  kind: "actual" | "aggregate" | "channel";
  diffState: DraftEdgeDiffState;
};

export type GraphLayoutComponent = {
  width: number;
  height: number;
  positions: Map<string, XYPosition>;
  anchorNode: GraphCanvasNodeInfo | null;
};

export type GraphCanvasView = {
  displayMode: GraphDisplayMode;
  canvasNodes: GraphCanvasNodeInfo[];
  canvasEdges: GraphCanvasEdgeInfo[];
  layoutEdges: GraphCanvasEdgeInfo[];
  hiddenActualEdgeKeys: Set<string>;
  visibleActualNodeKeys: Set<string>;
  isolatedTriggerSourceNodes: GraphNodeInfo[];
  isolatedCoreNodes: GraphNodeInfo[];
  repeaterNodes: GraphCanvasRepeaterNode[];
  aggregateNodes: GraphCanvasAggregateNode[];
  channelHubNodes: GraphCanvasChannelHubNode[];
};

export type GraphDraftDiff = {
  changedNodeKeys: Set<string>;
  addedEdgeKeys: Set<string>;
  removedEdges: GraphSnapshotBundle["edges"];
};
