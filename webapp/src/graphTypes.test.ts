import { describe, expect, it } from 'vitest';
import {
  parseGraphDraft,
  parseGraphWriteResponse,
} from './graphTypes';

describe('graphTypes', () => {
  it('parseGraphDraft 会过滤非法操作并归一化字段', () => {
    const parsedDraft = parseGraphDraft(
      JSON.stringify({
        draftId: '  ',
        baseSnapshotId: 'snapshot-1',
        mode: '  ',
        baseGraphRevision: 7,
        dirty: true,
        layoutVersion: 2,
        operations: [
          {
            type: 'RenameNodeAlias',
            nodeType: 'triggerSource',
            serial: 12.7,
            alias: '  alpha  ',
          },
          {
            type: 'ReplaceTriggerSourceTargets',
            triggerSourceSerial: 3,
            expectedSourceRevision: 5,
            targetCoreSerials: [9, 9, 4, 'x', -1],
          },
          {
            type: 'SetNodeChannel',
            nodeType: 'core',
            serial: 8,
            expectedSourceRevision: 11,
            expectedCoreRevision: 13,
            channel: 6.9,
          },
          {
            type: 'UnknownOperation',
          },
        ],
      }),
      'draft',
    );

    expect(parsedDraft).toEqual({
      draftId: 'draft-snapshot-1',
      baseSnapshotId: 'snapshot-1',
      mode: 'serial',
      baseGraphRevision: 7,
      dirty: true,
      layoutVersion: 2,
      operations: [
        {
          type: 'RenameNodeAlias',
          nodeType: 'triggerSource',
          serial: 12.7,
          alias: 'alpha',
        },
        {
          type: 'ReplaceTriggerSourceTargets',
          triggerSourceSerial: 3,
          expectedSourceRevision: 5,
          targetCoreSerials: [9, 4],
        },
        {
          type: 'SetNodeChannel',
          nodeType: 'core',
          serial: 8,
          expectedSourceRevision: 11,
          expectedCoreRevision: 13,
          channel: 6.9,
        },
      ],
    });
  });

  it('parseGraphDraft 在 kind 不匹配或 JSON 非法时返回 null', () => {
    expect(parseGraphDraft('{"baseSnapshotId":"snapshot-1"}', 'graph')).toBeNull();
    expect(parseGraphDraft('{broken json', 'draft')).toBeNull();
  });

  it('parseGraphWriteResponse 会回退 nodeKey 解析并归一化 preview', () => {
    const parsedResponse = parseGraphWriteResponse({
      status: 'error',
      result: 'preview',
      reason: '  limited  ',
      message: '  blocked  ',
      graphRevision: 17,
      changedNodeCount: 0,
      refreshRequired: false,
      updatedNodes: [
        {
          nodeKey: 'core:2',
          alias: '  beta  ',
          displayText: '',
          connectionMode: '',
          channel: 'invalid',
          sourceRevision: 3,
        },
        {
          nodeKey: 'triggerSource:5',
          nodeType: 'triggerSource',
          serial: 5,
          coreRevision: 8,
        },
        {
          nodeKey: 'invalid',
          nodeType: 'core',
        },
        null,
      ],
      preview: {
        aliasCost: 1,
        graphCost: 4,
        graphWriteUnitCount: 9,
        aliasAllowed: true,
        graphAllowed: false,
        aliasHardBlocked: false,
        graphHardBlocked: true,
        aliasWaitTicks: 2,
        graphWaitTicks: 7,
        canSave: false,
      },
    });

    expect(parsedResponse).toEqual({
      status: 'error',
      result: 'preview',
      reason: 'limited',
      message: 'blocked',
      graphRevision: 17,
      changedNodeCount: 0,
      refreshRequired: false,
      updatedNodes: [
        {
          nodeKey: 'core:2',
          nodeType: 'core',
          serial: 2,
          alias: 'beta',
          displayText: 'core:2',
          connectionMode: 'serial',
          channel: 0,
          sourceRevision: 3,
          coreRevision: undefined,
        },
        {
          nodeKey: 'triggerSource:5',
          nodeType: 'triggerSource',
          serial: 5,
          alias: undefined,
          displayText: undefined,
          connectionMode: undefined,
          channel: undefined,
          sourceRevision: undefined,
          coreRevision: 8,
        },
      ],
      preview: {
        aliasCost: 1,
        graphCost: 4,
        graphWriteUnitCount: 9,
        aliasAllowed: true,
        graphAllowed: false,
        aliasHardBlocked: false,
        graphHardBlocked: true,
        aliasWaitTicks: 2,
        graphWaitTicks: 7,
        canSave: false,
      },
    });
  });

  it('parseGraphWriteResponse 会解析 applied 摘要字段并允许缺省 updatedNodes', () => {
    expect(
      parseGraphWriteResponse({
        status: 'ok',
        result: 'applied',
        reason: 'applied',
        message: '已保存',
        graphRevision: 18,
        changedNodeCount: 7,
        refreshRequired: true,
      }),
    ).toEqual({
      status: 'ok',
      result: 'applied',
      reason: 'applied',
      message: '已保存',
      graphRevision: 18,
      changedNodeCount: 7,
      refreshRequired: true,
      updatedNodes: [],
      preview: null,
    });
  });
});
