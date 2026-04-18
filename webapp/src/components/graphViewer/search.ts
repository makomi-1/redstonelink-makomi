import type { GraphNodeInfo } from '../../graphTypes';
import type { GraphSearchTypeFilter } from './types';

export function formatSearchTypeLabel(searchTypeFilter: GraphSearchTypeFilter): string {
  if (searchTypeFilter === 'all') {
    return '全部';
  }
  return searchTypeFilter;
}

export function buildSearchResultLabel(node: GraphNodeInfo): string {
  return `${node.type} #${node.serial} · ${node.displayText}`;
}

export function matchesSearchType(
  node: GraphNodeInfo,
  searchTypeFilter: GraphSearchTypeFilter,
): boolean {
  return searchTypeFilter === 'all' || node.type === searchTypeFilter;
}

export function matchesSearch(node: GraphNodeInfo, query: string): boolean {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return false;
  }
  return [
    node.nodeKey,
    node.displayText,
    node.alias,
    node.type,
    node.connectionMode,
    String(node.serial),
    String(node.channel),
  ].some((value) => value.toLowerCase().includes(normalizedQuery));
}
