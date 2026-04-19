import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import {
  createTestBridgePayload,
  createTestGraphBundle,
  createTestGraphNode,
  createTestStorageCategory,
  createTestStorageEntryPayload,
  createTestStorageEntrySummary,
  createTestStorageIndex,
} from './test/factories';
import { createJsonResponse } from './test/http';

vi.mock('./app/GraphPage', () => ({
  default: (props: { selectedFileName: string; graphBundle: unknown }) => (
    <div data-testid="graph-page">
      graph-page:{props.selectedFileName}:{props.graphBundle ? 'loaded' : 'empty'}
    </div>
  ),
}));

vi.mock('./app/RecordingPage', () => ({
  default: () => <div data-testid="recording-page">recording-page</div>,
}));

describe('App', () => {
  it('首页点击 graph 资产后会切换到 graph 页面并传入解析后的 bundle', async () => {
    const user = userEvent.setup();
    const graphBundle = createTestGraphBundle({
      nodes: [createTestGraphNode({ type: 'triggerSource', serial: 1 })],
    });
    const graphEntry = createTestStorageEntrySummary({
      kind: 'graph',
      fileName: 'demo-graph.json',
    });
    const graphEntryPayload = createTestStorageEntryPayload({
      kind: 'graph',
      fileName: 'demo-graph.json',
      textContent: JSON.stringify(graphBundle),
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === './api/ping') {
        return createJsonResponse(createTestBridgePayload());
      }
      if (url === './api/storage/index') {
        return createJsonResponse(
          createTestStorageIndex([
            createTestStorageCategory({
              kind: 'graph',
              label: 'graph',
              entries: [graphEntry],
            }),
          ]),
        );
      }
      if (url === './api/storage/entry?kind=graph&name=demo-graph.json') {
        return createJsonResponse(graphEntryPayload);
      }
      throw new Error(`unexpected fetch url: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    window.history.replaceState({}, '', './');

    render(<App />);

    const graphEntryButton = await screen.findByRole('button', {
      name: /demo-graph\.json/i,
    });
    await user.click(graphEntryButton);

    await waitFor(() =>
      expect(screen.getByTestId('graph-page')).toHaveTextContent(
        'graph-page:demo-graph.json:loaded',
      ),
    );
  });

  it('带 graph 查询参数进入时会直接打开 graph 页面', async () => {
    const graphBundle = createTestGraphBundle({
      nodes: [createTestGraphNode({ type: 'triggerSource', serial: 1 })],
    });
    const graphEntry = createTestStorageEntrySummary({
      kind: 'graph',
      fileName: 'query-graph.json',
    });
    const graphEntryPayload = createTestStorageEntryPayload({
      kind: 'graph',
      fileName: 'query-graph.json',
      textContent: JSON.stringify(graphBundle),
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === './api/ping') {
        return createJsonResponse(createTestBridgePayload());
      }
      if (url === './api/storage/index') {
        return createJsonResponse(
          createTestStorageIndex([
            createTestStorageCategory({
              kind: 'graph',
              label: 'graph',
              entries: [graphEntry],
            }),
          ]),
        );
      }
      if (url === './api/storage/entry?kind=graph&name=query-graph.json') {
        return createJsonResponse(graphEntryPayload);
      }
      throw new Error(`unexpected fetch url: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    window.history.replaceState(
      {},
      '',
      './?page=graph&kind=graph&name=query-graph.json',
    );

    render(<App />);

    await waitFor(() =>
      expect(screen.getByTestId('graph-page')).toHaveTextContent(
        'graph-page:query-graph.json:loaded',
      ),
    );
  });
});
