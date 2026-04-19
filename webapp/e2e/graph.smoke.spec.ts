import { expect, test, type Page } from '@playwright/test';
import { serializeGraphDraft } from '../src/graphTypes';
import { createTestGraphDraft } from '../src/test/factories';
import { buildDraftFileName } from '../src/components/graphViewer/draft';
import {
  DEFAULT_GRAPH_BUNDLE,
  DEFAULT_GRAPH_FILE_NAME,
  createPreviewErrorResponse,
  createSaveErrorResponse,
  installMockApi,
} from './support/mockApi';

async function gotoGraphPage(page: Page) {
  await page.goto(`/?page=graph&kind=graph&name=${encodeURIComponent(DEFAULT_GRAPH_FILE_NAME)}`);
  await expect(page.getByRole('heading', { name: 'graph 拓扑分析' })).toBeVisible();
}

test.describe('graph browser smoke', () => {
  test('首页点击 graph 资产会切到 graph 页面', async ({ page }) => {
    await installMockApi(page);

    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'RedstoneLink Web Tools' })).toBeVisible();
    await page.getByRole('button', { name: /demo-graph\.json/i }).click();

    await expect(page).toHaveURL(
      new RegExp(`page=graph&kind=graph&name=${encodeURIComponent(DEFAULT_GRAPH_FILE_NAME)}`),
    );
    await expect(page.getByRole('heading', { name: 'graph 拓扑分析' })).toBeVisible();
  });

  test('带 graph 查询参数时会直接进入 graph 页面', async ({ page }) => {
    await installMockApi(page);

    await gotoGraphPage(page);

    await expect(
      page
        .getByRole('definition')
        .filter({ hasText: new RegExp(`^${DEFAULT_GRAPH_FILE_NAME}$`) }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save' })).toBeVisible();
  });

  test('搜索只有在应用后才刷新，并且清空后会复位', async ({ page }) => {
    await installMockApi(page);

    await gotoGraphPage(page);

    const searchInput = page.getByLabel('搜索节点');
    await searchInput.fill('alpha');

    await expect(page.getByText('搜索条件未应用')).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'triggerSource #1 · alpha(#1)' }),
    ).toHaveCount(0);

    await page.getByRole('button', { name: '应用搜索' }).click();

    await expect(page.getByText('搜索条件已应用')).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'triggerSource #1 · alpha(#1)' }),
    ).toBeVisible();

    await page.getByRole('button', { name: '清空搜索' }).click();

    await expect(searchInput).toHaveValue('');
    await expect(
      page.getByRole('button', { name: 'triggerSource #1 · alpha(#1)' }),
    ).toHaveCount(0);
  });

  test('预检失败和保存失败提示会反馈到页面', async ({ page }) => {
    await installMockApi(page, {
      previewResponse: createPreviewErrorResponse('Graph preview request timed out.'),
      saveResponse: createSaveErrorResponse('Graph save request timed out.'),
    });

    await gotoGraphPage(page);

    const aliasInput = page.getByLabel('Alias');
    await aliasInput.fill('renamed');
    await page.getByRole('button', { name: '应用到草稿' }).click();

    await expect(
      page.getByText('已将节点别名写入本地草稿，点击 Save 后才会回传游戏真值。'),
    ).toBeVisible();
    await expect(page.getByText('Graph preview request timed out.')).toBeVisible();

    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Graph save request timed out.')).toBeVisible();
  });

  test('存在本地草稿时会恢复草稿，并在保存成功后清空脏状态', async ({ page }) => {
    const draftFileName = buildDraftFileName(
      DEFAULT_GRAPH_FILE_NAME,
      DEFAULT_GRAPH_BUNDLE.snapshotId,
    );
    await installMockApi(page, {
      initialDrafts: {
        [draftFileName]: serializeGraphDraft(
          createTestGraphDraft({
            baseSnapshotId: DEFAULT_GRAPH_BUNDLE.snapshotId,
            dirty: true,
            operations: [
              {
                type: 'RenameNodeAlias',
                nodeType: 'triggerSource',
                serial: 1,
                alias: 'draft-alpha',
              },
            ],
          }),
        ),
      },
    });

    await gotoGraphPage(page);

    const aliasInput = page.getByLabel('Alias');
    await expect(aliasInput).toHaveValue('draft-alpha');
    await expect(page.getByText('预检通过')).toBeVisible();

    const saveButton = page.getByRole('button', { name: 'Save' });
    await expect(saveButton).toBeEnabled();
    await saveButton.click();

    await expect(page.getByText('已保存。')).toBeVisible();
    await expect(saveButton).toBeDisabled();
  });

  test('可以切换序号和频道视图，并通过点击聚合块更新详情区', async ({ page }) => {
    await installMockApi(page);

    await gotoGraphPage(page);

    await expect(page.getByText('+2 grouped cores')).toBeVisible();

    await page.getByRole('button', { name: '频道' }).click();
    await expect(
      page.getByTestId('rf__node-channelHub:7').getByText('channel #7'),
    ).toBeVisible();

    await page.getByRole('button', { name: '序号' }).click();
    await page.getByText('+2 grouped cores').click();

    await expect(page.getByText('2 个聚合成员')).toBeVisible();
    await expect(page.getByText('core aggregate')).toBeVisible();
  });
});
