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
  createTestWebPreferencesPayload,
} from './test/factories';
import { createJsonResponse } from './test/http';

vi.mock('./app/GraphPage', () => ({
  default: (props: {
    currentLanguage: 'zh-CN' | 'en-US';
    selectedFileName: string;
    graphBundle: unknown;
    themeId: string;
    onLanguageChange: (language: 'zh-CN' | 'en-US') => void;
    onThemeChange: (
      themeId: 'future-command' | 'lab-minimal' | 'industrial-tech',
    ) => void;
  }) => (
    <main data-theme={props.themeId}>
      <button
        type="button"
        onClick={() => props.onThemeChange('lab-minimal')}
      >
        实验室极简
      </button>
      <button
        type="button"
        onClick={() => props.onLanguageChange('en-US')}
      >
        English
      </button>
      <div data-testid="graph-page">
        graph-page:{props.selectedFileName}:{props.graphBundle ? 'loaded' : 'empty'}:{props.currentLanguage}
      </div>
    </main>
  ),
}));

vi.mock('./app/RecordingPage', () => ({
  default: () => <div data-testid="recording-page">recording-page</div>,
}));

describe('App', () => {
  it('根路径默认进入 graph 页面，并会通过 preferences 接口持久化语言与主题选择', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === './api/ping') {
        return createJsonResponse(createTestBridgePayload());
      }
      if (url === './api/preferences') {
        return createJsonResponse(
          createTestWebPreferencesPayload(),
        );
      }
      if (url === './api/storage/index') {
        return createJsonResponse(createTestStorageIndex([]));
      }
      throw new Error(`unexpected fetch url: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    window.history.replaceState({}, '', './');

    render(<App />);

    expect(screen.getByTestId('graph-page')).toHaveTextContent(
      'graph-page::empty:zh-CN',
    );
    expect(screen.getByRole('main')).toHaveAttribute(
      'data-theme',
      'future-command',
    );

    await user.click(screen.getByRole('button', { name: /实验室极简/i }));

    expect(screen.getByRole('main')).toHaveAttribute(
      'data-theme',
      'lab-minimal',
    );
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith('./api/preferences', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
        },
        body: JSON.stringify({
          language: 'zh-CN',
          themeId: 'lab-minimal',
        }),
      }),
    );

    await user.click(screen.getByRole('button', { name: 'English' }));

    expect(screen.getByTestId('graph-page')).toHaveTextContent(
      'graph-page::empty:en-US',
    );
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith('./api/preferences', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
        },
        body: JSON.stringify({
          language: 'en-US',
          themeId: 'lab-minimal',
        }),
      }),
    );
  });

  it('进入时会优先恢复客户端已持久化的语言与主题', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === './api/ping') {
        return createJsonResponse(createTestBridgePayload());
      }
      if (url === './api/preferences') {
        return createJsonResponse(
          createTestWebPreferencesPayload({
            language: 'en-US',
            themeId: 'lab-minimal',
          }),
        );
      }
      if (url === './api/storage/index') {
        return createJsonResponse(createTestStorageIndex([]));
      }
      throw new Error(`unexpected fetch url: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    window.history.replaceState({}, '', './');

    render(<App />);

    await waitFor(() =>
      expect(screen.getByTestId('graph-page')).toHaveTextContent(
        'graph-page::empty:en-US',
      ),
    );
    await waitFor(() =>
      expect(screen.getByRole('main')).toHaveAttribute(
        'data-theme',
        'lab-minimal',
      ),
    );
  });

  it('带 graph 查询参数进入时会直接打开 graph 页面并传入解析后的 bundle', async () => {
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
      if (url === './api/preferences') {
        return createJsonResponse(createTestWebPreferencesPayload());
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
    window.history.replaceState(
      {},
      '',
      './?page=graph&kind=graph&name=demo-graph.json',
    );

    render(<App />);

    await waitFor(() =>
      expect(screen.getByTestId('graph-page')).toHaveTextContent(
        'graph-page:demo-graph.json:loaded:zh-CN',
      ),
    );
  });

  it('带 recording 查询参数进入时会直接打开 recording 页面', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === './api/ping') {
        return createJsonResponse(createTestBridgePayload());
      }
      if (url === './api/preferences') {
        return createJsonResponse(createTestWebPreferencesPayload());
      }
      if (url === './api/storage/index') {
        return createJsonResponse(createTestStorageIndex([]));
      }
      throw new Error(`unexpected fetch url: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    window.history.replaceState({}, '', './?page=recording');

    render(<App />);

    expect(await screen.findByTestId('recording-page')).toBeInTheDocument();
  });
});
