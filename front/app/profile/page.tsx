'use client'

import { useCallback, useEffect, useState } from 'react'
import Image from 'next/image'
import { BiPlus, BiSave, BiShow, BiTrash, BiUpload, BiUserCircle, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import PageHeader from '@/app/components/PageHeader'
import { apiUrl } from '@/lib/api'

interface FactItem {
  id: string
  text: string
}

interface WorkItem {
  id: string
  company: string
  title: string
  period: string
  summary: string
}

interface ProjectItem {
  id: string
  name: string
  role: string
  period: string
  description: string
}

interface EducationItem {
  id: string
  school: string
  degree: string
  major: string
  period: string
}

interface SkillItem {
  id: string
  name: string
  level: string
  description: string
}

interface FollowUpConfig {
  enabled: boolean
  cooldownHours: number
  maxPerConversation: number
}

interface ResumeState {
  work: WorkItem[]
  project: ProjectItem[]
  education: EducationItem[]
  skills: SkillItem[]
}

interface JobIntentions {
  workType: string
  expectedSalary: string
  arrivalIntention: string
  availableFrom: string
  weeklyAvailabilityDays: number | null
}

interface ProfileState {
  version: string
  graduationYear: number | null
  acceptedCities: string[]
  facts: FactItem[]
  approvedResumeAssetId: string
  autoResume: boolean
  autoLocation: boolean
  enabled: boolean
  resume: ResumeState
  approvedPdfAssetId: string
  approvedImageAssetId: string
  preferredResumeAssetKind: 'PDF' | 'IMAGE' | ''
  intentions: JobIntentions
  resumeText: string
  resumeTextHash: string
  approvedResumeTextHash: string
  derivedFactIds: string[]
  autoReply: boolean
  followUp: FollowUpConfig
}

interface ResumeAsset {
  exists: boolean
  assetId?: string
  kind: 'PDF' | 'IMAGE'
  available?: boolean
  fileName?: string
  contentType?: string
  size?: number
  updatedAt?: number
  previewUrl?: string
}

type StatusMessage = { kind: 'ok' | 'error'; text: string }

type ResumeTab = keyof ResumeState

const COMMON_CITIES = ['北京', '上海', '杭州', '深圳', '广州', '成都', '武汉', '南京', '远程']

function emptyProfile(): ProfileState {
  return {
    version: '0',
    graduationYear: null,
    acceptedCities: [],
    facts: [],
    approvedResumeAssetId: '',
    autoResume: false,
    autoLocation: false,
    enabled: false,
    resume: {
      work: [],
      project: [],
      education: [],
      skills: [],
    },
    approvedPdfAssetId: '',
    approvedImageAssetId: '',
    preferredResumeAssetKind: '',
    intentions: {
      workType: '',
      expectedSalary: '',
      arrivalIntention: '',
      availableFrom: '',
      weeklyAvailabilityDays: null,
    },
    resumeText: '',
    resumeTextHash: '',
    approvedResumeTextHash: '',
    derivedFactIds: [],
    autoReply: false,
    followUp: {
      enabled: false,
      cooldownHours: 168,
      maxPerConversation: 1,
    },
  }
}

function arrayOrEmpty<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : []
}

