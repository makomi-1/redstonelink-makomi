import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import GraphViewer from './GraphViewer';
import {
  createTestGraphBundle,
  createTestGraphDraft,
  createTestGraphEdge,
  createTestGraphNode,
} from '../test/factories';
import { createJsonResponse } from '../test/http';
import { serializeGraphDraft } from '../graphTypes';

vi.mock('reactflow', async () => {
  const reactModule = await import('react');

  return {
    default: ({
      nodes,
      edges,
      onInit,
      children,
    }: {
      nodes: Array<{ id: string }>;
      edges: Array<{ id: string }>;
      onInit?: (instance: unknown) => void;
      children?: React.ReactNode;
    }) => {
      reactModule.useEffect(() => {
        onInit?.({
          fitView: vi.fn(),
          setCenter: vi.fn(),
        });
      }, [onInit]);
      return (
        <div data-testid="reactflow">
          <div data-testid="reactflow-node-count">{nodes.length}</div>
          <div data-testid="reactflow-edge-count">{edges.length}</div>
          {children}
        </div>
      );
    },
    Background: () => <div data-testid="reactflow-background" />,
    Controls: () => <div data-testid="reactflow-controls" />,
    MiniMap: () => <div data-testid="reactflow-minimap" />,
    SelectionMode: {
      Full: 'full',
    },
    Position: {
      Left: 'left',
      Right: 'right',
    },
    useNodesState: (initialNodes: unknown[] = []) => {
      const [nodes, setNodes] = reactModule.useState(initialNodes);
      return [nodes, setNodes, vi.fn()] as const;
    },
    useEdgesState: (initialEdges: unknown[] = []) => {
      const [edges, setEdges] = reactModule.useState(initialEdges);
      return [edges, setEdges, vi.fn()] as const;
    },
  };
});

type GraphViewerFetchMockOptions = {
  draftText?: string | null;
};

function createGraphViewerFetchMock(options: GraphViewerFetchMockOptions = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.startsWith('./api/storage/entry?kind=draft&name=')) {
      if (options.draftText != null) {
        return createJsonResponse({
          kind: 'draft',
          textContent: options.draftText,
        });
      }
      return {
        ok: false,
        status: 404,
        json: async () => ({}),
      } as Response;
    }
    if (url.startsWith('./api/graph/draft?name=')) {
      return createJsonResponse({}, { status: method === 'DELETE' ? 204 : 200 });
    }
    if (url === './api/graph/preview') {
      return createJsonResponse({
        status: 'ok',
        result: 'preview',
        reason: '',
        message: '预检通过',
        graphRevision: 1,
        updatedNodes: [],
        preview: {
          aliasCost: 1,
          graphCost: 2,
          graphWriteUnitCount: 1,
          aliasAllowed: true,
          graphAllowed: true,
          aliasHardBlocked: false,
          graphHardBlocked: false,
          aliasWaitTicks: 0,
          graphWaitTicks: 0,
          canSave: true,
        },
      });
    }
    if (url === './api/graph/save') {
      return createJsonResponse({
        status: 'ok',
        result: 'applied',
        reason: '',
        message: '已保存',
        graphRevision: 2,
        updatedNodes: [],
        preview: null,
      });
    }
    throw new Error(`unexpected fetch url: ${method} ${url}`);
  });
}

describe('GraphViewer', () => {
  const graphBundle = createTestGraphBundle({
    nodes: [
      createTestGraphNode({
        type: 'triggerSource',
        serial: 1,
        alias: 'alpha',
        displayText: 'alpha(#1)',
      }),
      createTestGraphNode({
        type: 'core',
        serial: 2,
        alias: 'beta',
        displayText: 'beta(#2)',
      }),
    ],
    edges: [createTestGraphEdge({ sourceSerial: 1, targetSerial: 2 })],
  });

  it('搜索输入态与应用态分离，只有应用后才刷新结果', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', createGraphViewerFetchMock());

    render(
      <GraphViewer
        graphBundle={graphBundle}
        graphFileName="demo-graph.json"
      />,
    );

    const searchInput = await screen.findByLabelText('搜索节点');
    await user.type(searchInput, 'alpha');

    expect(screen.getByText('搜索条件未应用')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'triggerSource #1 · alpha(#1)' }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '应用搜索' }));

    await waitFor(() =>
      expect(screen.getByText('搜索条件已应用')).toBeInTheDocument(),
    );
    expect(
      screen.getByRole('button', { name: 'triggerSource #1 · alpha(#1)' }),
    ).toBeInTheDocument();
  });

  it('应用别名草稿后会压入撤回历史，并支持一步撤回', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', createGraphViewerFetchMock());

    render(
      <GraphViewer
        graphBundle={graphBundle}
        graphFileName="demo-graph.json"
      />,
    );

    const aliasInput = await screen.findByLabelText('Alias');
    await user.clear(aliasInput);
    await user.type(aliasInput, 'renamed');
    await user.click(screen.getByRole('button', { name: '应用到草稿' }));

    await waitFor(() =>
      expect(
        screen.getByText('已将节点别名写入本地草稿，点击 Save 后才会回传游戏真值。'),
      ).toBeInTheDocument(),
    );

    const undoButton = screen.getByRole('button', { name: '撤回草稿' });
    expect(undoButton).toBeEnabled();

    await user.click(undoButton);

    await waitFor(() =>
      expect(screen.getByText('已撤回最近一次本地草稿应用。')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: '撤回草稿' })).toBeDisabled();
  });

  it('加载匹配 snapshot 的本地草稿后，会恢复草稿别名并触发预检', async () => {
    const restoredDraftText = serializeGraphDraft(
      createTestGraphDraft({
        baseSnapshotId: graphBundle.snapshotId,
        dirty: true,
        operations: [
          {
            type: 'RenameNodeAlias',
            nodeType: 'triggerSource',
            serial: 1,
            alias: 'restored',
          },
        ],
      }),
    );
    vi.stubGlobal(
      'fetch',
      createGraphViewerFetchMock({
        draftText: restoredDraftText,
      }),
    );

    render(
      <GraphViewer
        graphBundle={graphBundle}
        graphFileName="demo-graph.json"
      />,
    );

    const aliasInput = await screen.findByLabelText('Alias');
    await waitFor(() =>
      expect(aliasInput).toHaveValue('restored'),
    );
    await waitFor(() =>
      expect(screen.getByText('预检通过')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('保存成功后会清空草稿脏状态，并保留应用后的节点别名', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', createGraphViewerFetchMock());

    render(
      <GraphViewer
        graphBundle={graphBundle}
        graphFileName="demo-graph.json"
      />,
    );

    const aliasInput = await screen.findByLabelText('Alias');
    await user.clear(aliasInput);
    await user.type(aliasInput, 'saved-name');
    await user.click(screen.getByRole('button', { name: '应用到草稿' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled(),
    );

    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(screen.getByText('已保存')).toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled(),
    );
    expect(screen.getByRole('button', { name: '撤回草稿' })).toBeDisabled();
    expect(screen.getByLabelText('Alias')).toHaveValue('saved-name');
  });
});
