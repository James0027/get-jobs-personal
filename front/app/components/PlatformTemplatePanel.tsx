'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { BiBookmark, BiCheckCircle, BiRefresh, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Select } from '@/components/ui/select'
import PlatformBrand from '@/app/components/PlatformBrand'
import { apiUrl } from '@/lib/api'
import { PLATFORM_META, type PlatformId } from '@/lib/platforms'

type LoginState = 'logged_in' | 'not_logged_in' | 'checking'
type Status = { kind: 'ok' | 'error'; text: string }

interface TemplateSummary {
  id: string
  name: string
  isDefault: boolean
  version: number
}

interface EffectiveField {
  value: unknown
  source: string
}

interface EffectiveView {
  templateId: string
  templateVersion: number
  profileVersion: string
  fields: Record<string, EffectiveField>
}

interface OverrideView {
  platformId: string
  version: number
  fields: Record<string, unknown>
  effectiveCriteria: Record<string, unknown>
  sources: Record<string, string>
  draftOnlyFields: string[]
}

interface ApplyView {
  templateId: string
  templateVersion: number
  platformId: string
  appliedFields: Record<string, unknown>
  unappliedFields: string[]
  tasksStarted: boolean
  appliedAt: string
}

const PERSONAL_FIELDS: { key: string; label: string }[] = [
  { key: 'acceptedCities', label: '城市' },
  { key: 'workType', label: '工作类型' },
  { key: 'expectedSalary', label: '个人期望薪资' },
  { key: 'arrivalIntention', label: '到岗意向' },
  { key: 'linkedResumeAssetId', label: '关联简历' },
  { key: 'personalIntro', label: '个人介绍' },
  { key: 'communicationStyle', label: '沟通风格' },
  { key: 'sayHiDraft', label: '招呼语' },
]

const PLATFORM_FIELD_LABELS: Record<string, string> = {
  keywords: '关键词',
  cityCode: '城市',
  jobArea: '城市',
  salary: '平台薪资',
  experience: '经验',
  degree: '学历',
  industry: '行业',
  scale: '公司规模',
  stage: '融资阶段',
  jobType: '工作类型',
}

function readJson(response: Response): Promise<unknown> {
  return response.json().catch(() => null)
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (Array.isArray(value)) return value.length ? value.map((item) => String(item)).join('、') : '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function sourceLabel(source: string): string {
  if (source === 'PROFILE') return '继承资料'
  if (source === 'TEMPLATE') return '模板'
  if (source === 'PLATFORM_OVERRIDE') return '平台覆盖'
  if (source === 'UNSET') return '未设置'
  return source
}

function sourceClass(source: string): string {
  if (source === 'PLATFORM_OVERRIDE') return 'bg-sky-500/10 text-sky-700 dark:text-sky-300'
  if (source === 'TEMPLATE') return 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
  if (source === 'PROFILE') return 'bg-amber-500/10 text-amber-700 dark:text-amber-300'
  return 'bg-muted text-muted-foreground'
}

