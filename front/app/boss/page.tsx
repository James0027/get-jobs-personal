'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import Image from 'next/image'
import { createSSEWithBackoff } from '@/lib/sse'
import { createPortal } from 'react-dom'
import { BiBriefcase, BiSave, BiSearch, BiMap, BiMoney, BiBuilding, BiTime, BiBarChart, BiTrash, BiPlus, BiPlay, BiStop, BiLogOut, BiImage, BiUpload, BiZoomIn, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import PageHeader from '@/app/components/PageHeader'
import AnalysisContent from '@/app/boss/analysis/AnalysisContent'
import { API_BASE } from '@/lib/api'
import IndustryPicker, { IndustryGroup } from './components/IndustryPicker'
import ChatWorkspace from './components/ChatWorkspace'

interface BossConfig {
  id?: number
  debugger?: number
  waitTime?: number
  keywords?: string
  cityCode?: string
  targetCount?: number
  graduationYear?: number
  aiOnly?: number
  acceptedCities?: string
  approvedResume?: number
  autoResume?: number
  autoLocation?: number
  enableFollowUp?: number
  industry?: string
  jobType?: string
  experience?: string
  degree?: string
  salary?: string
  scale?: string
  stage?: string
  sayHi?: string
  enableAi?: number
  sendImgResume?: number
  allowRepeatChat?: number
  repeatChatMessages?: string
  filterDeadHr?: number
  deadStatus?: string
}

interface BossOption {
  id: number
  type: string
  name: string
  code: string
  // 可选的排序字段（来自后端/数据库），用于前端排序显示
  sort_order?: number
  sortOrder?: number
}

interface BossOptions {
  city: BossOption[]
  industry: BossOption[]
  experience: BossOption[]
  jobType: BossOption[]
  salary: BossOption[]
  degree: BossOption[]
  scale: BossOption[]
  stage: BossOption[]
}

interface BlacklistItem {
  id: number
  value: string
  type: string
}

interface ResumeImageInfo {
  exists: boolean
  fileName?: string
  contentType?: string
  size?: number
  updatedAt?: number
  previewUrl?: string
}

interface ResumeDocumentInfo {
  exists: boolean
  fileName?: string
  size?: number
  updatedAt?: number
  assetId?: string
  previewUrl?: string
}

interface BossProfile {
  version?: string
  graduationYear?: number | null
  acceptedCities?: string[]
  facts?: { id: string; text: string }[]
  approvedResumeAssetId?: string
  approvedPdfAssetId?: string
  autoResume?: boolean
  autoLocation?: boolean
  enabled?: boolean
  resume?: any
  resumeText?: string
  resumeTextHash?: string
  approvedResumeTextHash?: string
  derivedFactIds?: string[]
  autoReply?: boolean
  followUp?: any
}

export default function BossPage() {
  const [config, setConfig] = useState<BossConfig>({
    keywords: '',
    cityCode: '',
    targetCount: 50,
    aiOnly: 1,
    industry: '',
    jobType: '',
    experience: '',
    degree: '',
    salary: '',
    scale: '',
    stage: '',
    sendImgResume: 0,
    allowRepeatChat: 0,
    filterDeadHr: 0,
  })
  // 关键词显示用（无括号无引号，逗号分隔）
  const [keywordsDisplay, setKeywordsDisplay] = useState<string>('')
  // 多选选中的代码集合（按括号列表保存）
  const [selectedIndustry, setSelectedIndustry] = useState<string[]>([])
  const [selectedCities, setSelectedCities] = useState<string[]>([])
  const [industryGroups, setIndustryGroups] = useState<IndustryGroup[]>([])
  const [delivery, setDelivery] = useState<{ taskId: string; version: number; targetCount: number; successCount: number; failedCount: number; unknownCount: number; skippedCount: number; repeatCount: number; currentCity?: string; state?: string; cityQuotas?: Record<string, number>; pendingAction?: { jobId: string; text: string; city: string; repeat: boolean } } | null>(null)
  const hasUnknown = Boolean(delivery?.unknownCount && delivery.unknownCount > 0)
  const canResume = Boolean(delivery && ['interrupted', 'reconciled', 'cancelled', 'failed'].includes(delivery.state || '') && (delivery.successCount ?? 0) < (delivery.targetCount ?? 0))
  const [reconcileChecked, setReconcileChecked] = useState(false)
  const [reconcileBusy, setReconcileBusy] = useState(false)
  const [selectedExperience, setSelectedExperience] = useState<string[]>([])
  const [selectedDegree, setSelectedDegree] = useState<string[]>([])
  const [selectedScale, setSelectedScale] = useState<string[]>([])
  const [selectedStage, setSelectedStage] = useState<string[]>([])
  const [selectedSalary, setSelectedSalary] = useState<string[]>([])
  const [options, setOptions] = useState<BossOptions>({
    city: [],
    industry: [],
    experience: [],
    jobType: [],
    salary: [],
    degree: [],
    scale: [],
    stage: [],
  })
  const [blacklist, setBlacklist] = useState<BlacklistItem[]>([])
  const [newBlacklistKeyword, setNewBlacklistKeyword] = useState('')
  const [blacklistType, setBlacklistType] = useState('company') // 默认为公司
  const [loading, setLoading] = useState(true)
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  // title 可选：这个弹框既用于保存结果，也用于投递启动失败等提示
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string; title?: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [resumeImage, setResumeImage] = useState<ResumeImageInfo>({ exists: false })
  const [resumeImageUploading, setResumeImageUploading] = useState(false)
  const [resumeImageMessage, setResumeImageMessage] = useState<{ success: boolean; text: string } | null>(null)
  const [localResumePreviewUrl, setLocalResumePreviewUrl] = useState<string | null>(null)
  const [showResumePreview, setShowResumePreview] = useState(false)
  const resumeImageInputRef = useRef<HTMLInputElement>(null)

  // PDF 简历资产与 Profile
  const [resumeDocument, setResumeDocument] = useState<ResumeDocumentInfo>({ exists: false })
  const [resumeDocUploading, setResumeDocUploading] = useState(false)
  const [resumeDocMessage, setResumeDocMessage] = useState<{ success: boolean; text: string } | null>(null)
  const resumeDocInputRef = useRef<HTMLInputElement>(null)
  const [chatProfile, setChatProfile] = useState<BossProfile | null>(null)
  const [graduationYear, setGraduationYear] = useState<string>('')

  // 脏状态管理与新建任务弹窗
  const [dirty, setDirty] = useState(false)
  const [showNewTaskDialog, setShowNewTaskDialog] = useState(false)
  const [newTaskTargetCount, setNewTaskTargetCount] = useState<number>(50)

  useEffect(() => {
    let cancelled = false
    const refresh = async () => {
      try {
        const response = await fetch(`${API_BASE}/api/boss/status`)
        if (!response.ok) return
        const data = await response.json()
        if (!cancelled) { setDelivery(data.delivery || data.status?.delivery || null); setIsDelivering(Boolean(data.isRunning ?? data.status?.isRunning)) }
      } catch { /* 连接恢复后下次轮询重试 */ }
    }
    void refresh()
    const timer = setInterval(() => { void refresh() }, 3000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  useEffect(() => {
    return () => {
      if (localResumePreviewUrl) URL.revokeObjectURL(localResumePreviewUrl)
    }
  }, [localResumePreviewUrl])

  useEffect(() => {
    if (!showResumePreview) return

    const previousOverflow = document.body.style.overflow
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setShowResumePreview(false)
    }
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', handleEscape)
    }
  }, [showResumePreview])

  useEffect(() => {
    fetchAllData()

    // 确保在客户端环境且 EventSource 可用
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      onOpen: () => {
        console.log('[SSE] 连接已打开')
      },
      onError: (e, attempt, delay) => {
        console.warn(`[SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.bossLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === 'boss') {
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => {
      client.close()
    }
  }, [])

  const fetchAllData = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/boss/config`)
      const data = await response.json()

      console.log('Fetched data:', data)
      console.log('Blacklist:', data.blacklist)

      const fetchedResumeImage: ResumeImageInfo = data.resumeImage?.exists
        ? data.resumeImage
        : { exists: false }
      setResumeImage(fetchedResumeImage)

      const fetchedResumeDoc: ResumeDocumentInfo = data.resumeDocument?.exists
        ? data.resumeDocument
        : { exists: false }
      setResumeDocument(fetchedResumeDoc)

      if (data.config) {
        // 规范化职位类型：后端可能返回单值或括号列表，此处取第一个值用于下拉回显
        const normalizeJobType = (raw?: string): string => {
          if (!raw) return ''
          const list = parseListString(raw)
          if (list.length > 0) return list[0]
          return raw
        }
        setConfig({
          ...data.config,
          allowRepeatChat: data.config.allowRepeatChat ?? 0,
          repeatChatMessages: data.config.repeatChatMessages ?? '',
          cityCode: data.config.cityCode || '',
          targetCount: data.config.targetCount ?? 50,
          aiOnly: data.config.aiOnly ?? 1,
          jobType: normalizeJobType(data.config.jobType),
          // 没有图片时不能保留开启状态，否则开关会处于“开启但不可用”。
          sendImgResume: fetchedResumeImage.exists ? (data.config.sendImgResume ?? 0) : 0,
        })
        // 将后端存储的关键词（可能是 JSON 数组或括号列表）转为展示用逗号分隔文本
        const toDisplayKeywords = (raw?: string): string => {
          if (!raw) return ''
          const s = raw.trim()
          // 尝试作为 JSON 数组解析
          if (s.startsWith('[') && s.endsWith(']')) {
            try {
              const arr = JSON.parse(s)
              if (Array.isArray(arr)) {
                return arr.map((v) => String(v).trim()).filter((v) => v.length > 0).join(', ')
              }
            } catch (_) {
              // 非严格 JSON，如 [a,b]，走拆括号与逗号分隔
              const inner = s.slice(1, -1)
              return inner
                .split(',')
                .map((v) => v.trim().replace(/^"|"$/g, ''))
                .filter((v) => v.length > 0)
                .join(', ')
            }
          }
          // 普通文本：直接返回，去掉多余空格
          return s
        }
        setKeywordsDisplay(toDisplayKeywords(data.config.keywords))
        // 解析括号列表为数组
        setSelectedIndustry(parseListString(data.config.industry))
        setSelectedExperience(parseListString(data.config.experience))
        setSelectedDegree(parseListString(data.config.degree))
        setSelectedScale(parseListString(data.config.scale))
        setSelectedStage(parseListString(data.config.stage))
        setSelectedSalary(parseListString(data.config.salary))
      }
      if (data.options) {
        // 按 sort_order 或固定名称顺序排序；都没有时按名称兜底
        const CITY_ORDER = [
          // 顶层：全国 + 一线（北上广深）
          '全国','北京','上海','广州','深圳',
          // 准一线（图片顺序，从上到下）
          '杭州','成都','南京',
          '武汉','苏州','重庆','天津',
          '长沙','青岛','宁波','无锡',
          '西安','郑州','合肥','厦门','东莞',
          // 二线（图片顺序）
          '济南','福州','佛山','昆明','大连','沈阳','常州','哈尔滨','南昌','泉州',
          '南通','烟台','温州','贵阳','南宁','石家庄','长春','嘉兴','珠海','太原',
          '绍兴','金华','潍坊','徐州','惠州','台州','扬州','中山','乌鲁木齐','兰州',
          // 省会补充（图片底部出现的省会/直辖市）
          '海口','呼和浩特','银川'
        ]
        const orderMap = new Map<string, number>(CITY_ORDER.map((n, i) => [n, i + 1]))
        // 城市排序：仅在后端提供 sortOrder 时按其排序；否则保留后端返回顺序
        const cityList = data.options.city || []
        const cityHasOrder = cityList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
        let sortedCity = cityHasOrder
          ? [...cityList]
              .map((o, idx) => ({ o, idx }))
              .sort((a, b) => {
                const ar = a.o.sortOrder ?? a.o.sort_order
                const br = b.o.sortOrder ?? b.o.sort_order
                if (ar == null && br == null) return a.idx - b.idx // 都无排序，保持原序
                if (ar == null) return 1 // 无排序的排在已排序之后，保持原序
                if (br == null) return -1
                if (ar !== br) return ar - br
                return a.idx - b.idx // 稳定排序
              })
              .map(({ o }) => o)
          : cityList

        // 兜底：如果后端未返回『不限』（code='0'），前端补充一个，确保可选
        const hasUnlimitedCity = sortedCity.some((c: BossOption) => c.code === '0' || c.name === '不限')
        if (!hasUnlimitedCity) {
          sortedCity = [{ id: -1, type: 'city', name: '不限', code: '0', sortOrder: 0 }, ...sortedCity]
        }

        // 行业排序：仅在后端提供 sortOrder 时按其排序；否则保留后端返回顺序
        const industryList = data.options.industry || []
        const industryHasOrder = industryList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
        const sortedIndustry = industryHasOrder
          ? [...industryList]
              .map((o, idx) => ({ o, idx }))
              .sort((a, b) => {
                const ar = a.o.sortOrder ?? a.o.sort_order
                const br = b.o.sortOrder ?? b.o.sort_order
                if (ar == null && br == null) return a.idx - b.idx
                if (ar == null) return 1
                if (br == null) return -1
                if (ar !== br) return ar - br
                return a.idx - b.idx
              })
              .map(({ o }) => o)
          : industryList

        setOptions({
          ...data.options,
          city: sortedCity,
          industry: sortedIndustry,
        })

        // 将配置中的中文值映射为代码，用于UI显示与选择匹配
        const toCodes = (opts: BossOption[], items: string[]) => {
          const codeSet = new Set(opts.map(o => o.code))
          return items.map(it => {
            if (codeSet.has(it)) return it
            const byName = opts.find(o => o.name === it)
            return byName ? byName.code : it
          })
        }

        setIndustryGroups(data.industryGroups || [])
        setSelectedCities(toCodes(sortedCity, parseListString(data.config?.cityCode)))

        // 职位类型：若当前为中文名，转换为对应的 code 以在下拉中回显
        const currentJobTypeRaw = data.config?.jobType || ''
        const currentJobTypeHead = (() => {
          const list = parseListString(currentJobTypeRaw)
          return list.length > 0 ? list[0] : currentJobTypeRaw
        })()
        const jobTypeMatchByCode = (data.options.jobType || []).find((t: BossOption) => t.code === currentJobTypeHead)
        const jobTypeMatchByName = (data.options.jobType || []).find((t: BossOption) => t.name === currentJobTypeHead)
        const normalizedJobType = jobTypeMatchByCode ? jobTypeMatchByCode.code : (jobTypeMatchByName ? jobTypeMatchByName.code : '')
        setConfig(prev => ({ ...prev, jobType: normalizedJobType }))

        // HR活跃过滤开关：后端为 0/1，前端直接回显为数字
        const normalizedFilterDeadHr = (data.config?.filterDeadHr ?? 0)
        setConfig(prev => ({ ...prev, filterDeadHr: normalizedFilterDeadHr }))

        // 其它多选选项：将中文名称转换为代码以匹配 MultiSelect 的 selected
        setSelectedIndustry(toCodes(data.options.industry || [], parseListString(data.config?.industry)))
        setSelectedExperience(toCodes(data.options.experience || [], parseListString(data.config?.experience)))
        setSelectedDegree(toCodes(data.options.degree || [], parseListString(data.config?.degree)))
        setSelectedScale(toCodes(data.options.scale || [], parseListString(data.config?.scale)))
        setSelectedStage(toCodes(data.options.stage || [], parseListString(data.config?.stage)))
        setSelectedSalary(toCodes(data.options.salary || [], parseListString(data.config?.salary)))
      }
      if (data.blacklist) {
        // 兼容处理：将type为'boss'的旧数据视为'company'
        const normalizedBlacklist = data.blacklist.map((item: BlacklistItem) => ({
          ...item,
          type: item.type === 'boss' ? 'company' : item.type
        }))
        console.log('Normalized blacklist:', normalizedBlacklist)
        setBlacklist(normalizedBlacklist)
      }

      // 获取 profile 中的 graduationYear 与 approvedPdfAssetId
      try {
        const profileRes = await fetch(`${API_BASE}/api/boss/chat/profile`)
        if (profileRes.ok) {
          const profData = await profileRes.json()
          const p = profData.profile || profData
          setChatProfile(p)
          setGraduationYear(p.graduationYear != null ? String(p.graduationYear) : '')
        }
      } catch (err) {
        console.warn('获取 profile 失败:', err)
      }
    } catch (error) {
      console.error('Failed to fetch data:', error)
    } finally {
      setLoading(false)
    }
  }

  // 工具：解析括号列表字符串为数组，如 "[a,b]" 或 "a,b"
  const parseListString = (raw?: string): string[] => {
    if (!raw) return []
    let s = raw.trim()
    if (s.startsWith('[') && s.endsWith(']')) {
      s = s.slice(1, -1)
    }
    if (!s) return []
    return s
      .split(',')
      .map(v => v.trim().replace(/^"|"$/g, ''))
      .filter(v => v.length > 0)
  }

  // 工具：将数组转为括号列表字符串
  const toBracketList = (list: string[]): string => {
    if (!list || list.length === 0) return ''
    return `[${list.join(',')}]`
  }

  const handleSave = async (silent: boolean = false, overrides?: Partial<BossConfig>) => {
    try {
      // 毕业年份校验：空允许，填了必须在 2000-2100 整数
      let parsedGradYear: number | null = null
      if (graduationYear.trim()) {
        const y = Number(graduationYear.trim())
        if (!Number.isInteger(y) || y < 2000 || y > 2100) {
          throw new Error('毕业年份必须为 2000 至 2100 之间的整数（或留空）')
        }
        parsedGradYear = y
      }

      // 组装要保存的负载：多选使用括号列表
      const payload: BossConfig = {
        ...config,
        // 覆盖字段（用于失焦时使用当前控件值，避免异步状态滞后）
        ...(overrides || {}),
        // 关键词：前端发送逗号分隔的纯文本，后端统一组装为 JSON 列表
        keywords: keywordsDisplay,
        cityCode: toBracketList(selectedCities),
        industry: toBracketList(selectedIndustry),
        experience: toBracketList(selectedExperience),
        degree: toBracketList(selectedDegree),
        scale: toBracketList(selectedScale),
        stage: toBracketList(selectedStage),
        salary: toBracketList(selectedSalary),
      }
      if (!Number.isInteger(payload.targetCount) || (payload.targetCount ?? 0) <= 0 || (payload.targetCount ?? 0) > 10000) throw new Error('投递目标必须为1至10000的整数')
      const response = await fetch(`${API_BASE}/api/boss/config`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      })

      if (!response.ok) {
        console.warn('保存配置失败：后端返回非 2xx 状态')
        if (!silent) {
          const error = await response.json().catch(() => ({}))
          setSaveResult({ success: false, message: error.message || '保存失败：后端返回异常状态。' })
          setShowSaveDialog(true)
        }
        return
      }

      // 保存 Profile 中的 graduationYear（必须完整回传，只改 graduationYear）
      try {
        let currentProf = chatProfile
        if (!currentProf) {
          const pRes = await fetch(`${API_BASE}/api/boss/chat/profile`)
          if (pRes.ok) {
            const pd = await pRes.json()
            currentProf = pd.profile || pd
          }
        }
        if (currentProf) {
          const updatedProf = {
            ...currentProf,
            graduationYear: parsedGradYear,
          }
          const putRes = await fetch(`${API_BASE}/api/boss/chat/profile`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updatedProf),
          })
          if (!putRes.ok) {
            const errJson = await putRes.json().catch(() => ({}))
            throw new Error(errJson.message || '保存毕业年份失败')
          }
          const savedP = await putRes.json()
          setChatProfile(savedP.profile || savedP)
        }
      } catch (profErr) {
        console.error('保存 profile 毕业年份失败:', profErr)
        if (!silent) {
          setSaveResult({ success: false, message: profErr instanceof Error ? profErr.message : '保存毕业年份失败' })
          setShowSaveDialog(true)
        }
        return
      }

      // 统一保存 Cookie（Boss）
      try {
        await fetch(`${API_BASE}/api/cookie/save?platform=boss`, { method: 'POST' })
      } catch (e) {
        console.warn('保存 Cookie 失败（Boss）:', e)
      }

      setDirty(false)
      fetchAllData()
      if (!silent) {
        setSaveResult({ success: true, message: '保存成功，配置与Cookie已更新。' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to save config:', error)
      if (!silent) {
        setSaveResult({ success: false, message: error instanceof Error ? error.message : '保存失败：网络或服务异常。' })
        setShowSaveDialog(true)
      }
    }
  }

  const handleResumeImageChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    const allowedTypes = new Set(['image/png', 'image/jpeg', 'image/gif'])
    const allowedExtension = /\.(png|jpe?g|gif)$/i.test(file.name)
    if ((!allowedTypes.has(file.type) && !allowedExtension) || file.size === 0) {
      setResumeImageMessage({ success: false, text: '请选择有效的 PNG、JPG、JPEG 或 GIF 图片。' })
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      setResumeImageMessage({ success: false, text: '图片大小不能超过 10MB。' })
      return
    }

    setLocalResumePreviewUrl(URL.createObjectURL(file))
    setResumeImageUploading(true)
    setResumeImageMessage(null)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const response = await fetch(`${API_BASE}/api/boss/config/resume-image`, {
        method: 'POST',
        body: formData,
      })
      const data = await response.json().catch(() => ({}))
      if (!response.ok) {
        throw new Error(data.message || `上传失败（HTTP ${response.status}）`)
      }

      setResumeImage(data.resumeImage || { exists: true })
      setLocalResumePreviewUrl(null)
      setResumeImageMessage({ success: true, text: '图片简历上传成功，可以预览或重新上传。' })
    } catch (error) {
      setLocalResumePreviewUrl(null)
      setResumeImageMessage({
        success: false,
        text: error instanceof Error ? error.message : '图片简历上传失败，请确认后端服务已启动。',
      })
    } finally {
      setResumeImageUploading(false)
    }
  }

  const formatFileSize = (size?: number) => {
    if (size == null) return ''
    return size >= 1024 * 1024
      ? `${(size / 1024 / 1024).toFixed(2)} MB`
      : `${Math.max(1, Math.round(size / 1024))} KB`
  }

  const resumePreviewUrl = localResumePreviewUrl || (
    resumeImage.exists && resumeImage.previewUrl
      ? `${API_BASE}${resumeImage.previewUrl}?v=${resumeImage.updatedAt || 0}`
      : null
  )

  const handleResumeDocumentChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    if (!file.name.toLowerCase().endsWith('.pdf') || file.size === 0) {
      setResumeDocMessage({ success: false, text: '请选择有效的 PDF 文件。' })
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      setResumeDocMessage({ success: false, text: 'PDF 文件大小不能超过 10MB。' })
      return
    }

    setResumeDocUploading(true)
    setResumeDocMessage(null)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const response = await fetch(`${API_BASE}/api/boss/config/resume-document`, {
        method: 'POST',
        body: formData,
      })
      const data = await response.json().catch(() => ({}))
      if (!response.ok) {
        throw new Error(data.message || `上传失败（HTTP ${response.status}）`)
      }

      setResumeDocMessage({ success: true, text: 'PDF 简历上传成功。' })
      fetchAllData()
    } catch (error) {
      setResumeDocMessage({
        success: false,
        text: error instanceof Error ? error.message : 'PDF 简历上传失败，请确认后端服务已启动。',
      })
    } finally {
      setResumeDocUploading(false)
    }
  }

  const handleApprovePdf = async () => {
    if (!resumeDocument.assetId) {
      setResumeDocMessage({ success: false, text: '当前没有可批准的 PDF 简历资产，请先上传。' })
      return
    }
    try {
      let currentProf = chatProfile
      if (!currentProf) {
        const pRes = await fetch(`${API_BASE}/api/boss/chat/profile`)
        if (pRes.ok) {
          const pd = await pRes.json()
          currentProf = pd.profile || pd
        }
      }
      if (!currentProf) throw new Error('无法读取个人资料')
      const updatedProf = {
        ...currentProf,
        approvedPdfAssetId: resumeDocument.assetId,
      }
      const res = await fetch(`${API_BASE}/api/boss/chat/profile`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updatedProf),
      })
      if (!res.ok) {
        const errJson = await res.json().catch(() => ({}))
        throw new Error(errJson.message || '批准 PDF 简历失败')
      }
      const data = await res.json()
      setChatProfile(data.profile || data)
      setResumeDocMessage({ success: true, text: '已批准当前 PDF 简历。' })
    } catch (err) {
      setResumeDocMessage({ success: false, text: err instanceof Error ? err.message : '批准失败' })
    }
  }

  const handleRevokePdfApproval = async () => {
    try {
      let currentProf = chatProfile
      if (!currentProf) {
        const pRes = await fetch(`${API_BASE}/api/boss/chat/profile`)
        if (pRes.ok) {
          const pd = await pRes.json()
          currentProf = pd.profile || pd
        }
      }
      if (!currentProf) throw new Error('无法读取个人资料')
      const updatedProf = {
        ...currentProf,
        approvedPdfAssetId: '',
      }
      const res = await fetch(`${API_BASE}/api/boss/chat/profile`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updatedProf),
      })
      if (!res.ok) {
        const errJson = await res.json().catch(() => ({}))
        throw new Error(errJson.message || '撤销批准失败')
      }
      const data = await res.json()
      setChatProfile(data.profile || data)
      setResumeDocMessage({ success: true, text: '已撤销 PDF 简历批准。' })
    } catch (err) {
      setResumeDocMessage({ success: false, text: err instanceof Error ? err.message : '撤销批准失败' })
    }
  }

  const handleAddBlacklist = async () => {
    if (!newBlacklistKeyword.trim()) {
      // 输入为空：不弹框，直接返回
      return
    }

    try {
      const response = await fetch(`${API_BASE}/api/boss/config/blacklist`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          value: newBlacklistKeyword,
          type: blacklistType, // 使用选中的类型
        }),
      })

      if (response.ok) {
        setNewBlacklistKeyword('')
        setDirty(false)
        fetchAllData()
      } else {
        // 添加失败：不弹框
        console.warn('添加黑名单失败：后端返回非 2xx 状态')
      }
    } catch (error) {
      console.error('Failed to add blacklist:', error)
      // 添加失败：不弹框
    }
  }

  const handleDeleteBlacklist = async (id: number) => {
    try {
      const response = await fetch(`${API_BASE}/api/boss/config/blacklist/${id}`, {
        method: 'DELETE',
      })

      if (response.ok) {
        setDirty(false)
        fetchAllData()
      } else {
        // 删除失败：不弹框
        console.warn('删除黑名单失败：后端返回非 2xx 状态')
      }
    } catch (error) {
      console.error('Failed to delete blacklist:', error)
      // 删除失败：不弹框
    }
  }

  const handleStartNewDelivery = async (targetCountOverride?: number) => {
    try {
      const finalTargetCount = targetCountOverride ?? config.targetCount ?? 50
      if (!Number.isInteger(finalTargetCount) || finalTargetCount <= 0 || finalTargetCount > 10000) {
        throw new Error('投递目标必须为1至10000的整数')
      }
      const updatedConfig = { ...config, targetCount: finalTargetCount }
      setConfig(updatedConfig)

      const saved = await fetch(`${API_BASE}/api/boss/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...updatedConfig,
          keywords: keywordsDisplay,
          cityCode: toBracketList(selectedCities),
          industry: toBracketList(selectedIndustry),
          experience: toBracketList(selectedExperience),
          degree: toBracketList(selectedDegree),
          scale: toBracketList(selectedScale),
          stage: toBracketList(selectedStage),
          salary: toBracketList(selectedSalary),
        }),
      })
      if (!saved.ok) {
        const error = await saved.json().catch(() => ({}))
        throw new Error(error.message || '启动前保存配置失败')
      }
      setIsDelivering(true)
      const response = await fetch(`${API_BASE}/api/boss/delivery/new`, {
        method: 'POST',
      })
      const data = await response.json()

      if (data.success) {
        // 新建成功：状态由轮询更新
      } else {
        console.warn('新建投递失败：', data.message)
        setSaveResult({ success: false, title: '新建投递失败', message: data.message || '新建投递失败，请查看后端日志' })
        setShowSaveDialog(true)
        setIsDelivering(false)
      }
    } catch (error) {
      console.error('Failed to start new delivery:', error)
      setSaveResult({
        success: false,
        title: '新建投递失败',
        message: error instanceof Error ? error.message : `无法连接后端服务（${API_BASE}），请确认服务已启动`,
      })
      setShowSaveDialog(true)
      setIsDelivering(false)
    }
  }

  const handleResumeDelivery = async () => {
    try {
      setIsDelivering(true)
      const response = await fetch(`${API_BASE}/api/boss/delivery/resume`, {
        method: 'POST',
      })
      const data = await response.json()

      if (data.success) {
        // 继续成功：状态由轮询更新
      } else {
        console.warn('继续投递失败：', data.message)
        setSaveResult({ success: false, title: '继续投递失败', message: data.message || '继续投递失败，请查看后端日志' })
        setShowSaveDialog(true)
        setIsDelivering(false)
      }
    } catch (error) {
      console.error('Failed to resume delivery:', error)
      setSaveResult({
        success: false,
        title: '继续投递失败',
        message: error instanceof Error ? error.message : `无法连接后端服务（${API_BASE}），请确认服务已启动`,
      })
      setShowSaveDialog(true)
      setIsDelivering(false)
    }
  }

  const handleStartDelivery = handleStartNewDelivery

  const reconcileDelivery = async (confirmed: boolean) => {
    if (!delivery?.pendingAction?.jobId || !reconcileChecked || isDelivering) return
    setReconcileBusy(true)
    try {
      const response = await fetch(`${API_BASE}/api/boss/delivery/reconcile`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ taskId: delivery.taskId, jobId: delivery.pendingAction.jobId, version: delivery.version, confirmed }) })
      const data = await response.json()
      if (!response.ok) throw new Error(data.message || '核对失败，请刷新状态')
      setDelivery(data.delivery || data); setReconcileChecked(false)
    } catch (error) {
      setSaveResult({ success: false, title: '核对失败', message: error instanceof Error ? error.message : '请稍后重试' }); setShowSaveDialog(true)
    } finally { setReconcileBusy(false) }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/boss/stop`, {
        method: 'POST',
      })
      const data = await response.json()

      if (data.success) {
        // 请求停止不代表执行已退出，状态轮询确认后才允许再次启动。
      } else {
        console.warn('停止失败：', data.message)
        setSaveResult({ success: false, title: '停止请求未完成', message: data.message || '请等待状态更新后重试' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to stop delivery:', error)
      setSaveResult({ success: false, title: '停止请求失败', message: '无法确认任务是否已停止，请等待状态更新后重试。' })
      setShowSaveDialog(true)
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/boss/logout`, { method: 'POST' })
      const data = await response.json()
      if (data.success) {
        setIsLoggedIn(false)
        setIsDelivering(false)
        console.info('已退出登录，数据库Cookie已置空')
        setLogoutResult({ success: true, message: '已退出登录，Cookie已清空。' })
        setShowLogoutResultDialog(true)
      } else {
        console.warn('退出登录失败：', data.message)
        setLogoutResult({ success: false, message: `退出登录失败：${data.message || '服务返回异常。'}` })
        setShowLogoutResultDialog(true)
      }
    } catch (error) {
      console.error('Failed to logout:', error)
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  if (loading) {
    return <div className="flex items-center justify-center h-screen">加载中...</div>
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="Boss直聘配置"
        subtitle="配置Boss直聘平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-teal-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 请先登录Boss
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 停止投递
              </Button>
            ) : (
              <div className="flex items-center gap-2">
                <Button
                  onClick={handleResumeDelivery}
                  disabled={hasUnknown || !canResume || dirty}
                  title={
                    hasUnknown
                      ? '存在未核实发送状态，请先核对'
                      : dirty
                      ? '配置有未保存修改，请先保存'
                      : !canResume
                      ? '暂无可继续的未完成任务'
                      : '继续当前任务剩余额度（注意：如变更了任何配置项，需点击右侧新建任务）'
                  }
                  size="sm"
                  className={`rounded-full px-4 shadow-lg transition-all duration-300 ${hasUnknown || !canResume || dirty ? 'bg-gray-200 text-gray-400 cursor-not-allowed shadow-none' : 'bg-gradient-to-r from-blue-500 to-cyan-500 hover:from-blue-600 hover:to-cyan-600 text-white hover:shadow-xl hover:scale-105'}`}
                >
                  <BiPlay className="mr-1" /> 继续任务
                </Button>
                <Button
                  onClick={() => {
                    setNewTaskTargetCount(config.targetCount ?? 50)
                    setShowNewTaskDialog(true)
                  }}
                  disabled={hasUnknown || dirty}
                  title={
                    hasUnknown
                      ? '存在未核实发送状态，请先核对'
                      : dirty
                      ? '请先保存当前配置再新建任务'
                      : '创建独立新任务，保留旧历史'
                  }
                  size="sm"
                  className={`rounded-full px-4 shadow-lg transition-all duration-300 ${hasUnknown || dirty ? 'bg-gray-200 text-gray-400 cursor-not-allowed shadow-none' : 'bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white hover:shadow-xl hover:scale-105'}`}
                >
                  <BiPlus className="mr-1" /> 新建任务
                </Button>
              </div>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiLogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={() => handleSave(false)} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiSave className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      <Tabs defaultValue="config" className="w-full">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="config">平台配置</TabsTrigger>
          <TabsTrigger value="analytics">投递分析</TabsTrigger>
        </TabsList>

        <TabsContent value="config" className="space-y-6 mt-6" onChange={() => setDirty(true)}>
          <Card>
            <CardHeader><CardTitle>本次投递目标</CardTitle><CardDescription>按首次招呼确认发送成功的岗位计数；多城市平均分配，不足时补投。</CardDescription></CardHeader>
            <CardContent className="space-y-4">
              <div className="flex flex-wrap items-center gap-4">
                <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={config.aiOnly === 1} onChange={e => setConfig({ ...config, aiOnly: e.target.checked ? 1 : 0 })} />仅保留 AI 相关岗位</label>
                <span className="text-xs text-muted-foreground">目标份数在「新建任务」弹窗内设置，此处只读展示进度。</span>
              </div>
              <p className="text-xs text-muted-foreground">“经验不限”不代表校招。毕业年份在页面前部「搜索与匹配配置」设置；届别明确不符时跳过，信息不明时进入待确认。</p>
              {delivery && <div aria-live="polite" className="space-y-3 rounded-xl border bg-muted/20 p-4">
                <div className="flex justify-between items-center gap-3">
                  <span className="font-medium">
                    首次成功 {delivery.successCount ?? 0} / {delivery.targetCount ?? config.targetCount}
                    {delivery.taskId && <span className="ml-2 text-xs font-mono text-muted-foreground font-normal">任务ID: {delivery.taskId.slice(0, 8)}...</span>}
                  </span>
                  <span className="text-sm text-muted-foreground">
                    {({ running: '投递中', completed: '已完成', exhausted: '候选岗位不足', cancelled: '已停止', failed: '任务失败', awaiting_reconciliation: '等待人工核对', paused: '已暂停', reconciled: '已核实待继续' } as Record<string, string>)[delivery.state || ''] || '等待任务'}
                    {delivery.currentCity ? ` · ${options.city.find(c => c.code === delivery.currentCity)?.name || delivery.currentCity}` : ''}
                  </span>
                </div>
                <progress aria-label="首次成功投递进度" className="h-2 w-full accent-primary" max={delivery.targetCount || 1} value={delivery.successCount || 0} />
                <div className="flex flex-wrap gap-4 text-sm text-muted-foreground">
                  <span>失败 {delivery.failedCount ?? 0}</span>
                  <span>待核实 {delivery.unknownCount ?? 0}</span>
                  <span>跳过 {delivery.skippedCount ?? 0}</span>
                  <span>重复沟通 {delivery.repeatCount ?? 0}</span>
                  <span>剩余额度 {Math.max(0, (delivery.targetCount ?? 0) - (delivery.successCount ?? 0))}</span>
                </div>
                {delivery.cityQuotas && <p className="text-xs text-muted-foreground">城市配额：{Object.entries(delivery.cityQuotas).map(([city, count]) => `${options.city.find(c => c.code === city)?.name || city} ${typeof count === 'object' ? JSON.stringify(count) : count}`).join(' · ')}</p>}
                {delivery.pendingAction?.jobId && <div className="space-y-3 rounded-lg border border-amber-400/50 bg-amber-100/10 p-3">
                  <p className="text-sm font-medium">有一条发送结果待核实，任务暂停继续发送。</p>
                  <p className="whitespace-pre-wrap text-sm">待核实消息：{delivery.pendingAction.text}</p>
                  <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={reconcileChecked} onChange={e => setReconcileChecked(e.target.checked)} />我已在 BOSS 对应会话人工核对消息发送状态</label>
                  <div className="flex flex-wrap gap-2"><Button size="sm" disabled={isDelivering || reconcileBusy || !reconcileChecked} onClick={() => void reconcileDelivery(true)}>已核实发送成功</Button><Button size="sm" variant="outline" disabled={isDelivering || reconcileBusy || !reconcileChecked} onClick={() => void reconcileDelivery(false)}>已核实未发送</Button></div>
                  <p className="text-xs text-muted-foreground">核对后重新开始将继续原任务剩余额度，不会自动重发这条消息。</p>
                </div>}
              </div>}
            </CardContent>
          </Card>
          {/* 平台说明 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                Boss直聘平台说明
              </CardTitle>
              <CardDescription>登录与投递操作提示</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <p className="text-sm text-muted-foreground">请在浏览器标签页中登录 Boss 直聘平台，登录成功后系统会自动检测登录状态。</p>
                <p className="text-sm text-muted-foreground">登录成功后，点击“开始投递”按钮启动自动投递任务。</p>
                <p className="text-sm text-muted-foreground">点击“保存配置”按钮可手动保存当前登录相关信息到数据库。</p>
              </div>
            </CardContent>
          </Card>

          {/* 投递行为 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiImage className="text-primary" />
                投递行为
              </CardTitle>
              <CardDescription>设置重复沟通与图片简历发送行为，保存后在下次启动投递时生效</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <div className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="space-y-1">
                    <Label htmlFor="allowRepeatChat" className="text-base font-medium">向已沟通 Boss 再次发送消息</Label>
                    <p className="text-sm text-muted-foreground">
                      开启后，遇到“继续沟通”会随机发送下方一行消息；关闭时跳过该岗位。首次沟通先发通用招呼，启用 AI 时再补简短匹配说明。
                    </p>
                  </div>
                  <button
                    id="allowRepeatChat"
                    type="button"
                    role="switch"
                    aria-checked={config.allowRepeatChat === 1}
                    aria-label="向已沟通 Boss 再次发送消息"
                    onClick={() => setConfig(prev => ({ ...prev, allowRepeatChat: prev.allowRepeatChat === 1 ? 0 : 1 }))}
                    className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 ${config.allowRepeatChat === 1 ? 'bg-primary' : 'bg-muted-foreground/40'}`}
                  >
                    <span aria-hidden="true" className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${config.allowRepeatChat === 1 ? 'translate-x-5' : 'translate-x-0.5'}`} />
                  </button>
                </div>
                <div className="space-y-2 rounded-lg border bg-muted/20 p-4">
                  <Label htmlFor="repeatChatMessages">重复沟通消息（每行一条）</Label>
                  <textarea
                    id="repeatChatMessages"
                    rows={7}
                    value={config.repeatChatMessages ?? ''}
                    onChange={event => setConfig(prev => ({ ...prev, repeatChatMessages: event.target.value }))}
                    placeholder="每行填写一条想发送的消息"
                    className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-7 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  />
                  <p className="text-sm text-muted-foreground">可自由编辑、添加或删除。每次从非空行中随机选一条，可能重复；全部清空时跳过重复沟通。点击“保存配置”后，下次启动投递生效。</p>
                </div>
                <div className="grid gap-4 rounded-lg border bg-muted/20 p-4 md:grid-cols-[minmax(0,1fr)_220px]">
                  <div className="space-y-3">
                    <div>
                      <Label className="text-base font-medium">图片简历</Label>
                      <p className="mt-1 text-sm text-muted-foreground">
                        支持 PNG、JPG、JPEG、GIF，单张不超过 10MB。重新上传会替换当前图片。
                      </p>
                    </div>
                    <input
                      ref={resumeImageInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/gif,.png,.jpg,.jpeg,.gif"
                      onChange={handleResumeImageChange}
                      className="hidden"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      disabled={resumeImageUploading}
                      onClick={() => resumeImageInputRef.current?.click()}
                    >
                      <BiUpload className="mr-2" />
                      {resumeImageUploading ? '正在上传…' : resumeImage.exists ? '重新上传' : '选择并上传图片'}
                    </Button>
                    {resumeImage.exists && (
                      <p className="text-xs text-muted-foreground">
                        当前文件：{resumeImage.fileName || 'resume'}{resumeImage.size != null ? `（${formatFileSize(resumeImage.size)}）` : ''}
                      </p>
                    )}
                    {resumeImageMessage && (
                      <p className={`text-sm ${resumeImageMessage.success ? 'text-green-600' : 'text-red-600'}`}>
                        {resumeImageMessage.text}
                      </p>
                    )}
                  </div>
                  <div className="flex min-h-36 items-center justify-center overflow-hidden rounded-md border bg-background">
                    {resumePreviewUrl ? (
                      <button
                        type="button"
                        onClick={() => setShowResumePreview(true)}
                        className="group relative flex h-full w-full cursor-zoom-in items-center justify-center focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-inset"
                        aria-label="放大查看图片简历"
                        title="点击查看大图"
                      >
                        <Image
                          src={resumePreviewUrl}
                          alt="图片简历预览"
                          width={220}
                          height={224}
                          unoptimized
                          className="max-h-56 w-full object-contain"
                        />
                        <span className="absolute inset-x-0 bottom-0 flex items-center justify-center gap-1 bg-black/60 py-1.5 text-xs text-white opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
                          <BiZoomIn className="text-base" /> 点击查看大图
                        </span>
                      </button>
                    ) : (
                      <div className="px-4 text-center text-sm text-muted-foreground">尚未上传图片简历</div>
                    )}
                  </div>
                </div>
                <div className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="space-y-1">
                    <Label htmlFor="sendImgResume" className="text-base font-medium">
                      自动发送图片简历
                    </Label>
                    <p className="text-sm text-muted-foreground">
                      开启后，系统会在打招呼语发送成功后发送上方图片；上传图片不会自动开启。
                    </p>
                    {!resumeImage.exists && (
                      <p className="text-xs text-amber-600">请先上传图片简历，再开启此选项。</p>
                    )}
                  </div>
                  <button
                    id="sendImgResume"
                    type="button"
                    role="switch"
                    disabled={!resumeImage.exists}
                    aria-checked={config.sendImgResume === 1}
                    aria-label="自动发送图片简历"
                    onClick={() => setConfig({ ...config, sendImgResume: config.sendImgResume === 1 ? 0 : 1 })}
                    className={`relative inline-flex h-7 w-12 shrink-0 items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 ${
                      config.sendImgResume === 1 ? 'bg-primary' : 'bg-muted-foreground/40'
                    } ${resumeImage.exists ? 'cursor-pointer' : 'cursor-not-allowed opacity-50'}`}
                  >
                    <span
                      aria-hidden="true"
                      className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
                        config.sendImgResume === 1 ? 'translate-x-5' : 'translate-x-0.5'
                      }`}
                    />
                  </button>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* 搜索配置 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiSearch className="text-primary" />
                搜索配置
              </CardTitle>
              <CardDescription>设置职位搜索关键词和目标城市</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                <div className="space-y-2">
                  <Label htmlFor="keywords">搜索关键词</Label>
                  <Input
                    id="keywords"
                    value={keywordsDisplay}
                    onChange={(e) => setKeywordsDisplay(e.target.value)}
                    placeholder="例如：Java开发工程师"
                  />
                  <p className="text-xs text-muted-foreground">职位搜索的关键词</p>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="city">工作城市</Label>
                  <MultiSelect options={options.city} selected={selectedCities} onChange={values => {
                    const latest = values[values.length - 1]
                    setSelectedCities(latest === '0' || latest === '100010000' ? [latest] : values.filter(v => v !== '0' && v !== '100010000'))
                  }} placeholder="选择一个或多个城市" />
                  <p className="text-xs text-muted-foreground">按选择顺序平均分配；全国／不限与具体城市互斥。</p>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="graduationYear">毕业年份</Label>
                  <Input
                    id="graduationYear"
                    type="number"
                    min={2000}
                    max={2100}
                    step={1}
                    placeholder="如 2027；留空表示不限定"
                    value={graduationYear}
                    onChange={(e) => { setGraduationYear(e.target.value); setDirty(true) }}
                  />
                  <p className="text-xs text-muted-foreground">仅用于校招届别判定，不会由工作经验推导，也与教育背景时间无关。</p>
                </div>

              <div className="space-y-2">
                <Label htmlFor="jobType">职位类型</Label>
                <Select
                  id="jobType"
                  value={config.jobType || ''}
                  onChange={(e) => setConfig({ ...config, jobType: e.target.value })}
                >
                  {options.jobType.map((type) => (
                    <option key={type.id} value={type.code}>
                      {type.name}
                    </option>
                  ))}
                </Select>
                <p className="text-xs text-muted-foreground">选择职位类型</p>
              </div>

              <div className="space-y-2 md:col-span-3">
                <Label>公司行业</Label>
                <IndustryPicker options={options.industry} groups={industryGroups} selected={selectedIndustry} onChange={setSelectedIndustry} />
              </div>

              {/* HR活跃过滤开关 */}
              <div className="space-y-2">
                <Label htmlFor="filterDeadHr">HR活跃过滤</Label>
                <Select
                  id="filterDeadHr"
                  value={String(config.filterDeadHr ?? 0)}
                  onChange={(e) => setConfig({ ...config, filterDeadHr: Number(e.target.value) })}
                >
                  <option value="0">关闭</option>
                  <option value="1">开启</option>
                </Select>
                <p className="text-xs text-muted-foreground">开启后将过滤活跃状态包含“年”的HR，但仍保存数据。</p>
              </div>
              </div>
            </CardContent>
        </Card>

        <ChatWorkspace />

        {/* 薪资和经验 */}
        <Card className="animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiMoney className="text-primary" />
              薪资与经验要求
            </CardTitle>
            <CardDescription>设置薪资待遇和工作经验要求</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <Label>薪资待遇</Label>
                <MultiSelect
                  options={options.salary}
                  selected={selectedSalary}
                  onChange={setSelectedSalary}
                  placeholder="选择薪资待遇"
                />
                <p className="text-xs text-muted-foreground">可多选</p>
              </div>
              <div className="space-y-2">
                <Label>工作经验</Label>
                <MultiSelect
                  options={options.experience}
                  selected={selectedExperience}
                  onChange={setSelectedExperience}
                  placeholder="选择工作经验"
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 公司要求 */}
        <Card className="animate-in fade-in slide-in-from-bottom-7 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiBuilding className="text-primary" />
              公司要求
            </CardTitle>
            <CardDescription>设置目标公司的规模和融资阶段</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              <div className="space-y-2">
                <Label>学历要求</Label>
                <MultiSelect
                  options={options.degree}
                  selected={selectedDegree}
                  onChange={setSelectedDegree}
                  placeholder="选择学历要求"
                />
              </div>

              <div className="space-y-2">
                <Label>公司规模</Label>
                <MultiSelect
                  options={options.scale}
                  selected={selectedScale}
                  onChange={setSelectedScale}
                  placeholder="选择公司规模"
                />
              </div>

              <div className="space-y-2">
                <Label>融资阶段</Label>
                <MultiSelect
                  options={options.stage}
                  selected={selectedStage}
                  onChange={setSelectedStage}
                  placeholder="选择融资阶段"
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 匹配问候与说明 */}
        <Card>
          <CardHeader>
            <CardTitle>匹配问候与说明</CardTitle>
            <CardDescription>首次招呼语；开启匹配说明后，首次招呼成功会追加 ≤60 字匹配理由</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="sayHi">首次问候语</Label>
              <textarea
                id="sayHi"
                rows={3}
                className="w-full rounded-lg border bg-background px-3 py-2 text-sm"
                value={config.sayHi || ''}
                placeholder="默认通用问候，例如：您好，看到岗位与我的经历比较匹配，想和您进一步沟通。"
                onChange={e => { setConfig({ ...config, sayHi: e.target.value }); setDirty(true) }}
              />
              <p className="text-xs text-muted-foreground">匹配说明由 AI 依据已批准资料与 JD 生成，失败不重试。</p>
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={config.enableAi === 1} onChange={e => { setConfig({ ...config, enableAi: e.target.checked ? 1 : 0 }); setDirty(true) }} />
              开启匹配问候（AI 生成 ≤60 字说明）
            </label>
          </CardContent>
        </Card>

        {/* 简历资料：图片（语义不变） + PDF（本地资产） */}
        <Card>
          <CardHeader>
            <CardTitle>简历资料</CardTitle>
            <CardDescription>图片简历沿用现有语义；PDF 为本地资产，真实发送待平台证据</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="space-y-2 rounded-lg border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-medium">图片简历</span>
                <span className="text-xs text-muted-foreground">{resumeImage.exists ? '已上传' : '未上传'}</span>
              </div>
              <p className="text-xs text-muted-foreground">沿用原有图片上传与批准逻辑；仅在已批准且开关开启时用于首次问候附图。</p>
              <Button variant="outline" size="sm" onClick={() => resumeImageInputRef.current?.click()}>上传/替换图片</Button>
            </div>

            <div className="space-y-3 rounded-lg border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-medium">PDF 简历</span>
                <span className="text-xs text-muted-foreground">{resumeDocument.exists ? '已上传' : '未上传'}</span>
              </div>
              {resumeDocument.exists ? (
                <div className="space-y-1 text-xs text-muted-foreground">
                  <div>文件名：{resumeDocument.fileName}</div>
                  <div>大小：{Math.round((resumeDocument.size || 0) / 1024)} KB</div>
                  <div>更新时间：{resumeDocument.updatedAt || '-'}</div>
                  <div>资产指纹：{resumeDocument.assetId}</div>
                </div>
              ) : (
                <p className="text-xs text-muted-foreground">未上传 PDF 简历。</p>
              )}
              <p className="text-xs text-muted-foreground">
                PDF 真实发送未实现，等待平台附件入口证据；此处仅完成本地资产、批准与文本资料补充，不会自动发送。
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => resumeDocInputRef.current?.click()}>
                  {resumeDocument.exists ? '替换 PDF' : '上传 PDF'}
                </Button>
                <Button variant="outline" size="sm" disabled={!resumeDocument.exists}
                  onClick={() => { if (resumeDocument.previewUrl) window.open(`${API_BASE}${resumeDocument.previewUrl}`, '_blank') }}>
                  预览
                </Button>
                <Button size="sm" disabled={!resumeDocument.exists || chatProfile?.approvedPdfAssetId === resumeDocument.assetId}
                  onClick={() => void handleApprovePdf()}>
                  {chatProfile?.approvedPdfAssetId === resumeDocument.assetId ? '已批准' : '批准当前 PDF'}
                </Button>
                <Button variant="ghost" size="sm" disabled={!chatProfile?.approvedPdfAssetId}
                  onClick={() => void handleRevokePdfApproval()}>
                  撤销批准
                </Button>
                <input ref={resumeDocInputRef} type="file" accept="application/pdf" className="hidden" onChange={(e) => void handleResumeDocumentChange(e)} />
              </div>
              {resumeDocMessage && (
                <p className={`text-xs ${resumeDocMessage.success ? 'text-emerald-600' : 'text-red-600'}`} role="status">{resumeDocMessage.text}</p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* 黑名单管理 */}
        <Card className="animate-in fade-in slide-in-from-bottom-8 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiSearch className="text-primary" />
              黑名单管理 ({blacklist.length} 条)
            </CardTitle>
            <CardDescription>添加或删除不想投递的公司、职位或HR</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-6">
              {/* 添加黑名单 */}
              <div className="flex gap-2">
                <Select
                  value={blacklistType}
                  onChange={(e) => setBlacklistType(e.target.value)}
                  className="w-32"
                >
                  <option value="company">公司</option>
                  <option value="job">岗位</option>
                  <option value="recruiter">HR</option>
                </Select>
                <Input
                  value={newBlacklistKeyword}
                  onChange={(e) => setNewBlacklistKeyword(e.target.value)}
                  placeholder={`输入${blacklistType === 'company' ? '公司名称' : blacklistType === 'job' ? '岗位关键词' : 'HR职位'}关键词`}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      handleAddBlacklist()
                    }
                  }}
                />
                <Button onClick={handleAddBlacklist} className="whitespace-nowrap">
                  <BiPlus />
                  添加
                </Button>
              </div>

              {/* 黑名单列表 - 按类型分组显示 */}
              <div className="space-y-6">
                {/* 公司黑名单 */}
                <div>
                  <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <BiBuilding className="text-orange-500" />
                    <span>公司黑名单 ({blacklist.filter(item => item.type === 'company').length})</span>
                  </h3>
                  <div className="space-y-2">
                    {blacklist.filter(item => item.type === 'company').length === 0 ? (
                      <div className="text-center py-4 text-muted-foreground bg-muted/30 rounded-lg">
                        <p className="text-xs">暂无公司黑名单</p>
                      </div>
                    ) : (
                      blacklist.filter(item => item.type === 'company').map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between p-3 bg-orange-50 dark:bg-orange-950/20 rounded-lg border border-orange-200 dark:border-orange-800"
                        >
                          <span className="text-sm">{item.value}</span>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleDeleteBlacklist(item.id)}
                            className="text-red-500 hover:text-red-700 hover:bg-red-50"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                {/* 岗位黑名单 */}
                <div>
                  <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <BiBriefcase className="text-blue-500" />
                    <span>岗位黑名单 ({blacklist.filter(item => item.type === 'job').length})</span>
                  </h3>
                  <div className="space-y-2">
                    {blacklist.filter(item => item.type === 'job').length === 0 ? (
                      <div className="text-center py-4 text-muted-foreground bg-muted/30 rounded-lg">
                        <p className="text-xs">暂无岗位黑名单</p>
                      </div>
                    ) : (
                      blacklist.filter(item => item.type === 'job').map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between p-3 bg-blue-50 dark:bg-blue-950/20 rounded-lg border border-blue-200 dark:border-blue-800"
                        >
                          <span className="text-sm">{item.value}</span>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleDeleteBlacklist(item.id)}
                            className="text-red-500 hover:text-red-700 hover:bg-red-50"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                {/* HR黑名单 */}
                <div>
                  <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <BiSearch className="text-green-500" />
                    <span>HR黑名单 ({blacklist.filter(item => item.type === 'recruiter').length})</span>
                  </h3>
                  <div className="space-y-2">
                    {blacklist.filter(item => item.type === 'recruiter').length === 0 ? (
                      <div className="text-center py-4 text-muted-foreground bg-muted/30 rounded-lg">
                        <p className="text-xs">暂无HR黑名单</p>
                      </div>
                    ) : (
                      blacklist.filter(item => item.type === 'recruiter').map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between p-3 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800"
                        >
                          <span className="text-sm">{item.value}</span>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleDeleteBlacklist(item.id)}
                            className="text-red-500 hover:text-red-700 hover:bg-red-50"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        
        </TabsContent>

        <TabsContent value="analytics" className="space-y-6 mt-6">
          <AnalysisContent />
        </TabsContent>
      </Tabs>

      {/* 图片简历大图预览 */}
      {showResumePreview && resumePreviewUrl && createPortal(
        <div
          className="fixed inset-0 z-[100] overflow-auto bg-black/85 p-4 backdrop-blur-sm sm:p-8"
          role="dialog"
          aria-modal="true"
          aria-label="图片简历大图预览"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setShowResumePreview(false)
          }}
        >
          <button
            type="button"
            onClick={() => setShowResumePreview(false)}
            className="fixed right-4 top-4 z-[101] inline-flex h-12 w-12 items-center justify-center rounded-full border-2 border-white bg-white text-3xl text-gray-900 shadow-[0_4px_20px_rgba(0,0,0,0.65)] transition duration-200 hover:border-red-500 hover:bg-red-500 hover:text-white hover:scale-105 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-white/70 sm:right-6 sm:top-6"
            aria-label="关闭大图预览"
            title="关闭（Esc）"
          >
            <BiX />
          </button>
          <div className="flex min-h-full items-start justify-center py-10">
            <Image
              src={resumePreviewUrl}
              alt="图片简历大图"
              width={1400}
              height={2000}
              unoptimized
              priority
              className="h-auto w-[94vw] max-w-[1200px] rounded-md bg-white shadow-2xl"
              onMouseDown={(event) => event.stopPropagation()}
            />
          </div>
        </div>,
        document.body
      )}

      {/* 统计卡片已移除 */}
      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className="text-red-500" />
                  确认退出登录
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
                <div className="flex justify-end gap-2">
                  <Button
                    variant="ghost"
                    onClick={() => setShowLogoutDialog(false)}
                    className="rounded-full px-4"
                  >
                    取消
                  </Button>
                  <Button
                    onClick={async () => {
                      await triggerLogout()
                      setShowLogoutDialog(false)
                    }}
                    className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4"
                  >
                    确认退出
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                  {logoutResult.success ? '退出登录成功' : '退出登录失败'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowLogoutResultDialog(false)}
                    className={`rounded-full px-4 ${logoutResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 保存结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                  {saveResult.title ?? (saveResult.success ? '保存成功' : '保存失败')}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowSaveDialog(false)}
                    className={`rounded-full px-4 ${saveResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 新建任务弹窗 */}
      {showNewTaskDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95 p-6 space-y-4">
            <div className="space-y-1">
              <h3 className="text-lg font-semibold">新建投递任务</h3>
              <p className="text-xs text-muted-foreground">创建独立新任务，按所设目标份数开始投递</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="dialogTargetCount">目标份数</Label>
              <Input
                id="dialogTargetCount"
                type="number"
                min={1}
                max={10000}
                step={1}
                value={newTaskTargetCount}
                onChange={(e) => setNewTaskTargetCount(Number(e.target.value))}
                placeholder="1-10000 整数"
              />
              <p className="text-xs text-muted-foreground">请输入 1 至 10000 的整数目标</p>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <Button
                variant="ghost"
                onClick={() => setShowNewTaskDialog(false)}
                className="rounded-full px-4"
              >
                取消
              </Button>
              <Button
                onClick={async () => {
                  setShowNewTaskDialog(false)
                  await handleStartNewDelivery(newTaskTargetCount)
                }}
                className="rounded-full bg-gradient-to-r from-teal-500 to-green-600 hover:from-teal-600 hover:to-green-700 text-white px-4"
              >
                创建并开始
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// 多选下拉组件（简单版），按代码选择并显示名称
function MultiSelect({
  options,
  selected,
  onChange,
  placeholder,
  onClose,
}: {
  options: BossOption[]
  selected: string[]
  onChange: (v: string[]) => void
  placeholder?: string
  onClose?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [mounted, setMounted] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  const [dropdownPosition, setDropdownPosition] = useState({ top: 0, left: 0, width: 0 })

  // 确保组件已挂载（解决 SSR 问题）
  useEffect(() => {
    setMounted(true)
  }, [])

  // 计算下拉框位置
  const updatePosition = useCallback(() => {
    if (buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect()
      setDropdownPosition({
        top: rect.bottom + 8,
        left: rect.left,
        width: rect.width,
      })
    }
  }, [])

  // 打开时计算位置
  useEffect(() => {
    if (open) {
      updatePosition()
      // 监听滚动和窗口大小变化，更新位置
      const handleUpdate = () => updatePosition()
      window.addEventListener('scroll', handleUpdate, true)
      window.addEventListener('resize', handleUpdate)
      return () => {
        window.removeEventListener('scroll', handleUpdate, true)
        window.removeEventListener('resize', handleUpdate)
      }
    }
  }, [open, updatePosition])

  // 点击组件外部或焦点移出时关闭下拉
  useEffect(() => {
    const handleOutsideClick = (e: MouseEvent) => {
      if (!open) return
      const target = e.target as Node
      // 检查点击是否在按钮或下拉框内
      const clickedButton = wrapperRef.current?.contains(target)
      const clickedDropdown = dropdownRef.current?.contains(target)

      console.log('[MultiSelect] 外部点击检测', {
        clickedButton,
        clickedDropdown,
        targetElement: (target as HTMLElement)?.tagName,
        targetClass: (target as HTMLElement)?.className
      })

      if (!clickedButton && !clickedDropdown) {
        console.log('[MultiSelect] 检测到外部点击，关闭下拉框')
        setOpen(false)
        onClose?.()
      } else {
        console.log('[MultiSelect] 点击在组件内部，保持打开')
      }
    }
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        console.log('[MultiSelect] ESC 键关闭')
        setOpen(false)
        onClose?.()
      }
    }

    if (open) {
      console.log('[MultiSelect] 下拉框打开，注册监听器')
      // 使用 setTimeout 确保 DOM 已更新
      setTimeout(() => {
        document.addEventListener('mousedown', handleOutsideClick)
        document.addEventListener('keydown', handleEscape)
      }, 0)
    }

    return () => {
      if (open) {
        console.log('[MultiSelect] 移除监听器')
      }
      document.removeEventListener('mousedown', handleOutsideClick)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [open, onClose])

  const toggle = (code: string) => {
    console.log('[MultiSelect] toggle 被调用', { code, currentSelected: selected })
    if (selected.includes(code)) {
      const newSelected = selected.filter((c) => c !== code)
      console.log('[MultiSelect] 取消选择，新值:', newSelected)
      onChange(newSelected)
    } else {
      const newSelected = [...selected, code]
      console.log('[MultiSelect] 添加选择，新值:', newSelected)
      onChange(newSelected)
    }
  }

  const selectedNames = options
    .filter((o) => selected.includes(o.code))
    .map((o) => o.name)

  return (
    <div className="relative" ref={wrapperRef}>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => { const next = !open; setOpen(next); if (!next) onClose?.() }}
        className="flex h-10 w-full items-center justify-between rounded-full border border-white/20 bg-white/10 px-4 py-2 text-sm shadow-[inset_0_1px_0_rgba(255,255,255,.25)] transition-all duration-200 hover:bg-white/15 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:ring-offset-0"
      >
        <span className="truncate text-sm">
          {selectedNames.length > 0 ? selectedNames.join('，') : (placeholder || '请选择')}
        </span>
        <span className={`ml-2 text-xs text-muted-foreground transition-transform duration-200 ${open ? 'rotate-180' : ''}`}>▼</span>
      </button>
      {open && mounted && createPortal(
        <div
          ref={dropdownRef}
          className="dropdown-panel p-2"
          style={{
            top: `${dropdownPosition.top}px`,
            left: `${dropdownPosition.left}px`,
            width: `${dropdownPosition.width}px`,
          }}
        >
          <div className="flex flex-col gap-2">
            {options.map((opt) => {
              const checked = selected.includes(opt.code)
              return (
                <div
                  key={opt.id}
                  className={`group inline-flex items-center justify-between gap-3 rounded-full px-3 py-2 cursor-pointer transition-all border ${checked ? 'border-teal-300/60 bg-gradient-to-r from-teal-500/12 to-cyan-500/12 text-teal-900 dark:text-teal-200 shadow' : 'border-white/20 bg-white/8 text-foreground hover:bg-white/12'}`}
                  onClick={(e) => {
                    console.log('[MultiSelect] div 被点击', {
                      optionCode: opt.code,
                      optionName: opt.name,
                      currentChecked: checked
                    })
                    toggle(opt.code)
                  }}
                >
                  <span className="flex items-center gap-3">
                    <span className={`inline-flex h-4 w-4 items-center justify-center rounded-md border border-white/30 bg-white/10 shadow-inner transition-all ${checked ? 'bg-teal-400/60 border-teal-300/80' : ''}`}></span>
                    <span className="text-sm truncate">{opt.name}</span>
                  </span>
                </div>
              )
            })}
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
