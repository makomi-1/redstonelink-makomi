import '@testing-library/jest-dom/vitest';
import { afterEach, beforeAll, afterAll, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

class MockResizeObserver implements ResizeObserver {
  observe(): void {}

  unobserve(): void {}

  disconnect(): void {}
}

beforeAll(() => {
  vi.stubGlobal('ResizeObserver', MockResizeObserver);
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn((callback: FrameRequestCallback) =>
      window.setTimeout(() => callback(Date.now()), 0),
    ),
  );
  vi.stubGlobal(
    'cancelAnimationFrame',
    vi.fn((handle: number) => window.clearTimeout(handle)),
  );
  Object.defineProperty(window, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    configurable: true,
    value: vi.fn(),
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

afterAll(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
