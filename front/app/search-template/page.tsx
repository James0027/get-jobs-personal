'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { BiBookmark, BiPlus, BiSave, BiTrash, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import PageHeader from '@/app/components/PageHeader'
import { apiUrl } from '@/lib/api'

const CRITERIA_FIELDS = [
  { key: 'targetTitles', label: '目标岗位' },
  { key: 'keywords', label: '关键词' },
  { key: 'excludeKeywords', label: '排除关键词' },
  { key: 'targetCities', label: '城市' },
  { key: 'experience', label: '经验' },
  { key: 'degree', label: '学历' },
  { key: 'industry', label: '行业' },
  { key: 'companyScale', label: '公司规模' },
  { key: 'financingStage', label: '融资阶段' },
  { key: 'workType', label: '工作类型' },
] as const

type CriteriaKey = (typeof CRITERIA_FIELDS)[number]['key']
type Criteria = Partial<Record<CriteriaKey, string[]>>

type PlatformId = 'BOSS' | 'LIEPIN' | 'JOB51' | 'ZHILIAN'
type FieldType = 'list' | 'scalar'

interface PlatformField {
  key: string
  label: string
  type: FieldType
}

interface PlatformDefinition {
  id: PlatformId
  label: string
  fields: PlatformField[]
}

const PLATFORM_IDS: PlatformId[] = ['BOSS', 'LIEPIN', 'JOB51', 'ZHILIAN']

const PLATFORMS: PlatformDefinition[] = [
  {
    id: 'BOSS',
    label: 'BOSS直聘',
    fields: [
      { key: 'keywords', label: '关键词', type: 'list' },
      { key: 'cityCode', label: '城市代码', type: 'list' },
      { key: 'salary', label: '薪资代码', type: 'list' },
      { key: 'experience', label: '经验代码', type: 'list' },
      { key: 'degree', label: '学历代码', type: 'list' },
      { key: 'industry', label: '行业代码', type: 'list' },
      { key: 'scale', label: '规模代码', type: 'list' },
      { key: 'stage', label: '融资代码', type: 'list' },
    ],
  },
  {
    id: 'LIEPIN',
    label: '猎聘',
    fields: [
      { key: 'keywords', label: '关键词', type: 'list' },
      { key: 'cityCode', label: '城市代码', type: 'scalar' },
      { key: 'salary', label: '薪资代码', type: 'scalar' },
    ],
  },
  {
    id: 'JOB51',
    label: '51job',
    fields: [
      { key: 'keywords', label: '关键词', type: 'list' },
      { key: 'jobArea', label: '城市代码', type: 'list' },
      { key: 'salary', label: '薪资代码', type: 'list' },
    ],
  },
  {
    id: 'ZHILIAN',
    label: '智联招聘',
    fields: [
      { key: 'keywords', label: '关键词', type: 'list' },
      { key: 'cityCode', label: '城市代码', type: 'scalar' },
      { key: 'salary', label: '薪资代码', type: 'scalar' },
    ],
  },
]

interface SearchTemplate {
  id: string
  name: string
  isDefault: boolean
  version: number
  criteria: Criteria
  sayHiDraft: string
  expectedSalary: string
  arrivalIntention: string
  linkedResumeAssetId: string
  personalIntro: string
  communicationStyle: string
  createdAt: string
  updatedAt: string
}

interface ProfileSummary {
  acceptedCities: string[]
  intentions: {
    workType: string
    expectedSalary: string
    arrivalIntention: string
  }
  approvedPdfAssetId: string
  approvedImageAssetId: string
  preferredResumeAssetKind: string
}

interface EffectiveField {
  value: unknown
  source: 'TEMPLATE' | 'PROFILE' | 'UNSET' | string
}

interface EffectiveView {
  templateId: string
  templateVersion: number
  profileVersion: string
  fields: Record<string, EffectiveField>
}

interface ResumeAsset {
  kind: 'PDF' | 'IMAGE'
  assetId?: string
  fileName?: string
  exists: boolean
  available?: boolean
}

interface PlatformOverride {
  platformId: PlatformId
  version: number
  fields: Record<string, string | string[]>
}

