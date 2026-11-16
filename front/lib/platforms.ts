export type PlatformId = 'BOSS' | 'LIEPIN' | 'JOB51' | 'ZHILIAN'

export interface PlatformMeta {
  label: string
  shortLabel: string
  icon: string
  brandColor: string
}

// Icons: official site favicons downloaded 2026-11-02 from zhipin.com, liepin.com,
// 51job.com and zhaopin.com. Brand colors are used only for small identification accents.
export const PLATFORM_META: Record<PlatformId, PlatformMeta> = {
  BOSS: { label: 'BOSS直聘', shortLabel: 'BOSS', icon: '/brands/boss.ico', brandColor: '#00A6A7' },
  LIEPIN: { label: '猎聘', shortLabel: '猎聘', icon: '/brands/liepin.ico', brandColor: '#E8590C' },
  JOB51: { label: '51job', shortLabel: '51job', icon: '/brands/51job.ico', brandColor: '#E60012' },
  ZHILIAN: { label: '智联招聘', shortLabel: '智联', icon: '/brands/zhilian.ico', brandColor: '#0052D9' },
}

export const PLATFORM_IDS: PlatformId[] = ['BOSS', 'LIEPIN', 'JOB51', 'ZHILIAN']
