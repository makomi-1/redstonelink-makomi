import type { AppLocation, AppPage } from './types';

export function buildEntryKey(kind: string, fileName: string): string {
  return `${kind}:${fileName}`;
}

export function parseAppLocation(): AppLocation {
  if (typeof window === 'undefined') {
    return {
      page: 'graph',
      kind: '',
      fileName: '',
    };
  }
  const params = new URLSearchParams(window.location.search);
  const pageParam = params.get('page');
  const kindParam = params.get('kind') ?? '';
  const fileName = params.get('name') ?? '';
  const page: AppPage =
    pageParam === 'recording' || kindParam === 'recording' ? 'recording' : 'graph';
  const kind =
    kindParam ||
    (page === 'recording' && fileName
      ? 'recording'
      : page === 'graph' && fileName
        ? 'graph'
        : '');
  return {
    page,
    kind,
    fileName,
  };
}

export function buildAppHref(location: AppLocation): string {
  const params = new URLSearchParams();
  if (location.page === 'recording') {
    params.set('page', location.page);
  }
  if (location.kind) {
    params.set('kind', location.kind);
  }
  if (location.fileName) {
    params.set('name', location.fileName);
  }
  const search = params.toString();
  return search ? `./?${search}` : './';
}