interface TemplateDraft {
  id: string | null
  name: string
  isDefault: boolean
  version: number
  criteria: Criteria
  sayHiDraft: string
  expectedSalary: string
  arrivalIntention: string
  linkedResumeAssetId: string
  personalIntro: string
  communicationStyle: string
}

type StatusMessage = { kind: 'ok' | 'error'; text: string }

const NO_TEMPLATE = '__new_template__'

function emptyDraft(): TemplateDraft {
  return {
    id: null,
    name: '',
    isDefault: false,
    version: 0,
    criteria: {},
    sayHiDraft: '',
    expectedSalary: '',
    arrivalIntention: '',
    linkedResumeAssetId: '',
    personalIntro: '',
    communicationStyle: '',
  }
}

function toDraft(template: SearchTemplate): TemplateDraft {
  return {
    id: template.id,
    name: template.name,
    isDefault: template.isDefault,
    version: template.version,
    criteria: { ...template.criteria },
    sayHiDraft: template.sayHiDraft,
    expectedSalary: template.expectedSalary ?? '',
    arrivalIntention: template.arrivalIntention ?? '',
    linkedResumeAssetId: template.linkedResumeAssetId ?? '',
    personalIntro: template.personalIntro ?? '',
    communicationStyle: template.communicationStyle ?? '',
  }
}

function parseList(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function ArrayInput({
  label,
  value,
  onChange,
}: {
  label: string
  value: string[]
  onChange: (values: string[]) => void
}) {
  const [text, setText] = useState(value.join(', '))
  return (
    <div>
      <Label>{label}</Label>
      <Input
        className="mt-1"
        value={text}
        onChange={(event) => {
          setText(event.target.value)
          onChange(parseList(event.target.value))
        }}
      />
    </div>
  )
}

function normalizeCriteria(value: unknown): Criteria {
  const criteria: Criteria = {}
  if (!value || typeof value !== 'object') return criteria
  const raw = value as Record<string, unknown>
  for (const field of CRITERIA_FIELDS) {
    const fieldValue = raw[field.key]
    if (Array.isArray(fieldValue)) {
      criteria[field.key] = fieldValue.filter((item): item is string => typeof item === 'string')
    }
  }
  return criteria
}

function normalizeTemplate(value: unknown): SearchTemplate {
  const raw = (value && typeof value === 'object' ? value : {}) as Partial<SearchTemplate>
  return {
    id: typeof raw.id === 'string' ? raw.id : '',
    name: typeof raw.name === 'string' ? raw.name : '',
    isDefault: Boolean(raw.isDefault),
    version: typeof raw.version === 'number' ? raw.version : 0,
    criteria: normalizeCriteria(raw.criteria),
    sayHiDraft: typeof raw.sayHiDraft === 'string' ? raw.sayHiDraft : '',
    expectedSalary: typeof raw.expectedSalary === 'string' ? raw.expectedSalary : '',
    arrivalIntention: typeof raw.arrivalIntention === 'string' ? raw.arrivalIntention : '',
    linkedResumeAssetId: typeof raw.linkedResumeAssetId === 'string' ? raw.linkedResumeAssetId : '',
    personalIntro: typeof raw.personalIntro === 'string' ? raw.personalIntro : '',
    communicationStyle: typeof raw.communicationStyle === 'string' ? raw.communicationStyle : '',
    createdAt: typeof raw.createdAt === 'string' ? raw.createdAt : '',
    updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : '',
  }
}

function normalizeProfileSummary(value: unknown): ProfileSummary {
  const raw = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const intentions = (raw.intentions && typeof raw.intentions === 'object' ? raw.intentions : {}) as Record<string, unknown>
  return {
    acceptedCities: Array.isArray(raw.acceptedCities)
      ? raw.acceptedCities.filter((item): item is string => typeof item === 'string')
      : [],
    intentions: {
      workType: typeof intentions.workType === 'string' ? intentions.workType : '',
      expectedSalary: typeof intentions.expectedSalary === 'string' ? intentions.expectedSalary : '',
      arrivalIntention: typeof intentions.arrivalIntention === 'string' ? intentions.arrivalIntention : '',
    },
    approvedPdfAssetId: typeof raw.approvedPdfAssetId === 'string' ? raw.approvedPdfAssetId : '',
    approvedImageAssetId: typeof raw.approvedImageAssetId === 'string' ? raw.approvedImageAssetId : '',
    preferredResumeAssetKind: typeof raw.preferredResumeAssetKind === 'string' ? raw.preferredResumeAssetKind : '',
  }
}

function normalizeAssets(value: unknown): ResumeAsset[] {
  if (!Array.isArray(value)) return []
  return value.map((item) => {
    const raw = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
    return {
      kind: raw.kind === 'IMAGE' ? 'IMAGE' as const : 'PDF' as const,
      assetId: typeof raw.assetId === 'string' ? raw.assetId : undefined,
      fileName: typeof raw.fileName === 'string' ? raw.fileName : undefined,
      exists: Boolean(raw.exists),
      available: Boolean(raw.available),
    }
  })
}

function normalizeOverrideFields(value: unknown): Record<string, string | string[]> {
  const fields: Record<string, string | string[]> = {}
  if (!value || typeof value !== 'object') return fields
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (typeof raw === 'string') {
      fields[key] = raw
    } else if (Array.isArray(raw)) {
      fields[key] = raw.filter((item): item is string => typeof item === 'string')
    }
  }
  return fields
}