function normalizeProfile(value: unknown): ProfileState {
  const base = emptyProfile()
  if (!value || typeof value !== 'object') return base
  const raw = value as Partial<ProfileState>
  const resume = (raw.resume && typeof raw.resume === 'object' ? raw.resume : {}) as Partial<ResumeState>
  const followUp = (raw.followUp && typeof raw.followUp === 'object' ? raw.followUp : {}) as Partial<FollowUpConfig>
  const intentions = (raw.intentions && typeof raw.intentions === 'object' ? raw.intentions : {}) as Partial<JobIntentions>
  const preferredKind = raw.preferredResumeAssetKind === 'PDF' || raw.preferredResumeAssetKind === 'IMAGE'
    ? raw.preferredResumeAssetKind
    : ''
  return {
    ...base,
    version: typeof raw.version === 'string' ? raw.version : base.version,
    graduationYear: typeof raw.graduationYear === 'number' ? raw.graduationYear : null,
    acceptedCities: arrayOrEmpty<string>(raw.acceptedCities),
    facts: arrayOrEmpty<FactItem>(raw.facts),
    approvedResumeAssetId: typeof raw.approvedResumeAssetId === 'string' ? raw.approvedResumeAssetId : '',
    autoResume: Boolean(raw.autoResume),
    autoLocation: Boolean(raw.autoLocation),
    enabled: Boolean(raw.enabled),
    resume: {
      work: arrayOrEmpty<WorkItem>(resume.work),
      project: arrayOrEmpty<ProjectItem>(resume.project),
      education: arrayOrEmpty<EducationItem>(resume.education),
      skills: arrayOrEmpty<SkillItem>(resume.skills),
    },
    approvedPdfAssetId: typeof raw.approvedPdfAssetId === 'string' ? raw.approvedPdfAssetId : '',
    approvedImageAssetId: typeof raw.approvedImageAssetId === 'string' ? raw.approvedImageAssetId : '',
    preferredResumeAssetKind: preferredKind,
    intentions: {
      workType: typeof intentions.workType === 'string' ? intentions.workType : '',
      expectedSalary: typeof intentions.expectedSalary === 'string' ? intentions.expectedSalary : '',
      arrivalIntention: typeof intentions.arrivalIntention === 'string' ? intentions.arrivalIntention : '',
      availableFrom: typeof intentions.availableFrom === 'string' ? intentions.availableFrom : '',
      weeklyAvailabilityDays:
        typeof intentions.weeklyAvailabilityDays === 'number' ? intentions.weeklyAvailabilityDays : null,
    },
    resumeText: typeof raw.resumeText === 'string' ? raw.resumeText : '',
    resumeTextHash: typeof raw.resumeTextHash === 'string' ? raw.resumeTextHash : '',
    approvedResumeTextHash: typeof raw.approvedResumeTextHash === 'string' ? raw.approvedResumeTextHash : '',
    derivedFactIds: arrayOrEmpty<string>(raw.derivedFactIds),
    autoReply: Boolean(raw.autoReply),
    followUp: {
      enabled: Boolean(followUp.enabled),
      cooldownHours: typeof followUp.cooldownHours === 'number' ? followUp.cooldownHours : base.followUp.cooldownHours,
      maxPerConversation:
        typeof followUp.maxPerConversation === 'number'
          ? followUp.maxPerConversation
          : base.followUp.maxPerConversation,
    },
  }
}

