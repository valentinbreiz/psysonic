import { beforeEach, describe, expect, it, vi } from 'vitest';

const KEY = 'psysonic-cover-cache-strategy';

function seed(payload: unknown): void {
  localStorage.setItem(KEY, JSON.stringify(payload));
}

/**
 * The persisted default is platform-dependent since v2, so the store must be
 * imported fresh with the platform module mocked — rehydration (and thus
 * `migrate`) runs synchronously at module import.
 */
async function importStoreFresh(mobile: boolean) {
  vi.resetModules();
  vi.doMock('@/lib/util/platform', () => ({
    IS_ANDROID: mobile,
    IS_IOS: false,
    IS_MOBILE_PLATFORM: mobile,
    IS_LINUX: !mobile,
    IS_MACOS: false,
    IS_WINDOWS: false,
  }));
  const mod = await import('./coverStrategyStore');
  return mod.useCoverStrategyStore;
}

describe('coverStrategyStore v2 migration (platform-dependent default)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('mobile: flips a pre-v2 "lazy" (the old universal default) to "aggressive"', async () => {
    seed({ state: { strategy: 'lazy', strategyByServer: {} }, version: 1 });
    const store = await importStoreFresh(true);
    expect(store.getState().strategy).toBe('aggressive');
  });

  it('desktop: leaves a pre-v2 "lazy" untouched', async () => {
    seed({ state: { strategy: 'lazy', strategyByServer: {} }, version: 1 });
    const store = await importStoreFresh(false);
    expect(store.getState().strategy).toBe('lazy');
  });

  it('keeps an explicit pre-v2 "aggressive" and per-server overrides on both platforms', async () => {
    for (const mobile of [true, false]) {
      localStorage.clear();
      seed({
        state: { strategy: 'aggressive', strategyByServer: { 'demo.example': 'lazy' } },
        version: 1,
      });
      const store = await importStoreFresh(mobile);
      expect(store.getState().strategy).toBe('aggressive');
      expect(store.getState().strategyByServer['demo.example']).toBe('lazy');
    }
  });

  it('mobile: never rewrites a "lazy" chosen at v2', async () => {
    seed({ state: { strategy: 'lazy', strategyByServer: {} }, version: 2 });
    const store = await importStoreFresh(true);
    expect(store.getState().strategy).toBe('lazy');
  });
});
