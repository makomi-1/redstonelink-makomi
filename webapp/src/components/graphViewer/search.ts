import type { GraphNodeInfo } from '../../graphTypes';
import type { GraphSearchTypeFilter } from './types';

function normalizeSearchValue(value: string): string {
  return value.trim().toLowerCase();
}

function buildExactSearchTerms(node: GraphNodeInfo): string[] {
  const terms = [node.nodeKey, node.displayText, node.alias];
  terms.push(String(node.serial));
  terms.push(`#${node.serial}`);
  terms.push(`serial:${node.serial}`);
  if (node.channel > 0) {
    terms.push(`channel:${node.channel}`);
  }
  terms.push(`mode:${node.connectionMode}`);
  return terms;
}

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
  const normalizedQuery = normalizeSearchValue(query);
  if (!normalizedQuery) {
    return false;
  }
  return buildExactSearchTerms(node).some(
    (value) => normalizeSearchValue(value) === normalizedQuery,
  );
}
