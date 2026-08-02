import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import {
  DEFAULT_COVER_CACHE_STRATEGY,
  coverStrategyFromLegacyPrefetch,
  type CoverCacheStrategy,
} from '@/lib/library/coverStrategy';
import { useAuthStore } from './authStore';
import { serverIndexKeyFromUrl } from '@/lib/server/serverIndexKey';
import type { ServerProfile } from './authStoreTypes';

const resolveStrategyKey = (serverId: string): string => {
  const server = useAuthStore.getState().servers.find(s => s.id === serverId);
  if (!server) return serverId;
  return serverIndexKeyFromUrl(server.url) || serverId;
};

function readLegacyGlobalStrategy(): CoverCacheStrategy | null {
  try {
    const raw = localStorage.getItem('psysonic-auth');
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { state?: { coverPrefetchStrategy?: string } };
    const legacy = parsed?.state?.coverPrefetchStrategy;
    if (!legacy) return null;
    return coverStrategyFromLegacyPrefetch(legacy);
  } catch {
    return null;
  }
}

interface CoverStrategyState {
  strategy: CoverCacheStrategy;
  strategyByServer: Record<string, CoverCacheStrategy | undefined>;
  setStrategy: (strategy: CoverCacheStrategy) => void;
  setServerStrategy: (serverId: string, strategy: CoverCacheStrategy) => void;
  clearServerOverrides: (serverId: string) => void;
  migrateServerOverrides: (servers: ServerProfile[]) => void;
  getStrategyForServer: (serverId: string | null | undefined) => CoverCacheStrategy;
}

export const useCoverStrategyStore = create<CoverStrategyState>()(
  persist(
    (set, get) => ({
      strategy: DEFAULT_COVER_CACHE_STRATEGY,
      strategyByServer: {},
      setStrategy: strategy => set({ strategy }),
      setServerStrategy: (serverId, strategy) =>
        set(s => ({
          strategyByServer: { ...s.strategyByServer, [resolveStrategyKey(serverId)]: strategy },
        })),
      clearServerOverrides: (serverId) =>
        set(s => {
          const key = resolveStrategyKey(serverId);
          const { [serverId]: _, [key]: __, ...strategyByServer } = s.strategyByServer;
          return { strategyByServer };
        }),
      migrateServerOverrides: (servers) =>
        set(s => {
          if (servers.length === 0) return {};
          let changed = false;
          const strategyByServer = { ...s.strategyByServer };
          for (const server of servers) {
            const key = serverIndexKeyFromUrl(server.url) || server.id;
            if (key === server.id) continue;
            const legacyStrategy = strategyByServer[server.id];
            const nextStrategy = strategyByServer[key];
            if (legacyStrategy !== undefined && nextStrategy !== undefined) {
              delete strategyByServer[server.id];
              changed = true;
            } else if (legacyStrategy !== undefined && nextStrategy === undefined) {
              strategyByServer[key] = legacyStrategy;
              delete strategyByServer[server.id];
              changed = true;
            }
          }
          return changed ? { strategyByServer } : {};
        }),
      getStrategyForServer: serverId => {
        if (!serverId) return DEFAULT_COVER_CACHE_STRATEGY;
        const key = resolveStrategyKey(serverId);
        return get().strategyByServer[key] ?? get().strategyByServer[serverId] ?? get().strategy;
      },
    }),
    {
      name: 'psysonic-cover-cache-strategy',
      storage: createJSONStorage(() => localStorage),
      version: 2,
      migrate: (persisted, version) => {
        const fallback = {
          strategy: DEFAULT_COVER_CACHE_STRATEGY,
          strategyByServer: {} as Record<string, CoverCacheStrategy | undefined>,
        };
        let next: typeof fallback;
        if (version < 1) {
          const legacyGlobal = readLegacyGlobalStrategy();
          const old = persisted as { strategy?: CoverCacheStrategy; strategyByServer?: Record<string, CoverCacheStrategy> };
          next = {
            strategy: legacyGlobal ?? old.strategy ?? fallback.strategy,
            strategyByServer: old.strategyByServer ?? fallback.strategyByServer,
          };
        } else {
          const current = persisted as Partial<typeof fallback>;
          next = {
            strategy: current.strategy ?? fallback.strategy,
            strategyByServer: current.strategyByServer ?? fallback.strategyByServer,
          };
        }
        // v2: the global default became platform-dependent (mobile →
        // 'aggressive'). The store persists eagerly, so a pre-v2 'lazy' is
        // indistinguishable from "never chose" — and 'lazy' was the old
        // universal default. Treat it as unset and adopt the platform
        // default; choices made at v2 are never rewritten.
        if (version < 2 && next.strategy === 'lazy') {
          next.strategy = DEFAULT_COVER_CACHE_STRATEGY;
        }
        return next;
      },
      partialize: s => ({
        strategy: s.strategy,
        strategyByServer: s.strategyByServer,
      }),
    },
  ),
);