export default function PlatformTemplatePanel({
  platform,
  loginState,
  taskState,
  onApplied,
}: {
  platform: PlatformId
  loginState: LoginState
  taskState?: string
  onApplied?: () => void
}) {
  const [templates, setTemplates] = useState<TemplateSummary[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [effective, setEffective] = useState<EffectiveView | null>(null)
  const [override, setOverride] = useState<OverrideView | null>(null)
  const [preview, setPreview] = useState(false)
  const [applying, setApplying] = useState(false)
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState<Status | null>(null)
  const [applied, setApplied] = useState<ApplyView | null>(null)

  const loadTemplates = useCallback(async () => {
    setLoading(true)
    try {
      const response = await fetch(apiUrl('/api/search-templates'))
      const data = await readJson(response)
      if (!response.ok || !Array.isArray(data)) {
        setMessage({ kind: 'error', text: '模板加载失败' })
        return
      }
      const list = data
        .map((item) => {
          const raw = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
          return {
            id: typeof raw.id === 'string' ? raw.id : '',
            name: typeof raw.name === 'string' ? raw.name : '',
            isDefault: Boolean(raw.isDefault),
            version: typeof raw.version === 'number' ? raw.version : 0,
          }
        })
        .filter((item) => item.id)
      setTemplates(list)
      setSelectedId((current) => current || list.find((item) => item.isDefault)?.id || list[0]?.id || '')
    } catch {
      setMessage({ kind: 'error', text: '模板加载失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  const loadDetail = useCallback(async (templateId: string) => {
    if (!templateId) {
      setEffective(null)
      setOverride(null)
      return
    }
    try {
      const [effectiveResponse, overrideResponse] = await Promise.all([
        fetch(apiUrl(`/api/search-templates/${templateId}/effective`)),
        fetch(apiUrl(`/api/search-templates/${templateId}/platform-overrides`)),
      ])
      const effectiveData = await readJson(effectiveResponse)
      const overrideData = await readJson(overrideResponse)
      setEffective(effectiveResponse.ok && effectiveData && typeof effectiveData === 'object'
        ? (effectiveData as EffectiveView)
        : null)
      const item = Array.isArray(overrideData)
        ? (overrideData as unknown[]).find((entry) => {
            if (!entry || typeof entry !== 'object') return false
            return (entry as { platformId?: unknown }).platformId === platform
          })
        : null
      setOverride(item && typeof item === 'object' ? (item as OverrideView) : null)
    } catch {
      setEffective(null)
      setOverride(null)
    }
  }, [platform])

  useEffect(() => {
    void loadTemplates()
  }, [loadTemplates])

  useEffect(() => {
    void loadDetail(selectedId)
    setApplied(null)
  }, [selectedId, loadDetail])

  const applyTemplate = async () => {
    if (!selectedId) return
    setApplying(true)
    setMessage(null)
    try {
      const response = await fetch(apiUrl(`/api/search-templates/${selectedId}/apply/${platform}`), {
        method: 'POST',
      })
      const data = await readJson(response)
      if (!response.ok) {
        const error = (data as { error?: { code?: string; field?: string; message?: string } } | null)?.error
        const text = error?.code
          ? `${response.status} ${error.code}${error.field ? ` · ${error.field}` : ''}${error.message ? `：${error.message}` : ''}`
          : '套用失败'
        throw new Error(text)
      }
      setApplied(data as ApplyView)
      setMessage({ kind: 'ok', text: '已更新平台配置，未启动任务' })
      onApplied?.()
    } catch (error) {
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '套用失败' })
    } finally {
      setApplying(false)
    }
  }

  const loginText = loginState === 'logged_in' ? '已登录' : loginState === 'not_logged_in' ? '未登录' : '检测中'
  const loginClass = loginState === 'logged_in'
    ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
    : loginState === 'not_logged_in'
      ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300'
      : 'bg-muted text-muted-foreground'

  const selectedTemplate = templates.find((template) => template.id === selectedId)
  const platformValues = override?.effectiveCriteria ?? {}
  const platformSources = override?.sources ?? {}
  const unapplied = applied?.unappliedFields ?? override?.draftOnlyFields ?? []

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-2">
            <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: PLATFORM_META[platform].brandColor }} />
            <PlatformBrand platform={platform} />
            <span className={`rounded-full px-2.5 py-0.5 text-xs ${loginClass}`}>{loginText}</span>
            {taskState && <span className="rounded-full bg-muted px-2.5 py-0.5 text-xs text-muted-foreground">{taskState}</span>}
            {selectedTemplate?.isDefault && <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-xs text-primary">默认模板</span>}
          </div>
          <div className="flex items-center gap-2">
            <Select value={selectedId} onChange={(event) => setSelectedId(event.target.value)} placeholder="选择模板">
              {templates.map((template) => (
                <option key={template.id} value={template.id}>{template.name}</option>
              ))}
            </Select>
            <Button size="sm" variant="outline" onClick={() => setPreview((value) => !value)} disabled={!selectedId}>
              {preview ? '收起预览' : '预览'}
            </Button>
            <Button size="sm" onClick={applyTemplate} disabled={!selectedId || applying}>
              <BiCheckCircle />
              <span className="ml-1">{applying ? '套用中...' : '套用到配置'}</span>
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-3 pt-0">
        {loading && <p className="text-xs text-muted-foreground">加载中...</p>}
        {!loading && templates.length === 0 && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <BiBookmark />
            <span>暂无模板</span>
            <Link href="/search-template" className="underline underline-offset-2">去创建</Link>
          </div>
        )}
        {message && (
          <div className={`flex items-center justify-between gap-2 rounded-lg border p-2.5 text-xs ${
            message.kind === 'error'
              ? 'border-destructive/30 bg-destructive/10 text-destructive'
              : 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
          }`}>
            <span>{message.text}</span>
            <button type="button" onClick={() => setMessage(null)} aria-label="关闭提示"><BiX /></button>
          </div>
        )}
        {preview && (
          <div className="space-y-3 rounded-lg border border-border p-3">
            <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
              {PERSONAL_FIELDS.map((field) => {
                const entry = effective?.fields?.[field.key]
                return (
                  <div key={field.key} className="flex min-w-0 items-center justify-between gap-2 text-xs">
                    <span className="text-muted-foreground">{field.label}</span>
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className="truncate text-foreground">{formatValue(entry?.value)}</span>
                      <span className={`shrink-0 rounded px-1.5 py-0.5 ${sourceClass(entry?.source ?? 'UNSET')}`}>
                        {sourceLabel(entry?.source ?? 'UNSET')}
                      </span>
                    </span>
                  </div>
                )
              })}
            </div>
            <div className="border-t border-border pt-2">
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {Object.entries(platformValues).map(([key, value]) => (
                  <div key={key} className="flex min-w-0 items-center justify-between gap-2 text-xs">
                    <span className="text-muted-foreground">{PLATFORM_FIELD_LABELS[key] ?? key}</span>
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className="truncate text-foreground">{formatValue(value)}</span>
                      <span className={`shrink-0 rounded px-1.5 py-0.5 ${sourceClass(platformSources[key] ?? 'UNSET')}`}>
                        {sourceLabel(platformSources[key] ?? 'UNSET')}
                      </span>
                    </span>
                  </div>
                ))}
              </div>
            </div>
            {unapplied.length > 0 && (
              <p className="text-xs text-muted-foreground">未应用：{unapplied.join('、')}</p>
            )}
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <BiRefresh />
              <span>套用只更新平台配置，不启动任务</span>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
