import { IS_MOBILE_PLATFORM } from '@/lib/util/platform';

export const COVER_CACHE_STRATEGIES = ['lazy', 'aggressive'] as const;

export type CoverCacheStrategy = (typeof COVER_CACHE_STRATEGIES)[number];

/**
 * Mobile defaults to the library backfill: a cold on-demand ensure costs
 * 0.5–2.5 s per cover on a phone (server resize + download + WebP encode),
 * so lazy loading leaves every uncached grid crawling. Desktop keeps lazy.
 */
export const DEFAULT_COVER_CACHE_STRATEGY: CoverCacheStrategy = IS_MOBILE_PLATFORM
  ? 'aggressive'
  : 'lazy';

export function coverStrategyAllowsRoutePrefetch(_strategy: CoverCacheStrategy): boolean {
  return true;
}

export function coverStrategyAllowsLibraryBackfill(strategy: CoverCacheStrategy): boolean {
  return strategy === 'aggressive';
}

/** Map legacy auth-store `coverPrefetchStrategy` to per-server strategy. */
export function coverStrategyFromLegacyPrefetch(
  legacy: string | undefined,
): CoverCacheStrategy {
  if (legacy === 'library') return 'aggressive';
  return 'lazy';
}
