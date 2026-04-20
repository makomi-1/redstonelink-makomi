import type { GraphNodeInfo } from '../../graphTypes';
import { pickLocalizedText, type AppLanguage } from '../../app/i18n';
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

export function formatSearchTypeLabel(
  searchTypeFilter: GraphSearchTypeFilter,
  language: AppLanguage,
): string {
  if (searchTypeFilter === 'all') {
    return pickLocalizedText(language, '全部', 'All');
  }
  return searchTypeFilter;
}

export function buildSearchResultLabel(
  node: GraphNodeInfo,
  language: AppLanguage,
): string {
  return pickLocalizedText(
    language,
    `${node.type} #${node.serial} · ${node.displayText}`,
    `${node.type} #${node.serial} · ${node.displayText}`,
  );
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
