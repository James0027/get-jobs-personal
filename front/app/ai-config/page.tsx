'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { BiBrain, BiCheckCircle, BiSave, BiSend } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import PageHeader from '@/app/components/PageHeader'
import AiConnectionSummary from '@/app/components/AiConnectionSummary'
import { apiUrl } from '@/lib/api'

type Status = { kind: 'ok' | 'error'; text: string }

interface Draft {
  skillsIntro: string
  personalIntro: string
  sayHiDraft: string
  style: string
  length: string
  purpose: string
  advancedPrompt: string
  customStyle: string
  updatedAt: string
  simulated: boolean
  verified: boolean
  connectionMessage: string
}

interface Generated {
  skillsIntro: string
  personalIntro: string
  sayHiDraft: string
  simulated: boolean
  verified: boolean
  connectionMessage: string
  missingFacts: string[]
  parseWarning?: string
}

interface ProfileSummary {
  version: string
  acceptedCities: string[]
  facts: { id: string; text: string }[]
  intentions: {
    workType: string
    expectedSalary: string
    arrivalIntention: string
    availableFrom: string
    weeklyAvailabilityDays: number | null
  }
  resume: {
    work: unknown[]
    project: unknown[]
    education: unknown[]
    skills: unknown[]
  }
}

interface TemplateSummary {
  id: string
  name: string
  isDefault: boolean
  version: number
  criteria: Record<string, string[]>
}

const STYLE_OPTIONS = [
  { id: 'professional', label: '专业正式' },
  { id: 'sincere', label: '自然真诚' },
  { id: 'concise', label: '简洁直接' },
  { id: 'friendly', label: '轻松亲和' },
  { id: 'playful', label: '活泼俏皮' },
  { id: 'custom', label: '自定义' },
]

const LENGTH_OPTIONS = [
  { id: 'short', label: '短' },
  { id: 'medium', label: '中' },
  { id: 'long', label: '长' },
]

const PURPOSE_OPTIONS = [
  { id: 'job_search', label: '求职打招呼' },
  { id: 'personal_intro', label: '个人介绍' },
  { id: 'skills_intro', label: '技能介绍' },
]

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function errorText(status: number, body: unknown): string {
  const error = (body as { error?: { code?: string; field?: string; message?: string } } | null)?.error
  if (error?.code) {
    return `${status} ${error.code}${error.field ? ` · ${error.field}` : ''}${error.message ? `：${error.message}` : ''}`
  }
  const message = (body as { message?: string } | null)?.message
  return message ? `${message} (${status})` : `请求失败 (${status})`
}

function arrayOrEmpty<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : []
}

function normalizeProfile(value: unknown): ProfileSummary {
  const raw = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const resume = (raw.resume && typeof raw.resume === 'object' ? raw.resume : {}) as Record<string, unknown>
  const intentions = (raw.intentions && typeof raw.intentions === 'object' ? raw.intentions : {}) as Record<string, unknown>
  return {
    version: typeof raw.version === 'string' ? raw.version : '',
    acceptedCities: arrayOrEmpty<string>(raw.acceptedCities),
    facts: arrayOrEmpty<{ id: string; text: string }>(raw.facts),
    intentions: {
      workType: typeof intentions.workType === 'string' ? intentions.workType : '',
      expectedSalary: typeof intentions.expectedSalary === 'string' ? intentions.expectedSalary : '',
      arrivalIntention: typeof intentions.arrivalIntention === 'string' ? intentions.arrivalIntention : '',
      availableFrom: typeof intentions.availableFrom === 'string' ? intentions.availableFrom : '',
      weeklyAvailabilityDays:
        typeof intentions.weeklyAvailabilityDays === 'number' ? intentions.weeklyAvailabilityDays : null,
    },
    resume: {
      work: arrayOrEmpty<unknown>(resume.work),
      project: arrayOrEmpty<unknown>(resume.project),
      education: arrayOrEmpty<unknown>(resume.education),
      skills: arrayOrEmpty<unknown>(resume.skills),
    },
  }
}

