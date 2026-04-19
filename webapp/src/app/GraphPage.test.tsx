import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import GraphPage from './GraphPage';
import {
  createTestGraphBundle,
  createTestGraphNode,
  createTestStorageEntryPayload,
  createTestStorageEntrySummary,
} from '../test/factories';

vi.mock('../components/GraphViewer', () => ({
  default: (props: { graphFileName: string }) => (
    <div data-testid="graph-viewer">graph-viewer:{props.graphFileName}</div>
  ),
}));

describe('GraphPage', () => {
  it('未选择 graph 文件时会显示提示，并允许切换文件', async () => {
    const user = userEvent.setup();
    const onGraphFileChange = vi.fn();

    render(
      <GraphPage
        bridgeError=""
        bridgeStateClassName="status-pill is-ready"
        bridgeStateLabel="已连接"
        graphBundle={null}
        graphEntries={[
          createTestStorageEntrySummary({
            kind: 'graph',
            fileName: 'demo-graph.json',
          }),
        ]}
        graphEntryError=""
        graphEntryLoading={false}
        graphPageDirty={false}
        graphSelectedEntry={null}
        onDirtyStateChange={vi.fn()}
        onGraphFileChange={onGraphFileChange}
        onRefreshStorageIndex={vi.fn()}
        onThemeChange={vi.fn()}
        selectedFileName=""
        storageError=""
        storageLoading={false}
        themeId="future-command"
      />,
    );

    expect(
      screen.getByText('请先在上方选择一个 graph 文件再开始查看拓扑。'),
    ).toBeInTheDocument();

    await user.selectOptions(
      screen.getByRole('combobox'),
      'demo-graph.json',
    );

    expect(onGraphFileChange).toHaveBeenCalledWith('demo-graph.json');
  });

  it('有合法 graphBundle 时会渲染 GraphViewer 与原始 JSON', () => {
    const graphBundle = createTestGraphBundle({
      nodes: [createTestGraphNode({ type: 'triggerSource', serial: 1 })],
    });
    const graphSelectedEntry = createTestStorageEntryPayload({
      kind: 'graph',
      fileName: 'demo-graph.json',
      textContent: '{"kind":"graphSnapshotBundle"}',
    });

    render(
      <GraphPage
        bridgeError=""
        bridgeStateClassName="status-pill is-ready"
        bridgeStateLabel="已连接"
        graphBundle={graphBundle}
        graphEntries={[]}
        graphEntryError=""
        graphEntryLoading={false}
        graphPageDirty={false}
        graphSelectedEntry={graphSelectedEntry}
        onDirtyStateChange={vi.fn()}
        onGraphFileChange={vi.fn()}
        onRefreshStorageIndex={vi.fn()}
        onThemeChange={vi.fn()}
        selectedFileName="demo-graph.json"
        storageError=""
        storageLoading={false}
        themeId="future-command"
      />,
    );

    expect(screen.getByTestId('graph-viewer')).toHaveTextContent(
      'graph-viewer:demo-graph.json',
    );
    expect(screen.getByText('原始 JSON')).toBeInTheDocument();
    expect(screen.getByText('{"kind":"graphSnapshotBundle"}')).toBeInTheDocument();
  });
});