function isPlatformId(value: unknown): value is PlatformId {
  return typeof value === 'string' && (PLATFORM_IDS as string[]).includes(value)
}

function cleanCriteria(criteria: Criteria): Criteria {
  const cleaned: Criteria = {}
  for (const field of CRITERIA_FIELDS) {
    const value = criteria[field.key]
    if (value !== undefined) {
      const values = value.map((item) => item.trim()).filter(Boolean)
      if (field.key === 'workType' && values.length === 0) continue
      cleaned[field.key] = values
    }
  }
  return cleaned
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function formatApiError(status: number, body: unknown): string {
  const error = (body as { error?: { code?: string; field?: string; message?: string } } | null)?.error
  if (error?.code) {
    const field = error.field ? ` · ${error.field}` : ''
    const message = error.message ? `：${error.message}` : ''
    return `${status} ${error.code}${field}${message}`
  }
  const message = (body as { message?: string } | null)?.message
  return message ? `${message} (${status})` : `请求失败 (${status})`
}

export default function SearchTemplatePage() {
  const [templates, setTemplates] = useState<SearchTemplate[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [draft, setDraft] = useState<TemplateDraft | null>(null)
  const [overrides, setOverrides] = useState<Partial<Record<PlatformId, PlatformOverride>>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [savingPlatform, setSavingPlatform] = useState<PlatformId | null>(null)
  const [showOverrides, setShowOverrides] = useState(false)
  const [formNonce, setFormNonce] = useState(0)
  const [overridesNonce, setOverridesNonce] = useState(0)
  const [message, setMessage] = useState<StatusMessage | null>(null)
  const [profile, setProfile] = useState<ProfileSummary | null>(null)
  const [effective, setEffective] = useState<EffectiveView | null>(null)
  const [assets, setAssets] = useState<ResumeAsset[]>([])
  const activeTemplateRef = useRef<string | null>(null)

  const loadTemplates = useCallback(async (preferredId?: string | null): Promise<boolean> => {
    setLoading(true)
    try {
      const response = await fetch(apiUrl('/api/search-templates'))
      const data = await readJson(response)
      if (!response.ok) {
        setMessage({ kind: 'error', text: formatApiError(response.status, data) })
        return false
      }
      if (!Array.isArray(data)) {
        setMessage({ kind: 'error', text: '模板接口返回格式错误' })
        return false
      }
      const list = data.map(normalizeTemplate).filter((item) => Boolean(item.id))
      setTemplates(list)
      const nextId = preferredId && list.some((item) => item.id === preferredId) ? preferredId : list[0]?.id ?? null
      activeTemplateRef.current = nextId
      setActiveId(nextId)
      const nextTemplate = list.find((item) => item.id === nextId)
      setDraft(nextTemplate ? toDraft(nextTemplate) : null)
      setFormNonce((value) => value + 1)
      return true
    } catch {
      setMessage({ kind: 'error', text: '无法连接后端，模板未加载' })
      return false
    } finally {
      setLoading(false)
    }
  }, [])

  const loadProfileAndAssets = useCallback(async () => {
    try {
      const [profileResponse, assetResponse] = await Promise.all([
        fetch(apiUrl('/api/boss/chat/profile')),
        fetch(apiUrl('/api/boss/config/resume-assets')),
      ])
      if (profileResponse.ok) setProfile(normalizeProfileSummary(await readJson(profileResponse)))
      if (assetResponse.ok) setAssets(normalizeAssets(await readJson(assetResponse)))
    } catch {
      // Inheritance hints are optional; template editing must remain usable.
    }
  }, [])

  const loadEffective = useCallback(async (templateId: string) => {
    try {
      const response = await fetch(apiUrl(`/api/search-templates/${templateId}/effective`))
      const data = await readJson(response)
      if (activeTemplateRef.current !== templateId) return
      setEffective(response.ok && data && typeof data === 'object' ? (data as EffectiveView) : null)
    } catch {
      if (activeTemplateRef.current === templateId) setEffective(null)
    }
  }, [])

  const loadOverrides = useCallback(async (templateId: string) => {
    try {
      const response = await fetch(apiUrl(`/api/search-templates/${templateId}/platform-overrides`))
      const data = await readJson(response)
      if (activeTemplateRef.current !== templateId) return
      if (!response.ok) {
        setOverrides({})
        setOverridesNonce((value) => value + 1)
        setMessage({ kind: 'error', text: formatApiError(response.status, data) })
        return
      }
      if (!Array.isArray(data)) {
        setOverrides({})
        setOverridesNonce((value) => value + 1)
        setMessage({ kind: 'error', text: '平台覆盖接口返回格式错误' })
        return
      }
      const next: Partial<Record<PlatformId, PlatformOverride>> = {}
      for (const item of data as unknown[]) {
        if (!item || typeof item !== 'object') continue
        const raw = item as { platformId?: unknown; version?: unknown; fields?: unknown }
        if (!isPlatformId(raw.platformId)) continue
        next[raw.platformId] = {
          platformId: raw.platformId,
          version: typeof raw.version === 'number' ? raw.version : 0,
          fields: normalizeOverrideFields(raw.fields),
        }
      }
      if (activeTemplateRef.current !== templateId) return
      setOverrides(next)
      setOverridesNonce((value) => value + 1)
    } catch {
      if (activeTemplateRef.current !== templateId) return
      setOverrides({})
      setOverridesNonce((value) => value + 1)
      setMessage({ kind: 'error', text: '平台覆盖加载失败' })
    }
  }, [])

  useEffect(() => {
    void loadTemplates()
    void loadProfileAndAssets()
  }, [loadTemplates, loadProfileAndAssets])

  useEffect(() => {
    activeTemplateRef.current = activeId
    setOverrides({})
    setOverridesNonce((value) => value + 1)
    if (activeId && activeId !== NO_TEMPLATE) {
      void loadOverrides(activeId)
      void loadEffective(activeId)
    } else {
      setEffective(null)
    }
  }, [activeId, loadOverrides, loadEffective])

  const selectTemplate = (template: SearchTemplate) => {
    activeTemplateRef.current = template.id
    setActiveId(template.id)
    setDraft(toDraft(template))
    setOverrides({})
    setOverridesNonce((value) => value + 1)
    setFormNonce((value) => value + 1)
    setMessage(null)
  }

  const startCreate = () => {
    activeTemplateRef.current = NO_TEMPLATE
    setActiveId(NO_TEMPLATE)
    setDraft(emptyDraft())
    setOverrides({})
    setOverridesNonce((value) => value + 1)
    setFormNonce((value) => value + 1)
    setMessage(null)
  }

  const updateDraft = (patch: Partial<TemplateDraft>) => {
    setDraft((prev) => (prev ? { ...prev, ...patch } : prev))
  }

  const updateCriterion = (key: CriteriaKey, value: string[]) => {
    setDraft((prev) => (prev ? { ...prev, criteria: { ...prev.criteria, [key]: value } } : prev))
  }

  const removeCriterion = (key: CriteriaKey) => {
    setDraft((prev) => {
      if (!prev) return prev
      const criteria = { ...prev.criteria }
      delete criteria[key]
      return { ...prev, criteria }
    })
    setFormNonce((value) => value + 1)
  }

  const sourceFor = (key: string) => effective?.fields?.[key]?.source ?? ''
  const valueFor = (key: string) => effective?.fields?.[key]?.value
  const inheritedText = (key: string) => {
    const value = valueFor(key)
    return value === null || value === undefined ? '' : String(value)
  }

  const saveTemplate = async () => {
    if (!draft) return
    const name = draft.name.trim()
    if (!name) {
      setMessage({ kind: 'error', text: '模板名称不能为空' })
      return
    }

    const payload: Record<string, unknown> = {
      name,
      isDefault: draft.isDefault,
      criteria: cleanCriteria(draft.criteria),
      sayHiDraft: draft.sayHiDraft,
      expectedSalary: draft.expectedSalary.trim() || null,
      arrivalIntention: draft.arrivalIntention.trim() || null,
      linkedResumeAssetId: draft.linkedResumeAssetId.trim() || null,
      personalIntro: draft.personalIntro.trim() || null,
      communicationStyle: draft.communicationStyle.trim() || null,
    }
    if (draft.id) payload.version = draft.version

    setSaving(true)
    setMessage(null)
    try {
      const response = await fetch(
        draft.id ? apiUrl(`/api/search-templates/${draft.id}`) : apiUrl('/api/search-templates'),
        {
          method: draft.id ? 'PUT' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        }
      )
      const data = await readJson(response)
      if (!response.ok) {
        if (response.status === 409 && draft.id) {
          await loadTemplates(draft.id)
          setMessage({ kind: 'error', text: '409 VERSION_CONFLICT：模板已更新，已重新加载' })
          return
        }
        if (response.status === 404 && draft.id) await loadTemplates()
        setMessage({ kind: 'error', text: formatApiError(response.status, data) })
        return
      }
      const saved = normalizeTemplate(data)
      const refreshed = await loadTemplates(saved.id)
      if (refreshed) void loadEffective(saved.id)
      setMessage({
        kind: refreshed ? 'ok' : 'error',
        text: refreshed ? '已保存' : '已保存，但列表刷新失败',
      })
    } catch {
      setMessage({ kind: 'error', text: '保存失败' })
    } finally {
      setSaving(false)
    }
  }

  const deleteTemplate = async () => {
    if (!draft?.id) return
    if (!confirm(`删除模板“${draft.name || '未命名'}”？`)) return
    setSaving(true)
    setMessage(null)
    try {
      const response = await fetch(apiUrl(`/api/search-templates/${draft.id}`), { method: 'DELETE' })
      if (response.status === 204) {
        const refreshed = await loadTemplates()
        setMessage({ kind: refreshed ? 'ok' : 'error', text: refreshed ? '已删除' : '已删除，但列表刷新失败' })
        return
      }
      const data = await readJson(response)
      if (response.status === 404) await loadTemplates()
      setMessage({ kind: 'error', text: formatApiError(response.status, data) })
    } catch {
      setMessage({ kind: 'error', text: '删除失败' })
    } finally {
      setSaving(false)
    }
  }

  const setOverrideValue = (platformId: PlatformId, key: string, value: string | string[]) => {
    setOverrides((prev) => {
      const current = prev[platformId] ?? { platformId, version: 0, fields: {} }
      return {
        ...prev,
        [platformId]: {
          ...current,
          fields: { ...current.fields, [key]: value },
        },
      }
    })
  }

  const buildOverrideFields = (platformId: PlatformId): Record<string, string | string[]> => {
    const platform = PLATFORMS.find((item) => item.id === platformId)
    const current = overrides[platformId]?.fields ?? {}
    const fields: Record<string, string | string[]> = {}
    if (!platform) return fields
    for (const field of platform.fields) {
      const value = current[field.key]
      if (value === undefined) continue
      if (field.type === 'list') {
        const list = Array.isArray(value) ? value : [value]
        fields[field.key] = list.map((item) => item.trim()).filter(Boolean)
      } else {
        fields[field.key] = (typeof value === 'string' ? value : Array.isArray(value) ? value.join(',') : '').trim()
      }
    }
    return fields
  }

  const patchOverride = async (
    platformId: PlatformId,
    fields: Record<string, string | string[] | null>,
    successText: string
  ) => {
    if (!activeId || activeId === NO_TEMPLATE) return
    const templateId = activeId
    setSavingPlatform(platformId)
    setMessage(null)
    try {
      const response = await fetch(
        apiUrl(`/api/search-templates/${templateId}/platform-overrides/${platformId}`),
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ version: overrides[platformId]?.version ?? 0, fields }),
        }
      )
      const data = await readJson(response)
      if (activeTemplateRef.current !== templateId) return
      if (!response.ok) {
        if (response.status === 409) {
          await loadOverrides(templateId)
          setMessage({ kind: 'error', text: '409 VERSION_CONFLICT：平台覆盖已更新，已重新加载' })
          return
        }
        setMessage({ kind: 'error', text: formatApiError(response.status, data) })
        return
      }
      const saved = (data && typeof data === 'object' ? data : {}) as {
        platformId?: unknown
        version?: unknown
        fields?: unknown
      }
      if (!isPlatformId(saved.platformId) || saved.platformId !== platformId) {
        await loadOverrides(templateId)
        setMessage({ kind: 'error', text: '平台覆盖响应格式错误' })
        return
      }
      setOverrides((prev) => ({
        ...prev,
        [platformId]: {
          platformId,
          version: typeof saved.version === 'number' ? saved.version : 0,
          fields: normalizeOverrideFields(saved.fields),
        },
      }))
      setOverridesNonce((value) => value + 1)
      setMessage({ kind: 'ok', text: successText })
    } catch {
      if (activeTemplateRef.current === templateId) {
        setMessage({ kind: 'error', text: '保存失败' })
      }
    } finally {
      setSavingPlatform(null)
    }
  }

  const saveOverride = (platformId: PlatformId) => {
    const fields = buildOverrideFields(platformId)
    if (Object.keys(fields).length === 0) {
      setMessage({ kind: 'error', text: '没有可保存的覆盖字段' })
      return
    }
    void patchOverride(platformId, fields, '已保存')
  }

  const restoreInheritance = (platformId: PlatformId) => {
    const platform = PLATFORMS.find((item) => item.id === platformId)
    if (!platform) return
    if (!confirm(`清除${platform.label}的全部覆盖并恢复继承？`)) return
    const fields: Record<string, string | string[] | null> = {}
    for (const field of platform.fields) fields[field.key] = null
    void patchOverride(platformId, fields, '已恢复继承')
  }

  return (
    <div className="space-y-6 max-w-6xl mx-auto pb-12">
      <PageHeader
        icon={<BiBookmark className="text-2xl text-primary" />}
        title="求职模板"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={startCreate}>
              <BiPlus />
              <span className="ml-1">新建</span>
            </Button>
            <Button size="sm" onClick={saveTemplate} disabled={!draft || saving}>
              <BiSave />
              <span className="ml-1">{saving ? '保存中...' : '保存'}</span>
            </Button>
          </div>
        }
      />

      {loading && <p className="text-sm text-muted-foreground">加载中...</p>}

      {message && (
        <div
          role="alert"
          className={`flex items-center justify-between gap-3 rounded-lg border p-3 text-sm ${
            message.kind === 'error'
              ? 'border-destructive/30 bg-destructive/10 text-destructive'
              : 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
          }`}
        >
          <span>{message.text}</span>
          <button
            type="button"
            onClick={() => setMessage(null)}
            className="shrink-0 text-current opacity-70 hover:opacity-100"
            aria-label="关闭提示"
          >
            <BiX />
          </button>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-muted/40 p-2">
        {templates.map((template) => {
          const active = template.id === activeId
          return (
            <button
              key={template.id}
              type="button"
              onClick={() => selectTemplate(template)}
              className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-xs font-medium transition-colors ${
                active
                  ? 'border-border bg-background font-semibold text-foreground shadow-sm'
                  : 'border-transparent text-muted-foreground hover:bg-muted/60 hover:text-foreground'
              }`}
            >
              <span>{template.name || '未命名'}</span>
              {template.isDefault && (
                <span className="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-bold text-primary">默认</span>
              )}
            </button>
          )
        })}
        {activeId === NO_TEMPLATE && (
          <span className="rounded-lg border border-border bg-background px-3 py-2 text-xs font-semibold text-foreground">
            新模板
          </span>
        )}
        {templates.length === 0 && activeId !== NO_TEMPLATE && (
          <span className="px-2 text-xs text-muted-foreground">暂无模板</span>
        )}
        {draft?.id && (
          <Button
            variant="ghost"
            size="sm"
            onClick={deleteTemplate}
            disabled={saving}
            className="ml-auto text-destructive hover:bg-destructive/10"
          >
            <BiTrash />
            <span className="ml-1">删除</span>
          </Button>
        )}
      </div>

      {draft ? (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">模板</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 items-end gap-4 md:grid-cols-2">
                <div>
                  <Label htmlFor="templateName">名称</Label>
                  <Input
                    id="templateName"
                    className="mt-1"
                    value={draft.name}
                    onChange={(event) => updateDraft({ name: event.target.value })}
                  />
                </div>
                <label className="flex items-center gap-2 pb-2 text-sm text-foreground">
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-border"
                    checked={draft.isDefault}
                    onChange={(event) => updateDraft({ isDefault: event.target.checked })}
                  />
                  设为默认
                </label>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">个人与内容</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <div className="flex items-center justify-between gap-2">
                    <Label>个人期望薪资</Label>
                    {draft.expectedSalary && (
                      <button type="button" onClick={() => updateDraft({ expectedSalary: '' })} className="text-xs text-muted-foreground hover:text-foreground">
                        继承
                      </button>
                    )}
                  </div>
                  <Input
                    className="mt-1"
                    value={draft.expectedSalary}
                    onChange={(event) => updateDraft({ expectedSalary: event.target.value })}
                    placeholder={sourceFor('expectedSalary') === 'PROFILE' ? `继承：${inheritedText('expectedSalary')}` : ''}
                  />
                </div>
                <div>
                  <div className="flex items-center justify-between gap-2">
                    <Label>到岗意向</Label>
                    {draft.arrivalIntention && (
                      <button type="button" onClick={() => updateDraft({ arrivalIntention: '' })} className="text-xs text-muted-foreground hover:text-foreground">
                        继承
                      </button>
                    )}
                  </div>
                  <Input
                    className="mt-1"
                    value={draft.arrivalIntention}
                    onChange={(event) => updateDraft({ arrivalIntention: event.target.value })}
                    placeholder={sourceFor('arrivalIntention') === 'PROFILE' ? `继承：${inheritedText('arrivalIntention')}` : ''}
                  />
                </div>
                <div>
                  <div className="flex items-center justify-between gap-2">
                    <Label>关联简历</Label>
                    {draft.linkedResumeAssetId && (
                      <button type="button" onClick={() => updateDraft({ linkedResumeAssetId: '' })} className="text-xs text-muted-foreground hover:text-foreground">
                        继承
                      </button>
                    )}
                  </div>
                  <Select value={draft.linkedResumeAssetId} onChange={(event) => updateDraft({ linkedResumeAssetId: event.target.value })}>
                    <option value="">继承个人资料{profile?.preferredResumeAssetKind ? `（${profile.preferredResumeAssetKind}）` : ''}</option>
                    {assets.filter((asset) => asset.exists && asset.assetId).map((asset) => (
                      <option key={`${asset.kind}-${asset.assetId}`} value={asset.assetId}>
                        {asset.kind === 'PDF' ? 'PDF' : '图片'} · {asset.fileName || asset.assetId}
                      </option>
                    ))}
                    {draft.linkedResumeAssetId && !assets.some((asset) => asset.assetId === draft.linkedResumeAssetId) && (
                      <option value={draft.linkedResumeAssetId}>当前关联（不可用）</option>
                    )}
                  </Select>
                </div>
                <div>
                  <Label>沟通风格</Label>
                  <Input
                    className="mt-1"
                    value={draft.communicationStyle}
                    onChange={(event) => updateDraft({ communicationStyle: event.target.value })}
                    placeholder={sourceFor('communicationStyle') === 'PROFILE' ? '继承个人资料' : ''}
                  />
                </div>
              </div>
              <div>
                <Label>个人介绍</Label>
                <Textarea
                  className="mt-1 text-sm"
                  rows={4}
                  value={draft.personalIntro}
                  onChange={(event) => updateDraft({ personalIntro: event.target.value })}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">筛选条件</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-xs text-muted-foreground">多个值用逗号分隔</p>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {CRITERIA_FIELDS.map((field) => (
                  <div key={`${formNonce}-${draft.id ?? 'new'}-${field.key}`}>
                    <ArrayInput
                      label={field.label}
                      value={draft.criteria[field.key] ?? []}
                      onChange={(values) => updateCriterion(field.key, values)}
                    />
                    {field.key === 'workType' && (
                      <button
                        type="button"
                        onClick={() => removeCriterion('workType')}
                        className="mt-1 text-xs text-muted-foreground hover:text-foreground"
                      >
                        继承个人资料{profile?.intentions.workType ? `：${profile.intentions.workType}` : ''}
                      </button>
                    )}
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">打招呼语</CardTitle>
            </CardHeader>
            <CardContent>
              <Textarea
                rows={4}
                className="text-sm"
                value={draft.sayHiDraft}
                onChange={(event) => updateDraft({ sayHiDraft: event.target.value })}
              />
            </CardContent>
          </Card>

          {draft.id && (
            <Card>
              <CardHeader className="cursor-pointer" onClick={() => setShowOverrides((prev) => !prev)}>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-lg">平台覆盖</CardTitle>
                  <Button variant="ghost" size="sm" type="button">
                    {showOverrides ? '收起' : '展开'}
                  </Button>
                </div>
              </CardHeader>
              {showOverrides && (
                <CardContent className="space-y-4 pt-0">
                  {PLATFORMS.map((platform) => {
                    const override = overrides[platform.id]
                    return (
                      <div key={platform.id} className="space-y-3 rounded-lg border border-border p-4">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <h3 className="text-sm font-semibold text-foreground">{platform.label}</h3>
                          <div className="flex items-center gap-2">
                            {override && (override.version > 0 || Object.keys(override.fields).length > 0) && (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => restoreInheritance(platform.id)}
                                disabled={savingPlatform !== null}
                              >
                                恢复继承
                              </Button>
                            )}
                            <Button
                              size="sm"
                              onClick={() => saveOverride(platform.id)}
                              disabled={savingPlatform !== null}
                            >
                              {savingPlatform === platform.id ? '保存中...' : '保存覆盖'}
                            </Button>
                          </div>
                        </div>
                        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-4">
                          {platform.fields.map((field) => {
                            const rawValue = override?.fields[field.key]
                            if (field.type === 'list') {
                              const values = Array.isArray(rawValue)
                                ? rawValue.filter((item): item is string => typeof item === 'string')
                                : typeof rawValue === 'string'
                                  ? [rawValue]
                                  : []
                              return (
                                <ArrayInput
                                  key={`${formNonce}-${overridesNonce}-${draft.id ?? 'new'}-${platform.id}-${field.key}`}
                                  label={field.label}
                                  value={values}
                                  onChange={(next) => setOverrideValue(platform.id, field.key, next)}
                                />
                              )
                            }
                            return (
                              <div
                                key={`${formNonce}-${overridesNonce}-${draft.id ?? 'new'}-${platform.id}-${field.key}`}
                              >
                                <Label>{field.label}</Label>
                                <Input
                                  className="mt-1"
                                  value={typeof rawValue === 'string' ? rawValue : ''}
                                  onChange={(event) =>
                                    setOverrideValue(platform.id, field.key, event.target.value)
                                  }
                                />
                              </div>
                            )
                          })}
                        </div>
                      </div>
                    )
                  })}
                </CardContent>
              )}
            </Card>
          )}
        </div>
      ) : (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">暂无模板</CardContent>
        </Card>
      )}
    </div>
  )
}
