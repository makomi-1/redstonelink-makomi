import { describe, expect, it } from 'vitest';
import type { GraphCanvasView, GraphDraftDiff } from './types';
import {
  buildAggregateOutlineFlowNodes,
  buildAutoLayoutPositions,
  buildEdgeCountByNodeKey,
  buildEdges,
  buildGraphCanvasView,
  buildGraphFlowNodes,
  sameAggregateOutlineFlowNodes,
  toggleStringSelection,
} from './canvas';
import {
  createTestGraphBundle,
  createTestGraphEdge,
  createTestGraphNode,
} from '../../test/factories';

describe('graphViewer/canvas', () => {
  it('toggleStringSelection 会按排序规则切换字符串选中集合', () => {
    expect(toggleStringSelection(['core:2'], 'core:1')).toEqual(['core:1', 'core:2']);
    expect(toggleStringSelection(['core:1', 'core:2'], 'core:1')).toEqual(['core:2']);
  });

  it('buildEdgeCountByNodeKey 会同时统计边的来源与目标', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 11 }),
      ],
    });

    const edgeCountByNodeKey = buildEdgeCountByNodeKey(graphBundle);

    expect(edgeCountByNodeKey.get('triggerSource:1')).toBe(2);
    expect(edgeCountByNodeKey.get('core:10')).toBe(1);
    expect(edgeCountByNodeKey.get('core:11')).toBe(1);
  });

  it('buildGraphCanvasView 在序号模式下会生成 core 聚合块', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 11 }),
      ],
    });

    const canvasView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'serial',
    );

    expect(canvasView.aggregateNodes).toHaveLength(1);
    expect(canvasView.aggregateNodes[0]).toMatchObject({
      aggregateRole: 'core',
      memberCount: 2,
      sourceSerials: [1],
      coreSerials: [10, 11],
      expanded: false,
    });
  });

  it('buildGraphCanvasView 不会仅因选中成员可见就自动展开聚合块', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 11 }),
      ],
    });

    const canvasView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(['core:10']),
      new Set<string>(),
      'serial',
      new Set<string>(),
    );

    expect(canvasView.aggregateNodes).toHaveLength(1);
    expect(canvasView.aggregateNodes[0]?.expanded).toBe(false);
    expect(canvasView.canvasNodes.map((node) => node.nodeKey)).toEqual(
      expect.arrayContaining(['aggregate:core:triggerSource:1', 'core:10']),
    );
  });

  it('buildGraphCanvasView 在序号模式下会把转发器双身份组合成 repeater 节点', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({
          type: 'triggerSource',
          serial: 10,
          alias: 'relay',
          capabilityFlags: ['repeater'],
        }),
        createTestGraphNode({
          type: 'core',
          serial: 10,
          alias: 'relay',
          capabilityFlags: ['repeater'],
        }),
        createTestGraphNode({ type: 'core', serial: 20 }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 10, targetSerial: 20 }),
      ],
    });

    const collapsedView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'serial',
    );

    expect(collapsedView.repeaterNodes).toHaveLength(1);
    expect(collapsedView.repeaterNodes[0]).toMatchObject({
      serial: 10,
      inputSerials: [1],
      outputSerials: [20],
      memberNodeKeys: ['triggerSource:10', 'core:10'],
      expanded: false,
    });
    expect(collapsedView.canvasNodes.map((node) => node.nodeKey)).toContain('repeater:10');
    expect(collapsedView.canvasNodes.map((node) => node.nodeKey)).not.toEqual(
      expect.arrayContaining(['triggerSource:10', 'core:10']),
    );

    const expandedView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(['repeater:10']),
      'serial',
    );

    expect(expandedView.repeaterNodes[0]?.expanded).toBe(true);
    expect(expandedView.canvasNodes.map((node) => node.nodeKey)).toEqual(
      expect.arrayContaining(['repeater:10', 'triggerSource:10', 'core:10']),
    );
  });

  it('buildGraphCanvasView 在频道模式下会生成 channelHub 节点', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({
          type: 'triggerSource',
          serial: 1,
          connectionMode: 'channel',
          channel: 3,
        }),
        createTestGraphNode({
          type: 'core',
          serial: 10,
          connectionMode: 'channel',
          channel: 3,
        }),
      ],
    });

    const canvasView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'channel',
    );

    expect(canvasView.channelHubNodes).toHaveLength(1);
    expect(canvasView.channelHubNodes[0]).toMatchObject({
      channel: 3,
      sourceSerials: [1],
      coreSerials: [10],
      memberCount: 2,
    });
    expect(canvasView.canvasEdges.map((edge) => edge.edgeKey).sort()).toEqual([
      'channel-edge:channelHub:3:core:10',
      'channel-edge:triggerSource:1:channelHub:3',
    ]);
  });

  it('buildGraphCanvasView 在频道模式下会自动排除 repeater 节点', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({
          type: 'triggerSource',
          serial: 1,
          connectionMode: 'channel',
          channel: 3,
        }),
        createTestGraphNode({
          type: 'core',
          serial: 2,
          connectionMode: 'channel',
          channel: 3,
        }),
        createTestGraphNode({
          type: 'triggerSource',
          serial: 10,
          connectionMode: 'channel',
          channel: 3,
          capabilityFlags: ['repeater'],
        }),
        createTestGraphNode({
          type: 'core',
          serial: 10,
          connectionMode: 'channel',
          channel: 3,
          capabilityFlags: ['repeater'],
        }),
      ],
    });

    const canvasView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'channel',
    );

    expect(canvasView.repeaterNodes).toEqual([]);
    expect(canvasView.channelHubNodes).toHaveLength(1);
    expect(canvasView.channelHubNodes[0]).toMatchObject({
      channel: 3,
      sourceSerials: [1],
      coreSerials: [2],
      memberCount: 2,
    });
    expect(canvasView.canvasNodes.map((node) => node.nodeKey)).not.toEqual(
      expect.arrayContaining(['triggerSource:10', 'core:10', 'repeater:10']),
    );
  });

  it('buildAutoLayoutPositions 与 buildGraphFlowNodes 会为画布节点生成稳定位置和节点状态', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
      ],
      edges: [createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 })],
    });
    const canvasView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'serial',
    );
    const positionByNodeKey = buildAutoLayoutPositions(
      canvasView.canvasNodes,
      canvasView.layoutEdges,
    );
    const flowNodes = buildGraphFlowNodes(
      canvasView.canvasNodes,
      positionByNodeKey,
      'zh-CN',
      'replace',
      'triggerSource:1',
      false,
      new Set<string>(),
      new Set<string>(),
      new Set<string>(),
      new Set<string>(),
    );

    expect(positionByNodeKey.size).toBe(canvasView.canvasNodes.length);
    expect(flowNodes).toHaveLength(canvasView.canvasNodes.length);
    expect(flowNodes.find((node) => node.id === 'triggerSource:1')?.selectable).toBe(true);
  });

  it('buildAggregateOutlineFlowNodes 会为已展开聚合块绘制包围框', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 11 }),
      ],
    });
    const collapsedView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'serial',
    );
    const expandedAggregateNodeKey = collapsedView.aggregateNodes[0]?.nodeKey ?? '';
    const expandedView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>([expandedAggregateNodeKey]),
      'serial',
    );
    const positionByNodeKey = buildAutoLayoutPositions(
      expandedView.canvasNodes,
      expandedView.layoutEdges,
    );
    const flowNodes = buildGraphFlowNodes(
      expandedView.canvasNodes,
      positionByNodeKey,
      'zh-CN',
      'view',
      '',
      false,
      new Set<string>(),
      new Set<string>(),
      new Set<string>(),
      new Set<string>(),
    );

    const outlineNodes = buildAggregateOutlineFlowNodes(expandedView.aggregateNodes, flowNodes);

    expect(outlineNodes).toHaveLength(1);
    expect(sameAggregateOutlineFlowNodes(outlineNodes, [...outlineNodes])).toBe(true);
  });

  it('buildAutoLayoutPositions 会让已展开的 triggerSource 聚合块成员走整齐布局', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'triggerSource', serial: 2 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 2, targetSerial: 10 }),
      ],
    });
    const collapsedView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>(),
      'serial',
    );
    const expandedAggregateNodeKey = collapsedView.aggregateNodes[0]?.nodeKey ?? '';
    const expandedView = buildGraphCanvasView(
      graphBundle,
      new Set<string>(),
      new Set<string>([expandedAggregateNodeKey]),
      'serial',
    );
    const positionByNodeKey = buildAutoLayoutPositions(
      expandedView.canvasNodes,
      expandedView.layoutEdges,
    );

    expect(positionByNodeKey.get('triggerSource:1')?.x).toBe(
      positionByNodeKey.get('triggerSource:2')?.x,
    );
  });

  it('buildEdges 在序号模式下会补出被删除边的虚线预览', () => {
    const baseGraphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
      ],
      edges: [createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 })],
    });
    const effectiveGraphBundle = createTestGraphBundle({
      ...baseGraphBundle,
      edges: [],
      stats: {
        ...baseGraphBundle.stats,
        edgeCount: 0,
      },
    });
    const graphCanvasView: GraphCanvasView = {
      displayMode: 'serial',
      canvasNodes: [
        {
          kind: 'actual',
          nodeKey: 'triggerSource:1',
          graphNode: baseGraphBundle.nodes[0],
        },
        {
          kind: 'actual',
          nodeKey: 'core:10',
          graphNode: baseGraphBundle.nodes[1],
        },
      ],
      canvasEdges: [],
      layoutEdges: [],
      hiddenActualEdgeKeys: new Set<string>(),
      visibleActualNodeKeys: new Set<string>(['triggerSource:1', 'core:10']),
      isolatedTriggerSourceNodes: [],
      isolatedCoreNodes: [],
      repeaterNodes: [],
      aggregateNodes: [],
      channelHubNodes: [],
    };
    const draftDiff: GraphDraftDiff = {
      changedNodeKeys: new Set<string>(['triggerSource:1', 'core:10']),
      addedEdgeKeys: new Set<string>(),
      removedEdges: baseGraphBundle.edges,
    };

    const edges = buildEdges(
      baseGraphBundle,
      effectiveGraphBundle,
      false,
      new Set<string>(),
      draftDiff,
      graphCanvasView,
    );

    expect(edges).toHaveLength(1);
    expect(edges[0]).toMatchObject({
      id: 'removed:triggerSource:1->core:10',
      source: 'triggerSource:1',
      target: 'core:10',
    });
  });
});