function normalizeTemplates(value: unknown): TemplateSummary[] {
  if (!Array.isArray(value)) return []
  return value
    .map((item) => {
      const raw = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
      const criteria = (raw.criteria && typeof raw.criteria === 'object' ? raw.criteria : {}) as Record<string, unknown>
      const normalized: Record<string, string[]> = {}
      for (const [key, entry] of Object.entries(criteria)) {
        if (Array.isArray(entry)) normalized[key] = entry.filter((v): v is string => typeof v === 'string')
      }
      return {
        id: typeof raw.id === 'string' ? raw.id : '',
        name: typeof raw.name === 'string' ? raw.name : '',
        isDefault: Boolean(raw.isDefault),
        version: typeof raw.version === 'number' ? raw.version : 0,
        criteria: normalized,
      }
    })
    .filter((item) => item.id)
}

function draftFrom(value: unknown): Draft {
  const raw = (value && typeof value === 'object' ? value : {}) as Partial<Draft>
  return {
    skillsIntro: typeof raw.skillsIntro === 'string' ? raw.skillsIntro : '',
    personalIntro: typeof raw.personalIntro === 'string' ? raw.personalIntro : '',
    sayHiDraft: typeof raw.sayHiDraft === 'string' ? raw.sayHiDraft : '',
    style: typeof raw.style === 'string' ? raw.style : 'professional',
    length: typeof raw.length === 'string' ? raw.length : 'medium',
    purpose: typeof raw.purpose === 'string' ? raw.purpose : 'job_search',
    advancedPrompt: typeof raw.advancedPrompt === 'string' ? raw.advancedPrompt : '',
    customStyle: typeof raw.customStyle === 'string' ? raw.customStyle : '',
    updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : '',
    simulated: Boolean(raw.simulated),
    verified: Boolean(raw.verified),
    connectionMessage: typeof raw.connectionMessage === 'string' ? raw.connectionMessage : '',
  }
}

