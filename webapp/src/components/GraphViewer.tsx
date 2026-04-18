import { useEffect, useMemo, useRef, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  SelectionMode,
  type NodeChange,
  type ReactFlowInstance,
  useEdgesState,
  useNodesState,
} from "reactflow";
import "reactflow/dist/style.css";
import type {
  GraphDraft,
  GraphNodeInfo,
  GraphNodeTypeToken,
  GraphSnapshotBundle,
} from "../graphTypes";
import { createGraphNodeKey, createInitialGraphDraft } from "../graphTypes";
import {
  buildAggregateOutlineFlowNodes,
  buildAutoLayoutPositions,
  buildEdgeCountByNodeKey,
  buildEdges,
  buildGraphCanvasView,
  buildGraphFlowNodes,
  graphNodeTypes,
  sameAggregateOutlineFlowNodes,
} from "./graphViewer/canvas";
import {
  applyBatchEditToDraft,
  applyChannelEditToDraft,
  applyDraftToGraph,
  applyUpdatedNodeStates,
  buildDraftDiff,
  buildDraftFileName,
  formatEditModeInstruction,
  formatEditModeLabel,
  loadDraft,
  persistDraft,
  sameGraphDraft,
  sameNumberArray,
  submitGraphSave,
  upsertAliasDraft,
} from "./graphViewer/draft";
import {
  NODE_CENTER_OFFSET_X,
  NODE_CENTER_OFFSET_Y,
  type GraphCanvasAggregateNode,
  type GraphCanvasChannelHubNode,
  type GraphCanvasNodeInfo,
  type GraphDisplayContentMode,
  type GraphDisplayMode,
  type GraphEditMode,
  type GraphFlowNode,
  type GraphSearchTypeFilter,
  type GraphSidebarPanel,
  type SavePhase,
} from "./graphViewer/types";
import {
  buildSearchResultLabel,
  formatSearchTypeLabel,
  matchesSearch,
  matchesSearchType,
} from "./graphViewer/search";

type GraphViewerProps = {
  graphBundle: GraphSnapshotBundle;
  graphFileName: string;
  onDirtyStateChange?: (dirty: boolean) => void;
};

function formatCanvasNodeDisplayText(canvasNode: GraphCanvasNodeInfo): string {
  if (canvasNode.kind === "actual") {
    return canvasNode.graphNode.displayText;
  }
  if (canvasNode.kind === "channelHub") {
    return `channel #${canvasNode.channel}`;
  }
  return canvasNode.aggregateRole === "core"
    ? `${canvasNode.memberCount} grouped cores`
    : `${canvasNode.memberCount} grouped triggerSources`;
}

function resolveDefaultSelectedNodeKey(
  canvasNodes: GraphCanvasNodeInfo[],
  displayMode: GraphDisplayMode,
): string {
  if (displayMode === "channel") {
    return (
      canvasNodes.find((node) => node.kind === "channelHub")?.nodeKey ??
      canvasNodes.find((node) => node.kind === "aggregate")?.nodeKey ??
      canvasNodes.find((node) => node.kind === "actual")?.nodeKey ??
      ""
    );
  }
  return (
    canvasNodes.find((node) => node.kind === "actual")?.nodeKey ??
    canvasNodes[0]?.nodeKey ??
    ""
  );
}

function parseChannelBatchValue(value: string): number | null {
  if (!value.trim()) {
    return null;
  }
  const parsedValue = Number(value);
  return Number.isInteger(parsedValue) && parsedValue >= 0 ? parsedValue : null;
}

function resolveCurrentTargetSerials(
  graphBundle: GraphSnapshotBundle,
  triggerSourceSerial: number,
): number[] {
  const sourceNodeKey = createGraphNodeKey("triggerSource", triggerSourceSerial);
  return graphBundle.edges
    .filter((edge) => edge.sourceNodeKey === sourceNodeKey)
    .map((edge) => {
      const matched = edge.targetNodeKey.match(/^core:(\d+)$/);
      return matched == null ? 0 : Number(matched[1]);
    })
    .filter((serial) => serial > 0)
    .sort((left, right) => left - right);
}

