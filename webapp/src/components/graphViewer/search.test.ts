import { describe, expect, it } from 'vitest';
import {
  buildSearchResultLabel,
  formatSearchTypeLabel,
  matchesSearch,
  matchesSearchType,
} from './search';
import { createTestGraphNode } from '../../test/factories';

describe('graphViewer/search', () => {
  const triggerSourceNode = createTestGraphNode({
    type: 'triggerSource',
    serial: 12,
    alias: 'Alpha',
    displayText: 'Alpha(#12)',
    connectionMode: 'channel',
    channel: 3,
  });

  it('matchesSearch 采用精确匹配而不是模糊包含', () => {
    expect(matchesSearch(triggerSourceNode, 'Alpha')).toBe(true);
    expect(matchesSearch(triggerSourceNode, '#12')).toBe(true);
    expect(matchesSearch(triggerSourceNode, 'serial:12')).toBe(true);
    expect(matchesSearch(triggerSourceNode, 'channel:3')).toBe(true);
    expect(matchesSearch(triggerSourceNode, '1')).toBe(false);
    expect(matchesSearch(triggerSourceNode, '2-9')).toBe(false);
    expect(matchesSearch(triggerSourceNode, 'Alpha(#1)')).toBe(false);
  });

  it('matchesSearchType 与文案格式化会按节点类型输出', () => {
    expect(matchesSearchType(triggerSourceNode, 'triggerSource')).toBe(true);
    expect(matchesSearchType(triggerSourceNode, 'core')).toBe(false);
    expect(matchesSearchType(triggerSourceNode, 'all')).toBe(true);
    expect(formatSearchTypeLabel('all')).toBe('全部');
    expect(formatSearchTypeLabel('core')).toBe('core');
    expect(buildSearchResultLabel(triggerSourceNode)).toBe(
      'triggerSource #12 · Alpha(#12)',
    );
  });
});
