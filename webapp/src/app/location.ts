import type { AppLocation, AppPage } from './types';

export function buildEntryKey(kind: string, fileName: string): string {
  return `${kind}:${fileName}`;
}

export function parseAppLocation(): AppLocation {
  if (typeof window === 'undefined') {
    return {
      page: 'home',
      kind: '',
      fileName: '',
    };
  }
  const params = new URLSearchParams(window.location.search);
  const pageParam = params.get('page');
  const page: AppPage = pageParam === 'recording' || pageParam === 'graph' ? pageParam : 'home';
  const fileName = params.get('name') ?? '';
  const kind =
    params.get('kind') ??
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
  if (location.page !== 'home') {
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