export default function GraphViewer({
  graphBundle,
  graphFileName,
  onDirtyStateChange,
}: GraphViewerProps) {
  const [baseGraphBundle, setBaseGraphBundle] =
    useState<GraphSnapshotBundle>(graphBundle);
  const [graphDraft, setGraphDraft] = useState<GraphDraft>(() =>
    createInitialGraphDraft(graphBundle),
  );
  const [draftLoading, setDraftLoading] = useState(false);
  const [draftError, setDraftError] = useState("");
  const [draftPersistError, setDraftPersistError] = useState("");
  const [savePhase, setSavePhase] = useState<SavePhase>("idle");
  const [saveMessage, setSaveMessage] = useState("");
  const [displayMode, setDisplayMode] = useState<GraphDisplayMode>("serial");
  const [serialContentMode, setSerialContentMode] =
    useState<GraphDisplayContentMode>("topology");
  const [channelContentMode, setChannelContentMode] =
    useState<GraphDisplayContentMode>("topology");
  const [searchDraftText, setSearchDraftText] = useState("");
  const [appliedSearchText, setAppliedSearchText] = useState("");
  const [searchDraftTypeFilter, setSearchDraftTypeFilter] =
    useState<GraphSearchTypeFilter>("all");
  const [appliedSearchTypeFilter, setAppliedSearchTypeFilter] =
    useState<GraphSearchTypeFilter>("all");
  const [selectedNodeKey, setSelectedNodeKey] = useState<string>("");
  const [activeSidebarPanel, setActiveSidebarPanel] =
    useState<GraphSidebarPanel>("details");
  const [editMode, setEditMode] = useState<GraphEditMode>("view");
  const [selectedEditSourceSerials, setSelectedEditSourceSerials] = useState<
    number[]
  >([]);
  const [selectedEditTargetSerials, setSelectedEditTargetSerials] = useState<
    number[]
  >([]);
  const [selectedEditChannelNodeKeys, setSelectedEditChannelNodeKeys] =
    useState<string[]>([]);
  const [channelBatchDraftValue, setChannelBatchDraftValue] = useState("0");
  const [undoableGraphDraft, setUndoableGraphDraft] =
    useState<GraphDraft | null>(null);
  const [expandedAggregateNodeKeys, setExpandedAggregateNodeKeys] = useState<
    string[]
  >([]);
  const [pinnedIsolatedNodeKeys, setPinnedIsolatedNodeKeys] = useState<
    string[]
  >([]);
  const [pendingAggregateFocusNodeKey, setPendingAggregateFocusNodeKey] =
    useState("");
  const [pendingFocusNodeKey, setPendingFocusNodeKey] = useState("");
  const [pendingStructureLayoutReset, setPendingStructureLayoutReset] =
    useState(false);
  const [pendingStructureViewportFit, setPendingStructureViewportFit] =
    useState(false);
  const [reactFlowInstance, setReactFlowInstance] =
    useState<ReactFlowInstance | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [aggregateOutlineNodes, setAggregateOutlineNodes] = useState<
    GraphFlowNode[]
  >([]);
  const [aggregateOutlineSuspended, setAggregateOutlineSuspended] =
    useState(false);
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
  const activeContentMode =
    displayMode === "serial" ? serialContentMode : channelContentMode;
  const canEditCurrentView = activeContentMode === "topology";
  const nodeByKey = useMemo(
    () =>
      new Map(effectiveGraphBundle.nodes.map((node) => [node.nodeKey, node])),
    [effectiveGraphBundle.nodes],
  );
  const edgeCountByNodeKey = useMemo(
    () => buildEdgeCountByNodeKey(effectiveGraphBundle),
    [effectiveGraphBundle],
  );
  const searchableNodes = useMemo(
    () =>
      displayMode === "channel"
        ? effectiveGraphBundle.nodes.filter(
            (node) => node.connectionMode === "channel" && node.channel > 0,
          )
        : effectiveGraphBundle.nodes,
    [displayMode, effectiveGraphBundle.nodes],
  );
  const hasSearch = appliedSearchText.trim().length > 0;
  const matchedNodes = useMemo(
    () =>
      hasSearch
        ? searchableNodes.filter(
            (node) =>
              matchesSearchType(node, appliedSearchTypeFilter) &&
              matchesSearch(node, appliedSearchText),
          )
        : [],
    [appliedSearchText, appliedSearchTypeFilter, hasSearch, searchableNodes],
  );
  const matchedNodeKeys = useMemo(
    () => new Set(matchedNodes.map((node) => node.nodeKey)),
    [matchedNodes],
  );
  const parsedChannelBatchValue = useMemo(
    () => parseChannelBatchValue(channelBatchDraftValue),
    [channelBatchDraftValue],
  );
  const selectedEditSourceNodeKeys = useMemo(
    () =>
      displayMode === "channel"
        ? new Set(
            selectedEditChannelNodeKeys.filter(
              (nodeKey) => nodeByKey.get(nodeKey)?.type === "triggerSource",
            ),
          )
        : new Set(
            selectedEditSourceSerials.map((serial) =>
              createGraphNodeKey("triggerSource", serial),
            ),
          ),
    [displayMode, nodeByKey, selectedEditChannelNodeKeys, selectedEditSourceSerials],
  );
  const selectedEditTargetNodeKeys = useMemo(
    () =>
      displayMode === "channel"
        ? new Set(
            selectedEditChannelNodeKeys.filter(
              (nodeKey) => nodeByKey.get(nodeKey)?.type === "core",
            ),
          )
        : new Set(
            selectedEditTargetSerials.map((serial) =>
              createGraphNodeKey("core", serial),
            ),
          ),
    [displayMode, nodeByKey, selectedEditChannelNodeKeys, selectedEditTargetSerials],
  );
  const selectedEditSourceNodes = useMemo(
    () =>
      selectedEditSourceSerials
        .map(
          (serial) =>
            nodeByKey.get(createGraphNodeKey("triggerSource", serial)) ?? null,
        )
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedEditSourceSerials],
  );
  const selectedEditTargetNodes = useMemo(
    () =>
      selectedEditTargetSerials
        .map(
          (serial) => nodeByKey.get(createGraphNodeKey("core", serial)) ?? null,
        )
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedEditTargetSerials],
  );
  const selectedEditChannelNodes = useMemo(
    () =>
      selectedEditChannelNodeKeys
        .map((nodeKey) => nodeByKey.get(nodeKey) ?? null)
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedEditChannelNodeKeys],
  );
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
        displayMode,
      ),
    [
      displayMode,
      effectiveGraphBundle,
      expandedAggregateNodeKeySet,
      forcedVisibleNodeKeys,
    ],
  );
  const matchedCanvasNodeKeys = useMemo(() => {
    const nextKeys = new Set(matchedNodeKeys);
    graphCanvasView.channelHubNodes.forEach((channelHubNode) => {
      if (
        channelHubNode.memberNodeKeys.some((nodeKey) =>
          matchedNodeKeys.has(nodeKey),
        )
      ) {
        nextKeys.add(channelHubNode.nodeKey);
      }
    });
    graphCanvasView.aggregateNodes.forEach((aggregateNode) => {
      if (
        aggregateNode.sourceNodeKeys.some((nodeKey) =>
          matchedNodeKeys.has(nodeKey),
        ) ||
        aggregateNode.coreNodeKeys.some((nodeKey) =>
          matchedNodeKeys.has(nodeKey),
        )
      ) {
        nextKeys.add(aggregateNode.nodeKey);
      }
    });
    return nextKeys;
  }, [
    graphCanvasView.aggregateNodes,
    graphCanvasView.channelHubNodes,
    matchedNodeKeys,
  ]);
  const selectedCanvasNode = useMemo(
    () =>
      selectedNodeKey
        ? (graphCanvasView.canvasNodes.find(
            (node) => node.nodeKey === selectedNodeKey,
          ) ?? null)
        : null,
    [graphCanvasView.canvasNodes, selectedNodeKey],
  );
  const canvasNodeByKey = useMemo(
    () =>
      new Map(
        graphCanvasView.canvasNodes.map(
          (node) => [node.nodeKey, node] as const,
        ),
      ),
    [graphCanvasView.canvasNodes],
  );
  const selectedAggregateNode =
    selectedCanvasNode?.kind === "aggregate" ? selectedCanvasNode : null;
  const selectedNode =
    selectedCanvasNode?.kind === "actual" ? selectedCanvasNode.graphNode : null;
  const selectedTriggerSourceTargets =
    canEditCurrentView && selectedNode?.type === "triggerSource"
      ? resolveCurrentTargetSerials(effectiveGraphBundle, selectedNode.serial)
      : [];
  const selectedTriggerSourceTargetNodes = selectedTriggerSourceTargets
    .map((serial) => nodeByKey.get(createGraphNodeKey("core", serial)) ?? null)
    .filter((node): node is GraphNodeInfo => node != null);
  const channelHubByChannel = useMemo(
    () =>
      new Map(
        graphCanvasView.channelHubNodes.map(
          (node) => [node.channel, node] as const,
        ),
      ),
    [graphCanvasView.channelHubNodes],
  );
  const selectedChannelHubNode = useMemo(() => {
    if (selectedCanvasNode?.kind === "channelHub") {
      return selectedCanvasNode;
    }
    if (
      displayMode !== "channel" ||
      selectedNode == null ||
      selectedNode.connectionMode !== "channel" ||
      selectedNode.channel <= 0
    ) {
      return null;
    }
    return channelHubByChannel.get(selectedNode.channel) ?? null;
  }, [channelHubByChannel, displayMode, selectedCanvasNode, selectedNode]);
  const selectedChannelTriggerSourceNodes = useMemo(
    () =>
      (selectedChannelHubNode?.sourceNodeKeys ?? [])
        .map((nodeKey) => nodeByKey.get(nodeKey) ?? null)
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedChannelHubNode],
  );
  const selectedChannelCoreNodes = useMemo(
    () =>
      (selectedChannelHubNode?.coreNodeKeys ?? [])
        .map((nodeKey) => nodeByKey.get(nodeKey) ?? null)
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedChannelHubNode],
  );
  const selectedAggregateConnectedCanvasNodes = useMemo(
    () =>
      (selectedAggregateNode?.connectedNodeKeys ?? [])
        .map((nodeKey) => canvasNodeByKey.get(nodeKey) ?? null)
        .filter((node): node is GraphCanvasNodeInfo => node != null),
    [canvasNodeByKey, selectedAggregateNode],
  );
  const selectedAggregateMemberNodes = useMemo(
    () =>
      (selectedAggregateNode?.memberNodeKeys ?? [])
        .map((nodeKey) => nodeByKey.get(nodeKey) ?? null)
        .filter((node): node is GraphNodeInfo => node != null),
    [nodeByKey, selectedAggregateNode],
  );
  const autoLayoutPositions = useMemo(
    () =>
      buildAutoLayoutPositions(
        graphCanvasView.canvasNodes,
        graphCanvasView.layoutEdges,
      ),
    [graphCanvasView.canvasNodes, graphCanvasView.layoutEdges],
  );
  const pinnedIsolatedNodeCount = useMemo(
    () =>
      pinnedIsolatedNodeKeys.filter(
        (nodeKey) => (edgeCountByNodeKey.get(nodeKey) ?? 0) === 0,
      ).length,
    [edgeCountByNodeKey, pinnedIsolatedNodeKeys],
  );
  const hasCanvasNodes = graphCanvasView.canvasNodes.length > 0;
  const displayNodes = useMemo(
    () =>
      aggregateOutlineSuspended ? nodes : [...aggregateOutlineNodes, ...nodes],
    [aggregateOutlineNodes, aggregateOutlineSuspended, nodes],
  );
  const availableSidebarPanels = useMemo<[GraphSidebarPanel, string][]>(
    () =>
      displayMode === "serial"
        ? [
            ["details", "详情"],
            ["isolated", "孤立节点池"],
            ["batch", "批量编辑"],
          ]
        : [
            ["details", "详情"],
            ["batch", "批量编辑"],
          ],
    [displayMode],
  );
  const hasPendingSearchChanges =
    searchDraftText !== appliedSearchText ||
    searchDraftTypeFilter !== appliedSearchTypeFilter;
  const canApplyBatchEdit =
    displayMode === "channel"
      ? canEditCurrentView &&
        editMode === "replace" &&
        selectedEditChannelNodeKeys.length > 0 &&
        parsedChannelBatchValue != null
      : canEditCurrentView && editMode === "replace"
        ? selectedEditSourceSerials.length > 0
        : canEditCurrentView &&
          editMode !== "view" &&
          selectedEditSourceSerials.length > 0 &&
          selectedEditTargetSerials.length > 0;
  const statusClassName =
    savePhase === "saving"
      ? "status-pill is-waiting"
      : savePhase === "conflict" || savePhase === "error"
        ? "status-pill is-error"
        : graphDraft.dirty
          ? "status-pill is-waiting"
          : "status-pill is-ready";
  const statusText =
    savePhase === "saving"
      ? "保存中"
      : savePhase === "conflict"
        ? "保存冲突"
        : savePhase === "error"
          ? "保存失败"
          : graphDraft.dirty
            ? "未保存"
            : "已同步";
  const canvasSectionTag =
    displayMode === "serial" ? "Serial View" : "Channel View";
  const canvasTitle =
    displayMode === "serial" ? "显式保存拓扑图" : "频道两级拓扑图";
  const graphModeLabel = displayMode === "serial" ? "序号模式" : "频道模式";
  const activeContentLabel =
    activeContentMode === "topology" ? "拓扑" : activeContentMode;
  const selectionEnabled = canEditCurrentView && editMode !== "view";
  const availableEditModes =
    displayMode === "serial"
      ? (["view", "add", "remove", "replace"] as GraphEditMode[])
      : (["view", "replace"] as GraphEditMode[]);

  useEffect(() => {
    let disposed = false;
    setDraftLoading(true);
    setDraftError("");
    setSavePhase("idle");
    setSaveMessage("");
    setDisplayMode("serial");
    setSerialContentMode("topology");
    setChannelContentMode("topology");
    setActiveSidebarPanel("details");
    setEditMode("view");
    setSelectedEditSourceSerials([]);
    setSelectedEditTargetSerials([]);
    setSelectedEditChannelNodeKeys([]);
    setChannelBatchDraftValue("0");
    setUndoableGraphDraft(null);
    setExpandedAggregateNodeKeys([]);
    setPinnedIsolatedNodeKeys([]);
    setPendingAggregateFocusNodeKey("");
    setPendingFocusNodeKey("");
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
        if (
          loadedDraft &&
          loadedDraft.baseSnapshotId === graphBundle.snapshotId
        ) {
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
        setDraftError(error instanceof Error ? error.message : "unknown error");
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
    if (
      availableSidebarPanels.some(
        ([panelKey]) => panelKey === activeSidebarPanel,
      )
    ) {
      return;
    }
    setActiveSidebarPanel("details");
  }, [activeSidebarPanel, availableSidebarPanels]);

  useEffect(() => {
    if (draftLoading) {
      return;
    }
    const initialNodeKey = resolveDefaultSelectedNodeKey(
      graphCanvasView.canvasNodes,
      displayMode,
    );
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
    if (draftLoading) {
      return;
    }
    const canvasNodeKeySet = new Set(
      graphCanvasView.canvasNodes.map((node) => node.nodeKey),
    );
    if (selectedNodeKey && canvasNodeKeySet.has(selectedNodeKey)) {
      return;
    }
    const nextSelectedNodeKey = resolveDefaultSelectedNodeKey(
      graphCanvasView.canvasNodes,
      displayMode,
    );
    if (selectedNodeKey !== nextSelectedNodeKey) {
      setSelectedNodeKey(nextSelectedNodeKey);
    }
  }, [displayMode, draftLoading, graphCanvasView.canvasNodes, selectedNodeKey]);

  useEffect(() => {
    if (draftLoading || aggregateOutlineSuspended) {
      return;
    }
    const nextOutlineNodes = buildAggregateOutlineFlowNodes(
      graphCanvasView.aggregateNodes,
      nodes,
    );
    setAggregateOutlineNodes((currentNodes) =>
      sameAggregateOutlineFlowNodes(currentNodes, nextOutlineNodes)
        ? currentNodes
        : nextOutlineNodes,
    );
  }, [
    aggregateOutlineSuspended,
    draftLoading,
    graphCanvasView.aggregateNodes,
    nodes,
  ]);

  useEffect(() => {
    const shouldResetPositions = pendingStructureLayoutReset;
    setNodes((currentNodes) => {
      const existingPositionByNodeKey = new Map(
        shouldResetPositions
          ? []
          : currentNodes.map((node) => [node.id, node.position] as const),
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
        matchedCanvasNodeKeys,
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
    matchedCanvasNodeKeys,
    matchedNodeKeys,
    pendingStructureLayoutReset,
    selectedNodeKey,
    selectedEditSourceNodeKeys,
    selectedEditTargetNodeKeys,
    setEdges,
    setNodes,
  ]);

  useEffect(() => {
    if (
      draftLoading ||
      !reactFlowInstance ||
      graphCanvasView.canvasNodes.length === 0
    ) {
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
        aggregateNode.nodeKey === pendingAggregateFocusNodeKey &&
        aggregateNode.expanded,
    );
    if (!expandedAggregateNode) {
      setPendingAggregateFocusNodeKey("");
      return;
    }
    const targetNodeIds = [
      expandedAggregateNode.nodeKey,
      ...expandedAggregateNode.memberNodeKeys,
    ].filter((nodeId) => nodes.some((node) => node.id === nodeId));
    if (targetNodeIds.length === 0) {
      setPendingAggregateFocusNodeKey("");
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
      setPendingAggregateFocusNodeKey("");
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
    setPendingFocusNodeKey("");
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
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);
    };
  }, [graphDraft.dirty]);

  useEffect(() => {
    if (draftLoading) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      void persistDraft(draftFileName, graphDraft)
        .then(() => setDraftPersistError(""))
        .catch((error) =>
          setDraftPersistError(
            error instanceof Error ? error.message : "unknown error",
          ),
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
    setPendingFocusNodeKey("");
    setPendingAggregateFocusNodeKey(options?.focusAggregateNodeKey ?? "");
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

  function resolveSelectableActualNodeKeys(
    nodeKeys: Iterable<string>,
  ): string[] {
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
    canvasNodes: Pick<GraphFlowNode, "id" | "selected">[],
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
      if (change.type !== "select") {
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
    pendingSelectionNodeKeysRef.current = [...nextNodeKeySet].sort(
      (left, right) => left.localeCompare(right),
    );
  }

  /**
   * 将最终确认的真实节点集合写回 ReactFlow 受控 selected，避免拖框预览态直接污染业务高亮。
   */
  function applyCanvasSelectedNodeKeys(nodeKeys: Iterable<string>) {
    const selectedNodeKeySet = new Set(
      resolveSelectableActualNodeKeys(nodeKeys),
    );
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
  function handleCanvasMiddleMouseEvent(
    event: React.MouseEvent<HTMLDivElement>,
  ) {
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
    resumeCanvasSelectionSyncFrameRef.current = window.requestAnimationFrame(
      () => {
        resumeCanvasSelectionSyncFrameRef.current =
          window.requestAnimationFrame(() => {
            suspendCanvasSelectionSyncRef.current = false;
            resumeCanvasSelectionSyncFrameRef.current = null;
          });
      },
    );
  }

  /**
   * 清空选择时要同时拦住内部选中变更，否则旧选区会把批量编辑集合重新写回。
   */
  function handleCanvasNodesChange(changes: NodeChange[]) {
    if (selectionPreviewActiveRef.current) {
      applySelectionPreviewNodeChanges(changes);
    }
    if (
      !suspendCanvasSelectionSyncRef.current &&
      !selectionPreviewActiveRef.current
    ) {
      onNodesChange(changes);
      return;
    }
    const filteredChanges = changes.filter(
      (change) => change.type !== "select",
    );
    if (filteredChanges.length === 0) {
      return;
    }
    onNodesChange(filteredChanges);
  }

  /**
   * 将 ReactFlow 的当前框选结果同步为批量编辑集合，仅保留真实 triggerSource/core 节点。
   */
  function applyBatchSelectionFromNodeKeys(nodeKeys: Iterable<string>) {
    if (displayMode === "channel") {
      const normalizedNodeKeys = resolveSelectableActualNodeKeys(nodeKeys);
      setSelectedEditChannelNodeKeys((currentValues) =>
        currentValues.length === normalizedNodeKeys.length &&
        currentValues.every((value, index) => value === normalizedNodeKeys[index])
          ? currentValues
          : normalizedNodeKeys,
      );
      return;
    }
    const nextSourceSerials = new Set<number>();
    const nextTargetSerials = new Set<number>();
    for (const nodeKey of nodeKeys) {
      const graphNode = nodeByKey.get(nodeKey);
      if (!graphNode) {
        continue;
      }
      if (graphNode.type === "triggerSource") {
        nextSourceSerials.add(graphNode.serial);
        continue;
      }
      nextTargetSerials.add(graphNode.serial);
    }
    const normalizedSourceSerials = [...nextSourceSerials].sort(
      (left, right) => left - right,
    );
    const normalizedTargetSerials = [...nextTargetSerials].sort(
      (left, right) => left - right,
    );
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
    if (!canEditCurrentView || editMode === "view") {
      return;
    }
    clearSelectionPreviewState();
    selectionPreviewActiveRef.current = true;
    pendingSelectionNodeKeysRef.current =
      collectSelectableActualNodeKeysFromCanvasNodes(nodes);
  }

  function handleSelectionPreviewEnd() {
    if (!canEditCurrentView || editMode === "view") {
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
    setDraftPersistError("");
    if (savePhase === "error" || savePhase === "conflict") {
      setSavePhase("idle");
    }
    setGraphDraft(undoableGraphDraft);
    setUndoableGraphDraft(null);
    setSaveMessage("已撤回最近一步本地草稿。");
  }

  function handleApplySearch() {
    const nextMatchedNode = searchableNodes.find(
      (node) =>
        matchesSearchType(node, searchDraftTypeFilter) &&
        matchesSearch(node, searchDraftText),
    );
    setAppliedSearchText(searchDraftText);
    setAppliedSearchTypeFilter(searchDraftTypeFilter);
    if (nextMatchedNode) {
      focusNode(nextMatchedNode.nodeKey);
    }
  }

  function handleClearSearch() {
    setSearchDraftText("");
    setAppliedSearchText("");
    setSearchDraftTypeFilter("all");
    setAppliedSearchTypeFilter("all");
  }

  function handleAutoLayout() {
    setPendingAggregateFocusNodeKey("");
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

  function resetBatchSelectionState() {
    clearSelectionPreviewState();
    temporarilySuspendCanvasSelectionSync();
    setSelectedEditSourceSerials([]);
    setSelectedEditTargetSerials([]);
    setSelectedEditChannelNodeKeys([]);
    applyCanvasSelectedNodeKeys([]);
  }

  function handleDisplayModeChange(nextDisplayMode: GraphDisplayMode) {
    if (nextDisplayMode === displayMode) {
      return;
    }
    setSelectedNodeKey("");
    setDisplayMode(nextDisplayMode);
    setActiveSidebarPanel("details");
    setPendingAggregateFocusNodeKey("");
    setPendingFocusNodeKey("");
    requestStructureLayoutRefresh({
      fitViewport: true,
    });
    if (
      nextDisplayMode === "channel" &&
      (editMode === "add" || editMode === "remove")
    ) {
      setEditMode("view");
    }
    resetBatchSelectionState();
  }

  function handleEditModeChange(nextEditMode: GraphEditMode) {
    if (!canEditCurrentView && nextEditMode !== "view") {
      return;
    }
    if (
      displayMode === "channel" &&
      nextEditMode !== "view" &&
      nextEditMode !== "replace"
    ) {
      return;
    }
    setEditMode(nextEditMode);
    if (nextEditMode === "view") {
      resetBatchSelectionState();
      setActiveSidebarPanel((currentPanel) =>
        currentPanel === "batch" ? "details" : currentPanel,
      );
      return;
    }
    setActiveSidebarPanel("batch");
  }

  function handleClearBatchSelection() {
    resetBatchSelectionState();
  }

  function handleSetExpandedAggregateNode(
    nodeKey: string,
    expanded: boolean,
  ) {
    setExpandedAggregateNodeKeys((currentValues) => {
      const exists = currentValues.includes(nodeKey);
      if (expanded) {
        return exists
          ? currentValues
          : [...currentValues, nodeKey].sort((left, right) =>
              left.localeCompare(right),
            );
      }
      return exists
        ? currentValues.filter((value) => value !== nodeKey)
        : currentValues;
    });
  }

  function handleCanvasNodeClick(nodeKey: string) {
    if (nodeKey.startsWith("aggregate:")) {
      const currentAggregateNode = graphCanvasView.aggregateNodes.find(
        (aggregateNode) => aggregateNode.nodeKey === nodeKey,
      );
      const willExpand = !(currentAggregateNode?.expanded ?? false);
      setSelectedNodeKey(nodeKey);
      if (
        !willExpand &&
        currentAggregateNode?.memberNodeKeys.includes(selectedNodeKey)
      ) {
        setPendingFocusNodeKey("");
      }
      handleSetExpandedAggregateNode(nodeKey, willExpand);
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
    if (nodeKey.startsWith("channelHub:")) {
      setSelectedNodeKey(nodeKey);
      setActiveSidebarPanel("details");
      return;
    }
    if (!nodeByKey.has(nodeKey)) {
      return;
    }
    setSelectedNodeKey(nodeKey);
    if (!canEditCurrentView || editMode === "view") {
      setActiveSidebarPanel("details");
      return;
    }
  }

  function handleCanvasNodeDoubleClick(nodeKey: string) {
    if (nodeKey.startsWith("aggregate:")) {
      focusNode(nodeKey);
      return;
    }
    focusNode(nodeKey);
  }

  function handleRevealIsolatedNode(nodeKey: string) {
    setPinnedIsolatedNodeKeys((currentValues) =>
      currentValues.includes(nodeKey)
        ? currentValues
        : [...currentValues, nodeKey].sort((left, right) =>
            left.localeCompare(right),
          ),
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
      setSelectedNodeKey("");
      setPendingFocusNodeKey("");
    }
    setPinnedIsolatedNodeKeys([]);
  }

  function handleGraphNodeClick(nodeKey: string) {
    handleCanvasNodeClick(nodeKey);
  }

  function handleAliasChange(
    nodeType: GraphNodeTypeToken,
    serial: number,
    alias: string,
  ) {
    if (!canEditCurrentView) {
      return;
    }
    setDraftPersistError("");
    if (savePhase === "error" || savePhase === "conflict") {
      setSavePhase("idle");
    }
    applyLocalDraftChange(
      upsertAliasDraft(graphDraft, baseGraphBundle, nodeType, serial, alias),
    );
  }

  function handleApplyBatchEdit() {
    if (!canApplyBatchEdit || editMode === "view") {
      return;
    }
    setDraftPersistError("");
    if (savePhase === "error" || savePhase === "conflict") {
      setSavePhase("idle");
    }
    const nextDraft =
      displayMode === "channel"
        ? applyChannelEditToDraft(
            graphDraft,
            baseGraphBundle,
            selectedEditChannelNodeKeys,
            parsedChannelBatchValue ?? 0,
          )
        : applyBatchEditToDraft(
            graphDraft,
            baseGraphBundle,
            editMode,
            selectedEditSourceSerials,
            selectedEditTargetSerials,
          );
    if (applyLocalDraftChange(nextDraft)) {
      setSaveMessage(
        displayMode === "channel"
          ? `已将频道覆盖写入本地草稿，点击 Save 后才会回传游戏真值。`
          : `已将 ${formatEditModeLabel(editMode)} 操作写入本地草稿，点击 Save 后才会回传游戏真值。`,
      );
    }
  }

  async function handleSave() {
    if (!graphDraft.dirty || savePhase === "saving") {
      return;
    }
    setSavePhase("saving");
    setSaveMessage("");
    try {
      const graphWriteResponse = await submitGraphSave(graphDraft, displayMode);
      if (graphWriteResponse.status === "error") {
        setSavePhase("error");
        setSaveMessage(graphWriteResponse.message || "graph 保存请求失败。");
        return;
      }
      if (graphWriteResponse.result === "conflict") {
        setSavePhase("conflict");
        setSaveMessage(
          graphWriteResponse.message ||
            "保存冲突：请重新导出 graph 文件后再试。",
        );
        return;
      }
      if (graphWriteResponse.result === "rejected") {
        setSavePhase("error");
        setSaveMessage(graphWriteResponse.message || "保存被服务端拒绝。");
        return;
      }
      const nextBaseGraphBundle = applyUpdatedNodeStates(
        applyDraftToGraph(baseGraphBundle, graphDraft),
        graphWriteResponse,
      );
      setBaseGraphBundle(nextBaseGraphBundle);
      setGraphDraft(createInitialGraphDraft(nextBaseGraphBundle));
      setUndoableGraphDraft(null);
      setSavePhase("idle");
      setSaveMessage(graphWriteResponse.message || "已保存。");
    } catch (error) {
      setSavePhase("error");
      setSaveMessage(error instanceof Error ? error.message : "unknown error");
    }
  }

  return (
    <section className="graph-viewer">
      <div className="graph-toolbar-block">
        <div className="recording-toolbar-row">
          <span className="chart-toolbar-label">显示模式</span>
          <div className="chip-group">
            {(["serial", "channel"] as GraphDisplayMode[]).map((modeValue) => (
              <button
                key={modeValue}
                type="button"
                className={`metric-chip${displayMode === modeValue ? " is-active" : ""}`}
                onClick={() => handleDisplayModeChange(modeValue)}
              >
                {modeValue === "serial" ? "序号" : "频道"}
              </button>
            ))}
          </div>
        </div>
        <div className="recording-toolbar-row graph-submode-row">
          <span className="chart-toolbar-label">{graphModeLabel}内容</span>
          <div className="chip-group">
            <button
              type="button"
              className={`metric-chip${activeContentMode === "topology" ? " is-active" : ""}`}
              onClick={() => {
                if (displayMode === "serial") {
                  setSerialContentMode("topology");
                  return;
                }
                setChannelContentMode("topology");
              }}
            >
              拓扑
            </button>
          </div>
        </div>
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
                  if (event.key === "Enter") {
                    event.preventDefault();
                    handleApplySearch();
                  }
                }}
                placeholder="按别名、显示名、nodeKey 搜索；序号用 #12，频道用 channel:3"
              />
            </label>
            <div className="graph-search-filter-row">
              <span className="graph-search-filter-label">类型</span>
              <div className="chip-group">
                {(
                  ["all", "triggerSource", "core"] as GraphSearchTypeFilter[]
                ).map((filterValue) => (
                  <button
                    key={filterValue}
                    type="button"
                    className={`metric-chip${searchDraftTypeFilter === filterValue ? " is-active" : ""}`}
                    onClick={() => setSearchDraftTypeFilter(filterValue)}
                  >
                    {formatSearchTypeLabel(filterValue)}
                  </button>
                ))}
              </div>
            </div>
            <div className="graph-search-filter-row">
              <span className="graph-search-filter-label">
                {hasPendingSearchChanges ? "搜索条件未应用" : "搜索条件已应用"}
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
                    searchDraftTypeFilter === "all" &&
                    appliedSearchTypeFilter === "all"
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
            <button
              type="button"
              className="action-button"
              onClick={handleAutoLayout}
            >
              重新布局
            </button>
            <button
              type="button"
              className="action-button"
              disabled={!graphDraft.dirty || savePhase === "saving"}
              onClick={() => void handleSave()}
            >
              Save
            </button>
          </div>
        </div>
        <div className="recording-toolbar-row">
          <span className="chart-toolbar-label">
            编辑模式{canEditCurrentView ? "" : "（当前内容不可编辑）"}
          </span>
          <div className="chip-group">
            {availableEditModes.map((modeValue) => (
              <button
                key={modeValue}
                type="button"
                className={`metric-chip${editMode === modeValue ? " is-active" : ""}`}
                onClick={() => handleEditModeChange(modeValue)}
                disabled={!canEditCurrentView && modeValue !== "view"}
              >
                {formatEditModeLabel(modeValue)}
              </button>
            ))}
          </div>
        </div>
        <p className="chart-interaction-hint">
          鼠标滚轮缩放，拖动画布平移，拖拽节点只影响本地布局；双击节点会聚焦到该节点。
          搜索条件会在回车或点击“应用搜索”后刷新画布。
          {displayMode === "serial"
            ? "点击聚合块，可展开或收起对应的局部 core 集合。"
            : "频道模式通过虚拟 channelHub 与两类聚合块展示 triggerSource -> channelHub -> core 的两级连接。"}
          {canEditCurrentView
            ? displayMode === "channel"
              ? "先框选或点选一个或多个节点，再输入频道号并应用到草稿；输入 0 表示移出频道。网页修改只进入本地草稿，点击 Save 后才会回传游戏真值。"
              : `${formatEditModeInstruction(editMode)} 网页修改只进入本地草稿，点击 Save 后才会回传游戏真值。`
            : "当前内容模式不接入网页保存编辑。"}
        </p>
        {saveMessage ? (
          <p className="graph-editor-message">{saveMessage}</p>
        ) : null}
        {draftError ? (
          <p className="error-text">加载本地 draft 失败：{draftError}</p>
        ) : null}
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
                  className={`graph-search-chip${selectedNodeKey === node.nodeKey ? " is-selected" : ""}`}
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
              <span className="section-tag">{canvasSectionTag}</span>
              <h3>{canvasTitle}</h3>
            </div>
            <dl className="graph-inline-stats">
              <div>
                <dt>{displayMode === "serial" ? "Nodes" : "Mode Nodes"}</dt>
                <dd>
                  {displayMode === "serial"
                    ? effectiveGraphBundle.stats.nodeCount
                    : searchableNodes.length}
                </dd>
              </div>
              <div>
                <dt>Canvas</dt>
                <dd>{graphCanvasView.canvasNodes.length}</dd>
              </div>
              <div>
                <dt>{displayMode === "serial" ? "Edges" : "Virtual Edges"}</dt>
                <dd>
                  {displayMode === "serial"
                    ? effectiveGraphBundle.edges.length
                    : graphCanvasView.canvasEdges.length}
                </dd>
              </div>
              <div>
                <dt>
                  {displayMode === "serial" ? "Groups" : "Channels / Groups"}
                </dt>
                <dd>
                  {displayMode === "serial"
                    ? graphCanvasView.aggregateNodes.length
                    : `${graphCanvasView.channelHubNodes.length} / ${graphCanvasView.aggregateNodes.length}`}
                </dd>
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
            {displayMode === "channel" ? (
              <span className="graph-legend-item">
                <span className="graph-legend-swatch is-channel-hub" />
                channel hub
              </span>
            ) : null}
            <span className="graph-legend-item">
              <span className="graph-legend-swatch is-core" />
              core
            </span>
            {displayMode === "serial" ||
            graphCanvasView.aggregateNodes.length > 0 ? (
              <>
                <span className="graph-legend-item">
                  <span className="graph-legend-swatch is-aggregate" />
                  aggregate
                </span>
                <span className="graph-legend-item">
                  <span className="graph-legend-swatch is-aggregate-outline" />
                  expanded aggregate area
                </span>
              </>
            ) : null}
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
                  {displayMode === "channel"
                    ? "当前没有可展示的频道模式节点；仅 connectionMode=channel 且 channel>0 的节点会进入该视图。"
                    : effectiveGraphBundle.nodes.length === 0
                      ? "当前图快照没有可展示节点。"
                      : "当前主画布没有默认可展示的拓扑块，可在右侧孤立节点池中选择节点。"}
                </p>
              </div>
            ) : null}
            <ReactFlow
              nodes={displayNodes}
              edges={edges}
              onNodesChange={handleCanvasNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={(_, node) => handleGraphNodeClick(String(node.id))}
              onNodeDoubleClick={(_, node) =>
                handleCanvasNodeDoubleClick(String(node.id))
              }
              onPaneClick={() => setSelectedNodeKey("")}
              onSelectionChange={({ nodes: selectedNodes }) => {
                if (
                  !selectionEnabled ||
                  suspendCanvasSelectionSyncRef.current
                ) {
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
              elementsSelectable={selectionEnabled}
              selectionOnDrag={selectionEnabled}
              selectionMode={SelectionMode.Full}
              multiSelectionKeyCode={["Meta", "Control", "Shift"]}
              panOnDrag={[1]}
              zoomOnScroll
            >
              <Background color="rgba(255, 214, 191, 0.12)" gap={24} size={1} />
              <MiniMap
                pannable
                zoomable
                nodeColor={(node) =>
                  String(node.id).startsWith("aggregate-outline:")
                    ? "transparent"
                    : String(node.id).startsWith("triggerSource:")
                      ? "rgba(255, 181, 140, 0.82)"
                      : String(node.id).startsWith("channelHub:")
                        ? "rgba(239, 216, 139, 0.9)"
                        : String(node.id).startsWith("aggregate:")
                          ? "rgba(140, 213, 255, 0.86)"
                          : "rgba(108, 230, 255, 0.8)"
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
              <h2>
                {displayMode === "serial" ? "节点详情与编辑" : "频道视图详情"}
              </h2>
            </div>
            <div className="chip-group graph-detail-tabs">
              {availableSidebarPanels.map(([panelKey, panelLabel]) => (
                <button
                  key={panelKey}
                  type="button"
                  className={`metric-chip${activeSidebarPanel === panelKey ? " is-active" : ""}`}
                  onClick={() => setActiveSidebarPanel(panelKey)}
                >
                  {panelLabel}
                </button>
              ))}
            </div>
          </header>

          {activeSidebarPanel === "isolated" ? (
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
                    <span>
                      数量 {graphCanvasView.isolatedTriggerSourceNodes.length}
                    </span>
                  </div>
                  <div className="graph-target-list">
                    {graphCanvasView.isolatedTriggerSourceNodes.length === 0 ? (
                      <p className="empty-state">
                        当前没有孤立的 triggerSource。
                      </p>
                    ) : (
                      graphCanvasView.isolatedTriggerSourceNodes.map(
                        (isolatedNode) => (
                          <button
                            key={isolatedNode.nodeKey}
                            type="button"
                            className={`graph-target-item graph-target-chip${pinnedIsolatedNodeKeys.includes(isolatedNode.nodeKey) ? " is-selected" : ""}`}
                            onClick={() =>
                              handleRevealIsolatedNode(isolatedNode.nodeKey)
                            }
                          >
                            {isolatedNode.displayText}
                          </button>
                        ),
                      )
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
                          className={`graph-target-item graph-target-chip${pinnedIsolatedNodeKeys.includes(isolatedNode.nodeKey) ? " is-selected" : ""}`}
                          onClick={() =>
                            handleRevealIsolatedNode(isolatedNode.nodeKey)
                          }
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

          {activeSidebarPanel === "batch" ? (
            canEditCurrentView && editMode !== "view" ? (
              displayMode === "channel" ? (
                <section className="graph-batch-editor">
                  <div className="graph-batch-editor-header">
                    <strong>频道覆盖编辑</strong>
                    <span>已选节点 {selectedEditChannelNodeKeys.length} 个</span>
                  </div>
                  <p className="graph-batch-editor-caption">
                    在频道模式下，批量编辑按“节点到频道号”的覆盖语义处理；输入
                    `0` 表示移出频道。
                  </p>
                  <label className="graph-editor-field">
                    <span>目标频道号</span>
                    <input
                      className="graph-editor-input"
                      type="number"
                      min={0}
                      step={1}
                      value={channelBatchDraftValue}
                      onChange={(event) =>
                        setChannelBatchDraftValue(event.target.value)
                      }
                      placeholder="输入 >= 0 的整数"
                    />
                  </label>
                  <div className="graph-batch-selection-grid">
                    <section className="graph-target-editor">
                      <div className="graph-target-editor-header">
                        <strong>Selected Nodes</strong>
                        <span>
                          点击或框选 triggerSource/core，`Ctrl/Shift` 可追加多选。
                        </span>
                      </div>
                      <div className="graph-target-list">
                        {selectedEditChannelNodes.length === 0 ? (
                          <p className="empty-state">当前还没有选中节点。</p>
                        ) : (
                          selectedEditChannelNodes.map((graphNode) => (
                            <button
                              key={graphNode.nodeKey}
                              type="button"
                              className="graph-target-item graph-target-chip"
                              onClick={() => focusNode(graphNode.nodeKey)}
                            >
                              {graphNode.displayText}
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
                <section className="graph-batch-editor">
                  <div className="graph-batch-editor-header">
                    <strong>{formatEditModeLabel(editMode)} 批量拓扑编辑</strong>
                    <span>
                      已选来源 {selectedEditSourceSerials.length} 个 / 目标{" "}
                      {selectedEditTargetSerials.length} 个
                    </span>
                  </div>
                  <p className="graph-batch-editor-caption">
                    {formatEditModeInstruction(editMode)}
                  </p>
                  {editMode === "replace" ? (
                    <p className="graph-batch-editor-caption">
                      `replace` 模式允许来源集合为空目标，应用后可直接清空这些
                      triggerSource 的全部连接。
                    </p>
                  ) : null}
                  <div className="graph-batch-selection-grid">
                    <section className="graph-target-editor">
                      <div className="graph-target-editor-header">
                        <strong>Selected TriggerSources</strong>
                        <span>
                          点击或框选 triggerSource，`Ctrl/Shift` 可追加多选。
                        </span>
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
              )
            ) : (
              <p className="empty-state graph-detail-empty">
                当前视图还没有进入可编辑状态。切换到 `replace`，这里会显示批量编辑面板。
              </p>
            )
          ) : null}

          {activeSidebarPanel === "details" ? (
            !selectedCanvasNode ? (
              <p className="empty-state graph-detail-empty">
                点击图中的一个节点或频道块后，这里会显示当前模式下的结构信息。
              </p>
            ) : selectedCanvasNode.kind === "channelHub" ? (
              <div className="graph-detail-section">
                <div className="graph-detail-hero">
                  <p className="eyebrow">channelHub</p>
                  <h3>channel #{selectedCanvasNode.channel}</h3>
                  <p className="graph-detail-caption">
                    {selectedCanvasNode.nodeKey}
                  </p>
                </div>
                <dl className="preview-meta graph-detail-grid">
                  <div>
                    <dt>Channel</dt>
                    <dd>{selectedCanvasNode.channel}</dd>
                  </div>
                  <div>
                    <dt>TriggerSources</dt>
                    <dd>{selectedCanvasNode.sourceSerials.length}</dd>
                  </div>
                  <div>
                    <dt>Cores</dt>
                    <dd>{selectedCanvasNode.coreSerials.length}</dd>
                  </div>
                  <div>
                    <dt>Members</dt>
                    <dd>{selectedCanvasNode.memberCount}</dd>
                  </div>
                  <div>
                    <dt>Virtual Edges</dt>
                    <dd>
                      {selectedCanvasNode.sourceSerials.length +
                        selectedCanvasNode.coreSerials.length}
                    </dd>
                  </div>
                  <div>
                    <dt>Structure Checksum</dt>
                    <dd>
                      {effectiveGraphBundle.structureChecksum.slice(0, 12)}
                    </dd>
                  </div>
                </dl>
                <div className="graph-batch-selection-grid">
                  <section className="graph-target-editor">
                    <div className="graph-target-editor-header">
                      <strong>Channel TriggerSources</strong>
                      <span>当前频道下的来源节点。</span>
                    </div>
                    <div className="graph-target-list">
                      {selectedChannelTriggerSourceNodes.length === 0 ? (
                        <p className="empty-state">
                          当前频道下没有 triggerSource。
                        </p>
                      ) : (
                        selectedChannelTriggerSourceNodes.map((sourceNode) => (
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
                      <strong>Channel Cores</strong>
                      <span>当前频道下的目标节点。</span>
                    </div>
                    <div className="graph-target-list">
                      {selectedChannelCoreNodes.length === 0 ? (
                        <p className="empty-state">当前频道下没有 core。</p>
                      ) : (
                        selectedChannelCoreNodes.map((coreNode) => (
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
                </div>
              </div>
            ) : selectedCanvasNode.kind === "aggregate" ? (
              <div className="graph-detail-section">
                <div className="graph-detail-hero">
                  <p className="eyebrow">
                    {selectedCanvasNode.aggregateRole} aggregate
                  </p>
                  <h3>{selectedCanvasNode.memberCount} 个聚合成员</h3>
                  <p className="graph-detail-caption">
                    {selectedCanvasNode.nodeKey}
                  </p>
                </div>
                <dl className="preview-meta graph-detail-grid">
                  <div>
                    <dt>Role</dt>
                    <dd>{selectedCanvasNode.aggregateRole}</dd>
                  </div>
                  <div>
                    <dt>Members</dt>
                    <dd>{selectedCanvasNode.memberCount}</dd>
                  </div>
                  <div>
                    <dt>Connected Canvas Nodes</dt>
                    <dd>{selectedCanvasNode.connectedNodeKeys.length}</dd>
                  </div>
                  <div>
                    <dt>Expanded</dt>
                    <dd>{selectedCanvasNode.expanded ? "true" : "false"}</dd>
                  </div>
                  <div>
                    <dt>Anchor Serial</dt>
                    <dd>{selectedCanvasNode.anchorSerial}</dd>
                  </div>
                  <div>
                    <dt>Structure Checksum</dt>
                    <dd>
                      {effectiveGraphBundle.structureChecksum.slice(0, 12)}
                    </dd>
                  </div>
                </dl>
                <div className="graph-batch-selection-grid">
                  <section className="graph-target-editor">
                    <div className="graph-target-editor-header">
                      <strong>Connected Nodes</strong>
                      <span>
                        与该聚合块保持可见关系的连接对象；频道模式下这里会显示
                        channelHub。
                      </span>
                    </div>
                    <div className="graph-target-list">
                      {selectedAggregateConnectedCanvasNodes.length === 0 ? (
                        <p className="empty-state">当前没有已连接节点。</p>
                      ) : (
                        selectedAggregateConnectedCanvasNodes.map(
                          (canvasNode) => (
                            <button
                              key={canvasNode.nodeKey}
                              type="button"
                              className="graph-target-item graph-target-chip"
                              onClick={() => focusNode(canvasNode.nodeKey)}
                            >
                              {formatCanvasNodeDisplayText(canvasNode)}
                            </button>
                          ),
                        )
                      )}
                    </div>
                  </section>
                  <section className="graph-target-editor">
                    <div className="graph-target-editor-header">
                      <strong>Member Nodes</strong>
                      <span>聚合块代表的真实成员节点。</span>
                    </div>
                    <div className="graph-target-list">
                      {selectedAggregateMemberNodes.length === 0 ? (
                        <p className="empty-state">当前没有成员节点。</p>
                      ) : (
                        selectedAggregateMemberNodes.map((graphNode) => (
                          <button
                            key={graphNode.nodeKey}
                            type="button"
                            className="graph-target-item graph-target-chip"
                            onClick={() => focusNode(graphNode.nodeKey)}
                          >
                            {graphNode.displayText}
                          </button>
                        ))
                      )}
                    </div>
                  </section>
                </div>
              </div>
            ) : selectedCanvasNode.kind === "actual" ? (
              <div className="graph-detail-section">
                <div className="graph-detail-hero">
                  <p className="eyebrow">{selectedCanvasNode.graphNode.type}</p>
                  <h3>{selectedCanvasNode.graphNode.displayText}</h3>
                  <p className="graph-detail-caption">
                    {selectedCanvasNode.graphNode.nodeKey}
                  </p>
                </div>
                {canEditCurrentView ? (
                  <label className="graph-editor-field">
                    <span>Alias</span>
                    <input
                      className="graph-editor-input"
                      type="text"
                      value={selectedCanvasNode.graphNode.alias}
                      onChange={(event) =>
                        handleAliasChange(
                          selectedCanvasNode.graphNode.type,
                          selectedCanvasNode.graphNode.serial,
                          event.target.value,
                        )
                      }
                      placeholder="输入节点别名，留空则清空"
                    />
                  </label>
                ) : null}
                <dl className="preview-meta graph-detail-grid">
                  <div>
                    <dt>Serial</dt>
                    <dd>{selectedCanvasNode.graphNode.serial}</dd>
                  </div>
                  <div>
                    <dt>Connection Mode</dt>
                    <dd>{selectedCanvasNode.graphNode.connectionMode}</dd>
                  </div>
                  <div>
                    <dt>Channel</dt>
                    <dd>{selectedCanvasNode.graphNode.channel}</dd>
                  </div>
                  <div>
                    <dt>Source Revision</dt>
                    <dd>{selectedCanvasNode.graphNode.sourceRevision}</dd>
                  </div>
                  <div>
                    <dt>Core Revision</dt>
                    <dd>{selectedCanvasNode.graphNode.coreRevision}</dd>
                  </div>
                  <div>
                    <dt>Allocated</dt>
                    <dd>
                      {selectedCanvasNode.graphNode.allocated
                        ? "true"
                        : "false"}
                    </dd>
                  </div>
                  <div>
                    <dt>Retired</dt>
                    <dd>
                      {selectedCanvasNode.graphNode.retired ? "true" : "false"}
                    </dd>
                  </div>
                  <div>
                    <dt>Incident Edges</dt>
                    <dd>
                      {edgeCountByNodeKey.get(
                        selectedCanvasNode.graphNode.nodeKey,
                      ) ?? 0}
                    </dd>
                  </div>
                  <div>
                    <dt>Structure Checksum</dt>
                    <dd>
                      {effectiveGraphBundle.structureChecksum.slice(0, 12)}
                    </dd>
                  </div>
                </dl>
                {canEditCurrentView &&
                selectedCanvasNode.graphNode.type === "triggerSource" ? (
                  <section className="graph-target-editor">
                    <div className="graph-target-editor-header">
                      <strong>Current Target Cores</strong>
                      <span>
                        这里展示当前草稿视角下的有效目标集合；批量编辑请使用右侧批量编辑页签。
                      </span>
                    </div>
                    <div className="graph-target-list">
                      {selectedTriggerSourceTargetNodes.length === 0 ? (
                        <p className="empty-state">
                          当前 triggerSource 在草稿视角下没有目标 core。
                        </p>
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
                {displayMode === "channel" && selectedChannelHubNode ? (
                  <div className="graph-batch-selection-grid">
                    <section className="graph-target-editor">
                      <div className="graph-target-editor-header">
                        <strong>同频道 TriggerSources</strong>
                        <span>当前节点所在频道的来源节点。</span>
                      </div>
                      <div className="graph-target-list">
                        {selectedChannelTriggerSourceNodes.length === 0 ? (
                          <p className="empty-state">
                            当前频道下没有 triggerSource。
                          </p>
                        ) : (
                          selectedChannelTriggerSourceNodes.map(
                            (sourceNode) => (
                              <button
                                key={sourceNode.nodeKey}
                                type="button"
                                className="graph-target-item graph-target-chip"
                                onClick={() => focusNode(sourceNode.nodeKey)}
                              >
                                {sourceNode.displayText}
                              </button>
                            ),
                          )
                        )}
                      </div>
                    </section>
                    <section className="graph-target-editor">
                      <div className="graph-target-editor-header">
                        <strong>同频道 Cores</strong>
                        <span>当前节点所在频道的目标节点。</span>
                      </div>
                      <div className="graph-target-list">
                        {selectedChannelCoreNodes.length === 0 ? (
                          <p className="empty-state">当前频道下没有 core。</p>
                        ) : (
                          selectedChannelCoreNodes.map((coreNode) => (
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
                  </div>
                ) : null}
                <div className="graph-flag-block">
                  <strong>Capability Flags</strong>
                  <div className="graph-flag-list">
                    {selectedCanvasNode.graphNode.capabilityFlags.length ===
                    0 ? (
                      <span className="graph-flag-chip">-</span>
                    ) : (
                      selectedCanvasNode.graphNode.capabilityFlags.map(
                        (flag) => (
                          <span key={flag} className="graph-flag-chip">
                            {flag}
                          </span>
                        ),
                      )
                    )}
                  </div>
                </div>
              </div>
            ) : null
          ) : null}
        </aside>
      </div>
    </section>
  );
}
