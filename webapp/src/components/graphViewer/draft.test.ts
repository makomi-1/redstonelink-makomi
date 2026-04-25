import { describe, expect, it } from 'vitest';
import { createInitialGraphDraft } from '../../graphTypes';
import {
  applyBatchEditToDraft,
  applyChannelEditToDraft,
  applyDraftToGraph,
  buildDraftDiff,
  buildDraftFileName,
  resolveEffectiveTargetSerials,
  upsertAliasDraft,
} from './draft';
import {
  createTestGraphBundle,
  createTestGraphNode,
  createTestGraphEdge,
} from '../../test/factories';

describe('graphViewer/draft', () => {
  it('buildDraftFileName 会优先基于 graph 文件名生成草稿名', () => {
    expect(buildDraftFileName('snapshot.json.gz', 'snapshot-1')).toBe('draft-snapshot.json');
    expect(buildDraftFileName('', 'snapshot-1')).toBe('draft-snapshot-1.json');
  });

  it('upsertAliasDraft 对仅空白差异不生成草稿操作', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({
          type: 'core',
          serial: 2,
          alias: 'beta',
        }),
      ],
    });
    const initialDraft = createInitialGraphDraft(graphBundle);

    const nextDraft = upsertAliasDraft(initialDraft, graphBundle, 'core', 2, '  beta  ');

    expect(nextDraft.dirty).toBe(false);
    expect(nextDraft.operations).toEqual([]);
  });

  it('applyBatchEditToDraft 会把 add 模式折算成 replace 草稿', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 })],
    });
    const initialDraft = createInitialGraphDraft(graphBundle);

    const nextDraft = applyBatchEditToDraft(initialDraft, graphBundle, 'add', [1], [11]);

    expect(resolveEffectiveTargetSerials(graphBundle, nextDraft, 1)).toEqual([10, 11]);
    expect(nextDraft.operations).toEqual([
      {
        type: 'ReplaceTriggerSourceTargets',
        triggerSourceSerial: 1,
        expectedSourceRevision: 1,
        targetCoreSerials: [10, 11],
      },
    ]);
  });

  it('applyBatchEditToDraft 会对转发器做同序号自隔离', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({
          type: 'triggerSource',
          serial: 10,
          capabilityFlags: ['repeater'],
        }),
        createTestGraphNode({
          type: 'core',
          serial: 10,
          capabilityFlags: ['repeater'],
        }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
    });
    const initialDraft = createInitialGraphDraft(graphBundle);

    const nextDraft = applyBatchEditToDraft(initialDraft, graphBundle, 'replace', [10], [10, 11]);

    expect(resolveEffectiveTargetSerials(graphBundle, nextDraft, 10)).toEqual([11]);
    expect(nextDraft.operations).toEqual([
      {
        type: 'ReplaceTriggerSourceTargets',
        triggerSourceSerial: 10,
        expectedSourceRevision: 10,
        targetCoreSerials: [11],
      },
    ]);
  });

  it('频道迁回序号后，当前目标集合会按空显式边基线起算', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({
          type: 'triggerSource',
          serial: 1,
          connectionMode: 'channel',
          channel: 7,
        }),
        createTestGraphNode({
          type: 'core',
          serial: 10,
          connectionMode: 'channel',
          channel: 7,
        }),
        createTestGraphNode({
          type: 'core',
          serial: 11,
          connectionMode: 'channel',
          channel: 7,
        }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 11 }),
      ],
    });
    const initialDraft = createInitialGraphDraft(graphBundle);

    const migratedDraft = applyChannelEditToDraft(
      initialDraft,
      graphBundle,
      ['triggerSource:1', 'core:10', 'core:11'],
      0,
    );

    expect(resolveEffectiveTargetSerials(graphBundle, migratedDraft, 1)).toEqual([]);
    expect(applyDraftToGraph(graphBundle, migratedDraft).edges).toEqual([]);
  });

  it('频道迁回序号后第一次 add 会从空显式边集合开始追加', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({
          type: 'triggerSource',
          serial: 1,
          connectionMode: 'channel',
          channel: 7,
        }),
        createTestGraphNode({
          type: 'core',
          serial: 10,
          connectionMode: 'channel',
          channel: 7,
        }),
        createTestGraphNode({
          type: 'core',
          serial: 11,
          connectionMode: 'channel',
          channel: 7,
        }),
      ],
      edges: [
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 }),
        createTestGraphEdge({ sourceSerial: 1, targetSerial: 11 }),
      ],
    });
    const initialDraft = createInitialGraphDraft(graphBundle);
    const migratedDraft = applyChannelEditToDraft(
      initialDraft,
      graphBundle,
      ['triggerSource:1', 'core:10', 'core:11'],
      0,
    );

    const nextDraft = applyBatchEditToDraft(migratedDraft, graphBundle, 'add', [1], [10]);
    const replaceOperation = nextDraft.operations.find(
      (operation) => operation.type === 'ReplaceTriggerSourceTargets',
    );

    expect(resolveEffectiveTargetSerials(graphBundle, nextDraft, 1)).toEqual([10]);
    expect(replaceOperation).toEqual({
      type: 'ReplaceTriggerSourceTargets',
      triggerSourceSerial: 1,
      expectedSourceRevision: 1,
      targetCoreSerials: [10],
    });
    expect(applyDraftToGraph(graphBundle, nextDraft).edges.map((edge) => edge.edgeKey)).toEqual([
      'triggerSource:1->core:10',
    ]);
  });

  it('applyChannelEditToDraft 与 applyDraftToGraph 会把频道覆盖折算到最终图', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 })],
    });
    const initialDraft = createInitialGraphDraft(graphBundle);

    const nextDraft = applyChannelEditToDraft(initialDraft, graphBundle, ['triggerSource:1', 'core:11'], 7);
    const effectiveGraphBundle = applyDraftToGraph(graphBundle, nextDraft);

    const triggerSourceNode = effectiveGraphBundle.nodes.find(
      (node) => node.nodeKey === 'triggerSource:1',
    );
    const channelCoreNode = effectiveGraphBundle.nodes.find((node) => node.nodeKey === 'core:11');

    expect(triggerSourceNode?.connectionMode).toBe('channel');
    expect(triggerSourceNode?.channel).toBe(7);
    expect(channelCoreNode?.connectionMode).toBe('channel');
    expect(channelCoreNode?.channel).toBe(7);
    expect(effectiveGraphBundle.edges.map((edge) => edge.edgeKey)).toEqual([
      'triggerSource:1->core:11',
    ]);
  });

  it('buildDraftDiff 会标记新增边、删除边与受影响节点', () => {
    const baseGraphBundle = createTestGraphBundle({
      nodes: [
        createTestGraphNode({ type: 'triggerSource', serial: 1 }),
        createTestGraphNode({ type: 'core', serial: 10 }),
        createTestGraphNode({ type: 'core', serial: 11 }),
      ],
      edges: [createTestGraphEdge({ sourceSerial: 1, targetSerial: 10 })],
    });
    const initialDraft = createInitialGraphDraft(baseGraphBundle);
    const nextDraft = applyBatchEditToDraft(initialDraft, baseGraphBundle, 'replace', [1], [11]);
    const effectiveGraphBundle = applyDraftToGraph(baseGraphBundle, nextDraft);

    const draftDiff = buildDraftDiff(baseGraphBundle, effectiveGraphBundle, nextDraft);

    expect(Array.from(draftDiff.changedNodeKeys).sort()).toEqual([
      'core:10',
      'core:11',
      'triggerSource:1',
    ]);
    expect(Array.from(draftDiff.addedEdgeKeys)).toEqual(['triggerSource:1->core:11']);
    expect(draftDiff.removedEdges.map((edge) => edge.edgeKey)).toEqual([
      'triggerSource:1->core:10',
    ]);
  });
});
