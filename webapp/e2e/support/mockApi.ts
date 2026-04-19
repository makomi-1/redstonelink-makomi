import type { Page, Route } from '@playwright/test';
import type { GraphSnapshotBundle, GraphWriteResponse } from '../../src/graphTypes';
import {
  createTestBridgePayload,
  createTestGraphBundle,
  createTestGraphEdge,
  createTestGraphNode,
  createTestStorageCategory,
  createTestStorageEntryPayload,
  createTestStorageEntrySummary,
  createTestStorageIndex,
} from '../../src/test/factories';

export const DEFAULT_GRAPH_FILE_NAME = 'demo-graph.json';

export const DEFAULT_GRAPH_BUNDLE: GraphSnapshotBundle = createTestGraphBundle({
  snapshotId: 'snapshot-e2e',
  graphRevision: 11,
  structureChecksum: 'checksum-e2e',
  nodes: [
    createTestGraphNode({
      type: 'triggerSource',
      serial: 1,
      alias: 'alpha',
      displayText: 'alpha(#1)',
      sourceRevision: 101,
    }),
    createTestGraphNode({
      type: 'core',
      serial: 2,
      alias: 'beta',
      displayText: 'beta(#2)',
      coreRevision: 202,
    }),
    createTestGraphNode({
      type: 'core',
      serial: 3,
      alias: 'gamma',
      displayText: 'gamma(#3)',
      coreRevision: 203,
    }),
    createTestGraphNode({
      type: 'triggerSource',
      serial: 4,
      alias: 'channel-src',
      displayText: 'channel-src(#4)',
      connectionMode: 'channel',
      channel: 7,
      sourceRevision: 104,
    }),
    createTestGraphNode({
      type: 'core',
      serial: 5,
      alias: 'channel-core-a',
      displayText: 'channel-core-a(#5)',
      connectionMode: 'channel',
      channel: 7,
      coreRevision: 205,
    }),
    createTestGraphNode({
      type: 'core',
      serial: 6,
      alias: 'channel-core-b',
      displayText: 'channel-core-b(#6)',
      connectionMode: 'channel',
      channel: 7,
      coreRevision: 206,
    }),
  ],
  edges: [
    createTestGraphEdge({ sourceSerial: 1, targetSerial: 2 }),
    createTestGraphEdge({ sourceSerial: 1, targetSerial: 3 }),
  ],
});

type MockApiOptions = {
  graphFileName?: string;
  graphBundle?: GraphSnapshotBundle;
  previewResponse?: GraphWriteResponse;
  saveResponse?: GraphWriteResponse;
  initialDrafts?: Record<string, string>;
};

function buildPreviewResponse(graphRevision: number): GraphWriteResponse {
  return {
    status: 'ok',
    result: 'preview',
    reason: '',
    message: '预检通过',
    graphRevision,
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
  };
}

function buildSaveResponse(graphRevision: number): GraphWriteResponse {
  return {
    status: 'ok',
    result: 'applied',
    reason: '',
    message: '已保存。',
    graphRevision,
    updatedNodes: [],
    preview: null,
  };
}

async function fulfillJson(route: Route, payload: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify(payload),
  });
}

/**
 * 在浏览器层统一 mock `/api/**`，让 E2E 只验证页面行为，不依赖真实 bridge。
 */
export async function installMockApi(page: Page, options: MockApiOptions = {}) {
  const graphBundle = options.graphBundle ?? DEFAULT_GRAPH_BUNDLE;
  const graphFileName = options.graphFileName ?? DEFAULT_GRAPH_FILE_NAME;
  const drafts = new Map<string, string>(Object.entries(options.initialDrafts ?? {}));
  const graphEntrySummary = createTestStorageEntrySummary({
    kind: 'graph',
    fileName: graphFileName,
    sizeBytes: JSON.stringify(graphBundle).length,
  });
  const graphEntryPayload = createTestStorageEntryPayload({
    kind: 'graph',
    fileName: graphFileName,
    textContent: JSON.stringify(graphBundle),
  });
  const storageIndexPayload = createTestStorageIndex([
    createTestStorageCategory({
      kind: 'graph',
      label: 'graph',
      entries: [graphEntrySummary],
    }),
  ]);
  const previewResponse =
    options.previewResponse ?? buildPreviewResponse(graphBundle.graphRevision);
  const saveResponse =
    options.saveResponse ?? buildSaveResponse(graphBundle.graphRevision + 1);

  await page.route('**/api/**', async (route) => {
    const requestUrl = new URL(route.request().url());
    const pathName = requestUrl.pathname;
    const method = route.request().method();

    if (pathName === '/api/ping') {
      await fulfillJson(route, createTestBridgePayload());
      return;
    }

    if (pathName === '/api/storage/index') {
      await fulfillJson(route, storageIndexPayload);
      return;
    }

    if (pathName === '/api/storage/entry') {
      const kind = requestUrl.searchParams.get('kind') ?? '';
      const fileName = requestUrl.searchParams.get('name') ?? '';
      if (kind === 'graph' && fileName === graphFileName) {
        await fulfillJson(route, graphEntryPayload);
        return;
      }
      if (kind === 'draft') {
        const draftText = drafts.get(fileName);
        if (draftText == null) {
          await fulfillJson(route, { status: 'notFound' }, 404);
          return;
        }
        await fulfillJson(route, {
          kind: 'draft',
          textContent: draftText,
        });
        return;
      }
      await fulfillJson(route, { status: 'notFound' }, 404);
      return;
    }

    if (pathName === '/api/graph/draft') {
      const draftFileName = requestUrl.searchParams.get('name') ?? '';
      if (method === 'DELETE') {
        drafts.delete(draftFileName);
        await fulfillJson(route, { status: 'deleted' });
        return;
      }
      if (method === 'POST') {
        drafts.set(draftFileName, route.request().postData() ?? '');
        await fulfillJson(route, { status: 'stored' });
        return;
      }
    }

    if (pathName === '/api/graph/preview' && method === 'POST') {
      await fulfillJson(route, previewResponse);
      return;
    }

    if (pathName === '/api/graph/save' && method === 'POST') {
      await fulfillJson(route, saveResponse);
      return;
    }

    await fulfillJson(route, { status: 'notFound', pathName, method }, 404);
  });
}

export function createPreviewErrorResponse(message: string): GraphWriteResponse {
  return {
    status: 'error',
    result: '',
    reason: 'previewError',
    message,
    graphRevision: DEFAULT_GRAPH_BUNDLE.graphRevision,
    updatedNodes: [],
    preview: null,
  };
}

export function createSaveErrorResponse(message: string): GraphWriteResponse {
  return {
    status: 'error',
    result: '',
    reason: 'saveError',
    message,
    graphRevision: DEFAULT_GRAPH_BUNDLE.graphRevision,
    updatedNodes: [],
    preview: null,
  };
}