function nextId(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function visibleFacts(profile: ProfileState): FactItem[] {
  return profile.facts.filter((fact) => !profile.derivedFactIds.includes(fact.id))
}

function validateProfile(profile: ProfileState): string | null {
  if (profile.graduationYear !== null && (profile.graduationYear < 2000 || profile.graduationYear > 2100)) {
    return '毕业年份需在 2000 到 2100 之间'
  }
  for (const item of profile.resume.work) {
    if (!item.company.trim() || !item.title.trim()) return '工作经历需填写公司和职位'
  }
  for (const item of profile.resume.project) {
    if (!item.name.trim()) return '项目经历需填写项目名称'
  }
  for (const item of profile.resume.education) {
    if (!item.school.trim()) return '教育经历需填写学校名称'
  }
  for (const item of profile.resume.skills) {
    if (!item.name.trim()) return '技能需填写名称'
  }
  for (const fact of visibleFacts(profile)) {
    if (!fact.text.trim() || fact.text.length > 2000) return '单条事实需为 1 到 2000 字符'
  }
  if (profile.resumeText.length > 20000) return '简历文本不能超过 20000 字符'
  if (profile.intentions.weeklyAvailabilityDays !== null
      && (profile.intentions.weeklyAvailabilityDays < 1 || profile.intentions.weeklyAvailabilityDays > 7)) {
    return '每周可实习天数需在 1 到 7 之间'
  }
  if (profile.preferredResumeAssetKind === 'PDF' && !profile.approvedPdfAssetId) return '请选择要使用的 PDF 简历'
  if (profile.preferredResumeAssetKind === 'IMAGE' && !profile.approvedImageAssetId) return '请选择要使用的图片简历'
  return null
}

export default function ProfilePage() {
  const [profile, setProfile] = useState<ProfileState>(emptyProfile)
  const [assets, setAssets] = useState<ResumeAsset[]>([
    { kind: 'PDF', exists: false },
    { kind: 'IMAGE', exists: false },
  ])
  const [previewAsset, setPreviewAsset] = useState<ResumeAsset | null>(null)
  const [uploadingKind, setUploadingKind] = useState<'PDF' | 'IMAGE' | null>(null)
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<StatusMessage | null>(null)
  const [cityInput, setCityInput] = useState('')
  const [newFactText, setNewFactText] = useState('')
  const [resumeTab, setResumeTab] = useState<ResumeTab>('work')

  const loadAssets = useCallback(async () => {
    try {
      const response = await fetch(apiUrl('/api/boss/config/resume-assets'))
      if (!response.ok) return
      const data: unknown = await response.json()
      if (!Array.isArray(data)) return
      const normalized = data.map((item) => {
        const raw = (item && typeof item === 'object' ? item : {}) as Partial<ResumeAsset>
        return {
          exists: Boolean(raw.exists),
          assetId: typeof raw.assetId === 'string' ? raw.assetId : undefined,
          kind: raw.kind === 'IMAGE' ? 'IMAGE' as const : 'PDF' as const,
          available: Boolean(raw.available),
          fileName: typeof raw.fileName === 'string' ? raw.fileName : undefined,
          contentType: typeof raw.contentType === 'string' ? raw.contentType : undefined,
          size: typeof raw.size === 'number' ? raw.size : undefined,
          updatedAt: typeof raw.updatedAt === 'number' ? raw.updatedAt : undefined,
          previewUrl: typeof raw.previewUrl === 'string' ? raw.previewUrl : undefined,
        }
      })
      const pdf = normalized.find((item) => item.kind === 'PDF') ?? { kind: 'PDF' as const, exists: false }
      const image = normalized.find((item) => item.kind === 'IMAGE') ?? { kind: 'IMAGE' as const, exists: false }
      setAssets([pdf, image])
    } catch {
      // A missing asset service is shown as empty cards; it must not block profile editing.
    }
  }, [])

  useEffect(() => {
    let cancelled = false

    const loadProfile = async () => {
      try {
        const response = await fetch(apiUrl('/api/boss/chat/profile'))
        if (!response.ok) throw new Error(`资料加载失败 (${response.status})`)
        const data: unknown = await response.json()
        if (!cancelled) {
          setProfile(normalizeProfile(data))
          setLoaded(true)
        }
      } catch {
        if (!cancelled) {
          setMessage({ kind: 'error', text: '无法连接后端，资料未加载' })
          setLoaded(false)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }

      if (!cancelled) void loadAssets()
    }

    void loadProfile()
    return () => {
      cancelled = true
    }
  }, [loadAssets])

  const updateResume = <K extends ResumeTab>(section: K, items: ResumeState[K]) => {
    setProfile((prev) => ({ ...prev, resume: { ...prev.resume, [section]: items } }))
  }

  const addCity = (city: string) => {
    const value = city.trim()
    if (!value || profile.acceptedCities.includes(value)) return
    setProfile((prev) => ({ ...prev, acceptedCities: [...prev.acceptedCities, value] }))
    setCityInput('')
  }

  const removeCity = (city: string) => {
    setProfile((prev) => ({ ...prev, acceptedCities: prev.acceptedCities.filter((item) => item !== city) }))
  }

  const addFact = () => {
    const text = newFactText.trim()
    if (!text || text.length > 2000) {
      setMessage({ kind: 'error', text: '单条事实需为 1 到 2000 字符' })
      return
    }
    setProfile((prev) => ({ ...prev, facts: [...prev.facts, { id: nextId('fact'), text }] }))
    setNewFactText('')
  }

  const removeFact = (id: string) => {
    setProfile((prev) => ({ ...prev, facts: prev.facts.filter((fact) => fact.id !== id) }))
  }

  const addWork = () => {
    updateResume('work', [
      ...profile.resume.work,
      { id: nextId('work'), company: '', title: '', period: '', summary: '' },
    ])
  }

  const updateWork = (id: string, field: keyof WorkItem, value: string) => {
    updateResume(
      'work',
      profile.resume.work.map((item) => (item.id === id ? { ...item, [field]: value } : item))
    )
  }

  const removeWork = (id: string) => {
    updateResume('work', profile.resume.work.filter((item) => item.id !== id))
  }

  const addProject = () => {
    updateResume('project', [
      ...profile.resume.project,
      { id: nextId('project'), name: '', role: '', period: '', description: '' },
    ])
  }

  const updateProject = (id: string, field: keyof ProjectItem, value: string) => {
    updateResume(
      'project',
      profile.resume.project.map((item) => (item.id === id ? { ...item, [field]: value } : item))
    )
  }

  const removeProject = (id: string) => {
    updateResume('project', profile.resume.project.filter((item) => item.id !== id))
  }

  const addEducation = () => {
    updateResume('education', [
      ...profile.resume.education,
      { id: nextId('education'), school: '', degree: '', major: '', period: '' },
    ])
  }

  const updateEducation = (id: string, field: keyof EducationItem, value: string) => {
    updateResume(
      'education',
      profile.resume.education.map((item) => (item.id === id ? { ...item, [field]: value } : item))
    )
  }

  const removeEducation = (id: string) => {
    updateResume('education', profile.resume.education.filter((item) => item.id !== id))
  }

  const addSkill = () => {
    updateResume('skills', [
      ...profile.resume.skills,
      { id: nextId('skill'), name: '', level: '', description: '' },
    ])
  }

  const updateSkill = (id: string, field: keyof SkillItem, value: string) => {
    updateResume(
      'skills',
      profile.resume.skills.map((item) => (item.id === id ? { ...item, [field]: value } : item))
    )
  }

  const removeSkill = (id: string) => {
    updateResume('skills', profile.resume.skills.filter((item) => item.id !== id))
  }

  const assetFor = (kind: 'PDF' | 'IMAGE') => assets.find((asset) => asset.kind === kind)
  const approvedIdFor = (kind: 'PDF' | 'IMAGE') =>
    kind === 'PDF' ? profile.approvedPdfAssetId : profile.approvedImageAssetId

  const selectAsset = (kind: 'PDF' | 'IMAGE', assetId: string) => {
    setProfile((prev) => ({
      ...prev,
      preferredResumeAssetKind: kind,
      ...(kind === 'PDF' ? { approvedPdfAssetId: assetId } : { approvedImageAssetId: assetId }),
    }))
    setMessage({ kind: 'ok', text: `已选择${kind === 'PDF' ? ' PDF ' : '图片'}简历，保存后生效` })
  }

  const clearAssetSelection = (kind: 'PDF' | 'IMAGE') => {
    setProfile((prev) => ({
      ...prev,
      preferredResumeAssetKind: prev.preferredResumeAssetKind === kind ? '' : prev.preferredResumeAssetKind,
      ...(kind === 'PDF' ? { approvedPdfAssetId: '' } : { approvedImageAssetId: '' }),
    }))
    setMessage({ kind: 'ok', text: '已撤销选择，保存后生效' })
  }

  const uploadAsset = async (kind: 'PDF' | 'IMAGE', file: File) => {
    setUploadingKind(kind)
    setMessage(null)
    try {
      const endpoint = kind === 'PDF'
        ? '/api/boss/config/resume-document'
        : '/api/boss/config/resume-image'
      const form = new FormData()
      form.append('file', file)
      const response = await fetch(apiUrl(endpoint), { method: 'POST', body: form })
      const data = (await response.json().catch(() => null)) as
        | { message?: string; resumeDocument?: ResumeAsset; resumeImage?: ResumeAsset }
        | null
      if (!response.ok) throw new Error(data?.message || `上传失败 (${response.status})`)
      const uploaded = kind === 'PDF' ? data?.resumeDocument : data?.resumeImage
      await loadAssets()
      if (uploaded?.assetId) {
        setProfile((prev) => ({
          ...prev,
          ...(kind === 'PDF'
            ? { approvedPdfAssetId: prev.approvedPdfAssetId === uploaded.assetId ? prev.approvedPdfAssetId : '' }
            : { approvedImageAssetId: prev.approvedImageAssetId === uploaded.assetId ? prev.approvedImageAssetId : '' }),
          ...(prev.preferredResumeAssetKind === kind && approvedIdFor(kind) !== uploaded.assetId
            ? { preferredResumeAssetKind: '' as const }
            : {}),
        }))
      }
      setMessage({ kind: 'ok', text: '已上传，选择使用后保存' })
    } catch (error) {
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '上传失败' })
    } finally {
      setUploadingKind(null)
    }
  }

  const deleteAsset = async (kind: 'PDF' | 'IMAGE') => {
    if (!confirm(`确认删除当前${kind === 'PDF' ? ' PDF ' : '图片'}简历？`)) return
    setMessage(null)
    try {
      const endpoint = kind === 'PDF'
        ? '/api/boss/config/resume-document'
        : '/api/boss/config/resume-image'
      const response = await fetch(apiUrl(endpoint), { method: 'DELETE' })
      const data = (await response.json().catch(() => null)) as { message?: string } | null
      if (!response.ok) throw new Error(data?.message || `删除失败 (${response.status})`)
      setProfile((prev) => ({
        ...prev,
        ...(kind === 'PDF' ? { approvedPdfAssetId: '' } : { approvedImageAssetId: '' }),
        ...(prev.preferredResumeAssetKind === kind ? { preferredResumeAssetKind: '' as const } : {}),
      }))
      await loadAssets()
      setMessage({ kind: 'ok', text: '已删除' })
    } catch (error) {
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '删除失败' })
    }
  }

  const handleSave = async () => {
    if (!loaded) {
      setMessage({ kind: 'error', text: '资料尚未加载，不能保存' })
      return
    }
    const validationError = validateProfile(profile)
    if (validationError) {
      setMessage({ kind: 'error', text: validationError })
      return
    }

    setSaving(true)
    setMessage(null)
    try {
      const response = await fetch(apiUrl('/api/boss/chat/profile'), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(profile),
      })
      const data: unknown = await response.json().catch(() => null)
      if (!response.ok) {
        const message = (data as { message?: string } | null)?.message
        throw new Error(message || `保存失败 (${response.status})`)
      }
      if (!data || typeof data !== 'object') {
        throw new Error('保存响应格式错误')
      }
      setProfile(normalizeProfile(data))
      setMessage({ kind: 'ok', text: '已保存' })
    } catch (error) {
      setMessage({ kind: 'error', text: error instanceof Error ? error.message : '保存失败' })
    } finally {
      setSaving(false)
    }
  }

  const facts = visibleFacts(profile)
  const pdfAsset = assetFor('PDF')
  const imageAsset = assetFor('IMAGE')
  const pdfApproved = Boolean(pdfAsset?.assetId) && profile.approvedPdfAssetId === pdfAsset?.assetId
  const imageApproved = Boolean(imageAsset?.assetId) && profile.approvedImageAssetId === imageAsset?.assetId

  return (
    <div className="space-y-6 max-w-6xl mx-auto pb-12">
      <PageHeader
        icon={<BiUserCircle className="text-2xl text-primary" />}
        title="个人资料"
        actions={
          <Button size="sm" onClick={handleSave} disabled={saving || loading || !loaded}>
            <BiSave className="text-base" />
            <span className="ml-1.5">{saving ? '保存中...' : '保存'}</span>
          </Button>
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

      <Tabs defaultValue="basics" className="w-full">
        <TabsList className="grid w-full grid-cols-3 md:w-auto">
          <TabsTrigger value="basics">基础</TabsTrigger>
          <TabsTrigger value="resume">经历</TabsTrigger>
          <TabsTrigger value="resume-document">简历</TabsTrigger>
        </TabsList>

        <TabsContent value="basics" className="mt-6 space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">基础信息</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                <div>
                  <Label htmlFor="graduationYear">毕业年份</Label>
                  <Input
                    id="graduationYear"
                    type="number"
                    min="2000"
                    max="2100"
                    className="mt-1.5"
                    value={profile.graduationYear ?? ''}
                    onChange={(event) =>
                      setProfile((prev) => ({
                        ...prev,
                        graduationYear: event.target.value ? Number(event.target.value) : null,
                      }))
                    }
                    placeholder="2000 - 2100"
                  />
                </div>

                <div>
                  <Label>意向城市</Label>
                  <div className="mt-1.5 flex gap-2">
                    <Input
                      value={cityInput}
                      onChange={(event) => setCityInput(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          addCity(cityInput)
                        }
                      }}
                      placeholder="输入城市名"
                    />
                    <Button type="button" variant="outline" onClick={() => addCity(cityInput)}>
                      <BiPlus />
                    </Button>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {COMMON_CITIES.map((city) => (
                      <button
                        key={city}
                        type="button"
                        onClick={() => addCity(city)}
                        disabled={profile.acceptedCities.includes(city)}
                        className={`rounded border px-2 py-0.5 text-xs transition-colors ${
                          profile.acceptedCities.includes(city)
                            ? 'cursor-not-allowed border-transparent bg-muted text-muted-foreground opacity-60'
                            : 'border-border bg-background text-foreground hover:bg-muted'
                        }`}
                      >
                        {city}
                      </button>
                    ))}
                  </div>
                  <div className="mt-3 flex min-h-9 flex-wrap gap-2 rounded-md border border-border bg-muted/30 p-2">
                    {profile.acceptedCities.map((city) => (
                      <span
                        key={city}
                        className="inline-flex items-center gap-1 rounded border border-border bg-card px-2.5 py-1 text-xs font-medium text-foreground"
                      >
                        {city}
                        <button
                          type="button"
                          onClick={() => removeCity(city)}
                          className="text-muted-foreground hover:text-destructive"
                          aria-label={`移除 ${city}`}
                        >
                          <BiX />
                        </button>
                      </span>
                    ))}
                    {profile.acceptedCities.length === 0 && (
                      <span className="text-xs text-muted-foreground">暂无</span>
                    )}
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">求职意向</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <Label>工作类型</Label>
                  <Input
                    className="mt-1"
                    value={profile.intentions.workType}
                    onChange={(event) =>
                      setProfile((prev) => ({
                        ...prev,
                        intentions: { ...prev.intentions, workType: event.target.value },
                      }))
                    }
                    placeholder="全职 / 实习 / 兼职"
                  />
                </div>
                <div>
                  <Label>期望薪资（个人）</Label>
                  <Input
                    className="mt-1"
                    value={profile.intentions.expectedSalary}
                    onChange={(event) =>
                      setProfile((prev) => ({
                        ...prev,
                        intentions: { ...prev.intentions, expectedSalary: event.target.value },
                      }))
                    }
                    placeholder="如 200/天 或 15-20K"
                  />
                </div>
                <div>
                  <Label>到岗意向</Label>
                  <Input
                    className="mt-1"
                    value={profile.intentions.arrivalIntention}
                    onChange={(event) =>
                      setProfile((prev) => ({
                        ...prev,
                        intentions: { ...prev.intentions, arrivalIntention: event.target.value },
                      }))
                    }
                    placeholder="随时到岗 / 1个月內"
                  />
                </div>
                <div>
                  <Label>可到岗时间</Label>
                  <Input
                    className="mt-1"
                    value={profile.intentions.availableFrom}
                    onChange={(event) =>
                      setProfile((prev) => ({
                        ...prev,
                        intentions: { ...prev.intentions, availableFrom: event.target.value },
                      }))
                    }
                    placeholder="如 2026-12"
                  />
                </div>
                <div>
                  <Label>每周可实习天数</Label>
                  <Input
                    className="mt-1"
                    type="number"
                    min="1"
                    max="7"
                    value={profile.intentions.weeklyAvailabilityDays ?? ''}
                    onChange={(event) =>
                      setProfile((prev) => ({
                        ...prev,
                        intentions: {
                          ...prev.intentions,
                          weeklyAvailabilityDays: event.target.value ? Number(event.target.value) : null,
                        },
                      }))
                    }
                    placeholder="1 - 7"
                  />
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">补充信息</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex gap-2">
                <Input
                  value={newFactText}
                  onChange={(event) => setNewFactText(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      addFact()
                    }
                  }}
                  placeholder="补充经历之外的信息，如到岗时间、每周可实习天数"
                />
                <Button type="button" onClick={addFact} className="shrink-0">
                  <BiPlus />
                  <span className="ml-1">添加</span>
                </Button>
              </div>

              <div className="space-y-2">
                {facts.map((fact) => (
                  <div
                    key={fact.id}
                    className="flex items-start justify-between gap-3 rounded-lg border border-border bg-card p-3"
                  >
                    <p className="min-w-0 break-words text-sm leading-relaxed text-foreground">{fact.text}</p>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => removeFact(fact.id)}
                      className="h-auto shrink-0 p-1 text-muted-foreground hover:text-destructive"
                      aria-label="删除事实"
                    >
                      <BiTrash />
                    </Button>
                  </div>
                ))}
                {facts.length === 0 && <p className="py-6 text-center text-sm text-muted-foreground">暂无</p>}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="resume" className="mt-6">
          <Card>
            <CardHeader className="border-b border-border pb-3">
              <div className="flex flex-col justify-between gap-3 md:flex-row md:items-center">
                <CardTitle className="text-lg">经历</CardTitle>
                <div className="flex flex-wrap items-center gap-1 rounded-lg bg-muted p-1">
                  {(
                    [
                      ['work', `工作 ${profile.resume.work.length}`],
                      ['project', `项目 ${profile.resume.project.length}`],
                      ['education', `教育 ${profile.resume.education.length}`],
                      ['skills', `技能 ${profile.resume.skills.length}`],
                    ] as const
                  ).map(([value, label]) => (
                    <button
                      key={value}
                      type="button"
                      onClick={() => setResumeTab(value)}
                      className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                        resumeTab === value
                          ? 'bg-background text-foreground shadow-sm'
                          : 'text-muted-foreground hover:text-foreground'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4 pt-6">
              {resumeTab === 'work' && (
                <>
                  <div className="flex justify-end">
                    <Button size="sm" onClick={addWork}>
                      <BiPlus />
                      <span className="ml-1">新增</span>
                    </Button>
                  </div>
                  {profile.resume.work.map((item) => (
                    <Card key={item.id} className="border border-border bg-card/60 p-4">
                      <div className="mb-3 grid grid-cols-1 gap-3 md:grid-cols-3">
                        <div>
                          <Label>
                            公司 <span className="text-destructive">*</span>
                          </Label>
                          <Input
                            className="mt-1"
                            value={item.company}
                            onChange={(event) => updateWork(item.id, 'company', event.target.value)}
                          />
                        </div>
                        <div>
                          <Label>
                            职位 <span className="text-destructive">*</span>
                          </Label>
                          <Input
                            className="mt-1"
                            value={item.title}
                            onChange={(event) => updateWork(item.id, 'title', event.target.value)}
                          />
                        </div>
                        <div className="flex items-end gap-2">
                          <div className="flex-1">
                            <Label>时间</Label>
                            <Input
                              className="mt-1"
                              value={item.period}
                              onChange={(event) => updateWork(item.id, 'period', event.target.value)}
                            />
                          </div>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => removeWork(item.id)}
                            className="text-muted-foreground hover:text-destructive"
                            aria-label="删除工作经历"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      </div>
                      <div>
                        <Label>描述</Label>
                        <Textarea
                          className="mt-1 text-sm"
                          rows={2}
                          value={item.summary}
                          onChange={(event) => updateWork(item.id, 'summary', event.target.value)}
                        />
                      </div>
                    </Card>
                  ))}
                  {profile.resume.work.length === 0 && (
                    <p className="py-6 text-center text-sm text-muted-foreground">暂无</p>
                  )}
                </>
              )}

              {resumeTab === 'project' && (
                <>
                  <div className="flex justify-end">
                    <Button size="sm" onClick={addProject}>
                      <BiPlus />
                      <span className="ml-1">新增</span>
                    </Button>
                  </div>
                  {profile.resume.project.map((item) => (
                    <Card key={item.id} className="border border-border bg-card/60 p-4">
                      <div className="mb-3 grid grid-cols-1 gap-3 md:grid-cols-3">
                        <div>
                          <Label>
                            项目名称 <span className="text-destructive">*</span>
                          </Label>
                          <Input
                            className="mt-1"
                            value={item.name}
                            onChange={(event) => updateProject(item.id, 'name', event.target.value)}
                          />
                        </div>
                        <div>
                          <Label>角色</Label>
                          <Input
                            className="mt-1"
                            value={item.role}
                            onChange={(event) => updateProject(item.id, 'role', event.target.value)}
                          />
                        </div>
                        <div className="flex items-end gap-2">
                          <div className="flex-1">
                            <Label>时间</Label>
                            <Input
                              className="mt-1"
                              value={item.period}
                              onChange={(event) => updateProject(item.id, 'period', event.target.value)}
                            />
                          </div>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => removeProject(item.id)}
                            className="text-muted-foreground hover:text-destructive"
                            aria-label="删除项目经历"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      </div>
                      <div>
                        <Label>描述</Label>
                        <Textarea
                          className="mt-1 text-sm"
                          rows={2}
                          value={item.description}
                          onChange={(event) => updateProject(item.id, 'description', event.target.value)}
                        />
                      </div>
                    </Card>
                  ))}
                  {profile.resume.project.length === 0 && (
                    <p className="py-6 text-center text-sm text-muted-foreground">暂无</p>
                  )}
                </>
              )}

              {resumeTab === 'education' && (
                <>
                  <div className="flex justify-end">
                    <Button size="sm" onClick={addEducation}>
                      <BiPlus />
                      <span className="ml-1">新增</span>
                    </Button>
                  </div>
                  {profile.resume.education.map((item) => (
                    <Card key={item.id} className="border border-border bg-card/60 p-4">
                      <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
                        <div>
                          <Label>
                            学校 <span className="text-destructive">*</span>
                          </Label>
                          <Input
                            className="mt-1"
                            value={item.school}
                            onChange={(event) => updateEducation(item.id, 'school', event.target.value)}
                          />
                        </div>
                        <div>
                          <Label>学历</Label>
                          <Input
                            className="mt-1"
                            value={item.degree}
                            onChange={(event) => updateEducation(item.id, 'degree', event.target.value)}
                          />
                        </div>
                        <div>
                          <Label>专业</Label>
                          <Input
                            className="mt-1"
                            value={item.major}
                            onChange={(event) => updateEducation(item.id, 'major', event.target.value)}
                          />
                        </div>
                        <div className="flex items-end gap-2">
                          <div className="flex-1">
                            <Label>时间</Label>
                            <Input
                              className="mt-1"
                              value={item.period}
                              onChange={(event) => updateEducation(item.id, 'period', event.target.value)}
                            />
                          </div>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => removeEducation(item.id)}
                            className="text-muted-foreground hover:text-destructive"
                            aria-label="删除教育经历"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      </div>
                    </Card>
                  ))}
                  {profile.resume.education.length === 0 && (
                    <p className="py-6 text-center text-sm text-muted-foreground">暂无</p>
                  )}
                </>
              )}

              {resumeTab === 'skills' && (
                <>
                  <div className="flex justify-end">
                    <Button size="sm" onClick={addSkill}>
                      <BiPlus />
                      <span className="ml-1">新增</span>
                    </Button>
                  </div>
                  {profile.resume.skills.map((item) => (
                    <Card key={item.id} className="border border-border bg-card/60 p-4">
                      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                        <div>
                          <Label>
                            技能 <span className="text-destructive">*</span>
                          </Label>
                          <Input
                            className="mt-1"
                            value={item.name}
                            onChange={(event) => updateSkill(item.id, 'name', event.target.value)}
                          />
                        </div>
                        <div>
                          <Label>熟练程度</Label>
                          <Input
                            className="mt-1"
                            value={item.level}
                            onChange={(event) => updateSkill(item.id, 'level', event.target.value)}
                          />
                        </div>
                        <div className="flex items-end gap-2">
                          <div className="flex-1">
                            <Label>描述</Label>
                            <Input
                              className="mt-1"
                              value={item.description}
                              onChange={(event) => updateSkill(item.id, 'description', event.target.value)}
                            />
                          </div>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => removeSkill(item.id)}
                            className="text-muted-foreground hover:text-destructive"
                            aria-label="删除技能"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      </div>
                    </Card>
                  ))}
                  {profile.resume.skills.length === 0 && (
                    <p className="py-6 text-center text-sm text-muted-foreground">暂无</p>
                  )}
                </>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="resume-document" className="mt-6 space-y-6">
          {(['PDF', 'IMAGE'] as const).map((kind) => {
            const asset = kind === 'PDF' ? pdfAsset : imageAsset
            const approved = kind === 'PDF' ? pdfApproved : imageApproved
            const selected = profile.preferredResumeAssetKind === kind && approved
            const inputId = `resume-upload-${kind.toLowerCase()}`
            const usable = Boolean(asset?.exists && asset.assetId && asset.available !== false)
            return (
              <Card key={kind}>
                <CardHeader>
                  <CardTitle className="text-lg">{kind === 'PDF' ? 'PDF 简历' : '图片简历'}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="flex flex-col justify-between gap-3 rounded-lg border border-border bg-card p-4 md:flex-row md:items-center">
                    <div className="min-w-0 text-sm">
                      <p className="font-medium text-foreground">{asset?.exists ? asset.fileName || '已上传' : '未上传'}</p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {asset?.exists && asset.size ? `${(asset.size / 1024).toFixed(1)} KB` : '暂无文件'}
                        {approved ? (selected ? ' · 已选择' : ' · 已批准') : asset?.exists ? ' · 未选择' : ''}
                        {asset?.exists && asset.available === false ? ' · 不可用' : ''}
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <input
                        id={inputId}
                        type="file"
                        className="hidden"
                        accept={kind === 'PDF' ? '.pdf,application/pdf' : 'image/png,image/jpeg,image/gif'}
                        onChange={(event) => {
                          const file = event.target.files?.[0]
                          if (file) void uploadAsset(kind, file)
                          event.target.value = ''
                        }}
                      />
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={uploadingKind !== null}
                        onClick={() => document.getElementById(inputId)?.click()}
                      >
                        <BiUpload />
                        <span className="ml-1">{uploadingKind === kind ? '上传中...' : asset?.exists ? '替换' : '上传'}</span>
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={!usable}
                        onClick={() => setPreviewAsset(asset ?? null)}
                      >
                        <BiShow />
                        <span className="ml-1">预览</span>
                      </Button>
                      {selected ? (
                        <Button variant="outline" size="sm" onClick={() => clearAssetSelection(kind)}>
                          撤销选择
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          disabled={!usable}
                          onClick={() => selectAsset(kind, asset?.assetId ?? '')}
                        >
                          选择使用
                        </Button>
                      )}
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={!asset?.exists}
                        onClick={() => void deleteAsset(kind)}
                        className="text-destructive hover:bg-destructive/10"
                      >
                        <BiTrash />
                        <span className="ml-1">删除</span>
                      </Button>
                    </div>
                  </div>
                  {!asset?.exists && (
                    <p className="text-xs text-muted-foreground">
                      {kind === 'PDF' ? '支持 PDF，≤10MB' : '支持 PNG/JPG/GIF，≤10MB'}
                    </p>
                  )}
                </CardContent>
              </Card>
            )
          })}

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">简历文本</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="mb-1.5 flex items-center justify-between">
                <Label htmlFor="resumeText">内容</Label>
                <span className="text-xs text-muted-foreground">{profile.resumeText.length} / 20000</span>
              </div>
              <Textarea
                id="resumeText"
                rows={6}
                className="text-sm"
                value={profile.resumeText}
                onChange={(event) => {
                  const text = event.target.value
                  if (text.length <= 20000) {
                    setProfile((prev) => ({ ...prev, resumeText: text }))
                  }
                }}
              />
            </CardContent>
          </Card>

          {previewAsset && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="dialog" aria-modal="true">
              <div className="flex max-h-[90vh] w-full max-w-4xl flex-col rounded-lg border border-border bg-card">
                <div className="flex items-center justify-between border-b border-border p-3">
                  <p className="text-sm font-medium">{previewAsset.kind === 'PDF' ? 'PDF 预览' : '图片预览'}</p>
                  <Button variant="ghost" size="sm" onClick={() => setPreviewAsset(null)} aria-label="关闭预览">
                    <BiX />
                  </Button>
                </div>
                <div className="min-h-0 flex-1 overflow-auto p-3">
                  {previewAsset.kind === 'PDF' ? (
                    <iframe
                      title="PDF 预览"
                      src={apiUrl(previewAsset.previewUrl || '/api/boss/config/resume-document/content')}
                      className="h-[70vh] w-full rounded border border-border"
                    />
                  ) : (
                    <Image
                      src={apiUrl(previewAsset.previewUrl || '/api/boss/config/resume-image/content')}
                      alt="图片简历预览"
                      width={1200}
                      height={1600}
                      unoptimized
                      className="mx-auto max-h-[70vh] w-auto object-contain"
                    />
                  )}
                </div>
              </div>
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