export default function AiConfigPage() {
  const [profile, setProfile] = useState<ProfileSummary | null>(null)
  const [templates, setTemplates] = useState<TemplateSummary[]>([])
  const [selectedTemplate, setSelectedTemplate] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [generated, setGenerated] = useState<Generated | null>(null)
  const [generating, setGenerating] = useState(false)
  const [savingDraft, setSavingDraft] = useState(false)
  const [contentMessage, setContentMessage] = useState<Status | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [draftRes, profileRes, templateRes] = await Promise.all([
          fetch(apiUrl('/api/ai/content')),
          fetch(apiUrl('/api/boss/chat/profile')),
          fetch(apiUrl('/api/search-templates')),
        ])
        const draftData = await readJson(draftRes)
        const profileData = await readJson(profileRes)
        const templateData = await readJson(templateRes)
        if (cancelled) return
        setDraft(draftFrom(draftData))
        setProfile(normalizeProfile(profileData))
        const loadedTemplates = normalizeTemplates(templateData)
        setTemplates(loadedTemplates)
        setSelectedTemplate(loadedTemplates.find((template) => template.isDefault)?.id ?? loadedTemplates[0]?.id ?? '')
      } catch {
        if (!cancelled) setContentMessage({ kind: 'error', text: '无法连接后端，内容配置未加载' })
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [])

  const missingFacts = useMemo(() => {
    if (!profile) return []
    const missing: string[] = []
    const resumeEmpty =
      profile.resume.work.length === 0 &&
      profile.resume.project.length === 0 &&
      profile.resume.education.length === 0 &&
      profile.resume.skills.length === 0
    if (profile.facts.length === 0 && resumeEmpty) missing.push('个人事实或结构化经历')
    if (!profile.intentions.workType && !profile.intentions.expectedSalary && !profile.intentions.arrivalIntention) {
      missing.push('求职意向（工作类型/期望薪资/到岗意向）')
    }
    return missing
  }, [profile])

  const updateDraft = (patch: Partial<Draft>) => {
    setDraft((prev) => prev ? { ...prev, ...patch } : prev)
  }

  const generate = async () => {
    if (!draft) return
    setGenerating(true)
    setContentMessage(null)
    try {
      const response = await fetch(apiUrl('/api/ai/content/generate'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          templateId: selectedTemplate,
          style: draft.style,
          length: draft.length,
          purpose: draft.purpose,
          customStyle: draft.style === 'custom' ? draft.customStyle : '',
          advancedPrompt: draft.advancedPrompt,
        }),
      })
      const data = await readJson(response)
      if (!response.ok) throw new Error(errorText(response.status, data))
      setGenerated(data as Generated)
      setContentMessage({
        kind: 'ok',
        text: (data as Generated).simulated ? '已生成模拟草稿，未调用真实模型' : '已生成预览，确认后应用到草稿',
      })
    } catch (error) {
      setContentMessage({ kind: 'error', text: error instanceof Error ? error.message : '生成失败' })
    } finally {
      setGenerating(false)
    }
  }

  const applyGenerated = (field?: 'skills' | 'intro' | 'sayHi') => {
    if (!generated || !draft) return
    updateDraft({
      ...(field === undefined || field === 'skills' ? { skillsIntro: generated.skillsIntro } : {}),
      ...(field === undefined || field === 'intro' ? { personalIntro: generated.personalIntro } : {}),
      ...(field === undefined || field === 'sayHi' ? { sayHiDraft: generated.sayHiDraft } : {}),
    })
    setContentMessage({ kind: 'ok', text: '已应用到可编辑草稿，保存后持久化' })
  }

  const saveDraft = async () => {
    if (!draft) return
    setSavingDraft(true)
    setContentMessage(null)
    try {
      const response = await fetch(apiUrl('/api/ai/content'), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          skillsIntro: draft.skillsIntro,
          personalIntro: draft.personalIntro,
          sayHiDraft: draft.sayHiDraft,
          style: draft.style,
          length: draft.length,
          purpose: draft.purpose,
          advancedPrompt: draft.advancedPrompt,
          customStyle: draft.customStyle,
        }),
      })
      const data = await readJson(response)
      if (!response.ok) throw new Error(errorText(response.status, data))
      setDraft(draftFrom(data))
      setContentMessage({ kind: 'ok', text: '草稿已保存' })
    } catch (error) {
      setContentMessage({ kind: 'error', text: error instanceof Error ? error.message : '保存草稿失败' })
    } finally {
      setSavingDraft(false)
    }
  }

  return (
    <div className="space-y-6 max-w-6xl mx-auto pb-12">
      <PageHeader
        icon={<BiBrain className="text-xl" />}
        title="AI 配置"
        actions={
          <Button size="sm" onClick={saveDraft} disabled={!draft || savingDraft}>
            <BiSave />
            <span className="ml-1.5">{savingDraft ? '保存中...' : '保存草稿'}</span>
          </Button>
        }
      />

      <Tabs defaultValue="connection" className="w-full">
        <TabsList className="grid w-full grid-cols-2 md:w-auto">
          <TabsTrigger value="connection">连接</TabsTrigger>
          <TabsTrigger value="content">内容</TabsTrigger>
        </TabsList>

        <TabsContent value="connection" className="mt-6">
          <AiConnectionSummary />
        </TabsContent>

        <TabsContent value="content" className="mt-6 space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">资料与风格</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <Label>个人资料</Label>
                  <Select value="current" onChange={() => undefined} disabled>
                    <option value="current">当前个人资料{profile ? ` · 事实 ${profile.facts.length}` : ''}</option>
                  </Select>
                </div>
                <div>
                  <Label>求职模板</Label>
                  <Select value={selectedTemplate} onChange={(event) => setSelectedTemplate(event.target.value)}>
                    <option value="">不使用模板</option>
                    {templates.map((template) => (
                      <option key={template.id} value={template.id}>{template.name}{template.isDefault ? '（默认）' : ''}</option>
                    ))}
                  </Select>
                </div>
              </div>

              <div>
                <Label>风格</Label>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {STYLE_OPTIONS.map((option) => (
                    <button
                      key={option.id}
                      type="button"
                      onClick={() => updateDraft({ style: option.id })}
                      className={`rounded-md border px-2.5 py-1 text-xs ${draft?.style === option.id ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted'}`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
                {draft?.style === 'custom' && (
                  <Input
                    className="mt-2"
                    value={draft?.customStyle ?? ''}
                    onChange={(event) => updateDraft({ customStyle: event.target.value })}
                    placeholder="自定义风格描述"
                  />
                )}
              </div>

              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <Label>长度</Label>
                  <Select value={draft?.length ?? 'medium'} onChange={(event) => updateDraft({ length: event.target.value })}>
                    {LENGTH_OPTIONS.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
                  </Select>
                </div>
                <div>
                  <Label>用途</Label>
                  <Select value={draft?.purpose ?? 'job_search'} onChange={(event) => updateDraft({ purpose: event.target.value })}>
                    {PURPOSE_OPTIONS.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
                  </Select>
                </div>
              </div>

              {missingFacts.length > 0 && (
                <div className="flex flex-wrap items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-300">
                  <span>资料待补充：{missingFacts.join('、')}</span>
                  <Link href="/profile" className="underline underline-offset-2">去补充</Link>
                </div>
              )}

              <details className="rounded-lg border border-border p-3">
                <summary className="cursor-pointer text-xs text-muted-foreground">高级提示词</summary>
                <Textarea
                  className="mt-2 text-sm"
                  rows={4}
                  value={draft?.advancedPrompt ?? ''}
                  onChange={(event) => updateDraft({ advancedPrompt: event.target.value })}
                  placeholder="可选：补充生成要求，不需要填写占位符"
                />
              </details>

              <div className="flex flex-wrap gap-2">
                <Button size="sm" onClick={generate} disabled={generating || !draft}>
                  <BiSend />
                  <span className="ml-1">{generating ? '生成中...' : '生成预览'}</span>
                </Button>
                {generated?.simulated && <span className="rounded-full bg-amber-500/10 px-2.5 py-0.5 text-xs text-amber-600">模拟生成</span>}
                {generated && !generated.simulated && !generated.verified && <span className="rounded-full bg-amber-500/10 px-2.5 py-0.5 text-xs text-amber-600">连接未测试</span>}
              </div>

              {contentMessage && (
                <div className={`rounded-lg border p-3 text-sm ${contentMessage.kind === 'error' ? 'border-destructive/30 bg-destructive/10 text-destructive' : 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'}`}>
                  {contentMessage.text}
                </div>
              )}
            </CardContent>
          </Card>

          {generated && (
            <Card>
              <CardHeader>
                <CardTitle className="text-lg">生成预览</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <p className={`text-xs ${generated.simulated ? 'text-amber-600' : 'text-muted-foreground'}`}>
                  {generated.connectionMessage}
                </p>
                {generated.parseWarning && <p className="text-xs text-amber-600">{generated.parseWarning}</p>}
                {(['skillsIntro', 'personalIntro', 'sayHiDraft'] as const).map((field) => (
                  <div key={field}>
                    <div className="mb-1 flex items-center justify-between">
                      <Label>{field === 'skillsIntro' ? '技能介绍' : field === 'personalIntro' ? '个人介绍' : '招呼语'}</Label>
                      <Button size="sm" variant="outline" onClick={() => applyGenerated(field === 'skillsIntro' ? 'skills' : field === 'personalIntro' ? 'intro' : 'sayHi')}>
                        应用
                      </Button>
                    </div>
                    <Textarea readOnly rows={3} className="text-sm" value={generated[field]} />
                  </div>
                ))}
                <Button size="sm" variant="outline" onClick={() => applyGenerated()}>
                  <BiCheckCircle />
                  <span className="ml-1">应用全部到草稿</span>
                </Button>
              </CardContent>
            </Card>
          )}

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">可编辑草稿</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {([
                ['skillsIntro', '技能介绍'],
                ['personalIntro', '个人介绍'],
                ['sayHiDraft', '招呼语'],
              ] as const).map(([field, label]) => (
                <div key={field}>
                  <Label>{label}</Label>
                  <Textarea
                    className="mt-1 text-sm"
                    rows={field === 'sayHiDraft' ? 3 : 5}
                    value={draft?.[field] ?? ''}
                    onChange={(event) => updateDraft({ [field]: event.target.value })}
                  />
                </div>
              ))}
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-muted-foreground">
                  {draft?.simulated ? '模拟草稿' : draft?.verified ? '真实连接已测试' : '手动编辑不会发送给 HR'}
                </span>
                <Button size="sm" variant="outline" onClick={saveDraft} disabled={savingDraft}>
                  <BiSave />
                  <span className="ml-1">{savingDraft ? '保存中...' : '保存草稿'}</span>
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
