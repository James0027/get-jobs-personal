'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import ProfileSections, { ResumeData } from './ProfileSections'
import ChatRecords, { ChatRecordItem } from './ChatRecords'

interface Profile {
  version?: string
  graduationYear?: number | null
  acceptedCities: string[]
  facts: { id: string; text: string }[]
  approvedResumeAssetId?: string
  autoResume: boolean
  autoLocation: boolean
  enabled: boolean
  resume?: ResumeData
  approvedPdfAssetId?: string
  resumeText?: string
  resumeTextHash?: string
  approvedResumeTextHash?: string
  derivedFactIds?: string[]
  autoReply?: boolean
  followUp?: {
    enabled: boolean
    cooldownHours: number
    maxPerConversation: number
  }
}

interface FollowUpCandidate {
  conversationId: string
  lastInboundAt: number | null
  lastOutboundAt: number | null
  followUpCount: number
  eligible: boolean
  reasonCode: string
}

const FOLLOW_UP_REASONS: Record<string, string> = {
  COOLDOWN: '冷却未到',
  LIMIT_REACHED: '已达跟进上限',
  UNVERIFIED_SEND: '有未核实发送',
  NEW_INBOUND_UNHANDLED: '有新消息未处理',
  REFUSED: '对方已拒绝',
  NO_OUTBOUND: '无发送记录',
  NO_TRUSTED_TIME: '缺可信时间',
}

const initialResume: ResumeData = {
  work: [],
  project: [],
  education: [],
  skills: [],
}

const initial: Profile = {
  acceptedCities: [],
  facts: [],
  autoResume: false,
  autoLocation: false,
  enabled: false,
  resume: initialResume,
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

async function readChat(path: string, init?: RequestInit) {
  const response = await fetch(`${API_BASE}/api/boss/chat${path}`, init)
  const data = await response.json().catch(() => ({}))
  if (!response.ok || data.success === false) {
    const errorMsg = data.message || `请求失败 (${response.status})`
    const error = new Error(errorMsg) as Error & { status?: number }
    error.status = response.status
    throw error
  }
  return data
}

export default function ChatWorkspace() {
  const [profile, setProfile] = useState<Profile>(initial)
  const [legacyFactsText, setLegacyFactsText] = useState('')
  const [records, setRecords] = useState<ChatRecordItem[]>([])
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [isSectionsValid, setIsSectionsValid] = useState(true)
  const [candidates, setCandidates] = useState<FollowUpCandidate[] | null>(null)
  const [loadingCandidates, setLoadingCandidates] = useState(false)
  const [sendingFollowUpId, setSendingFollowUpId] = useState<string | null>(null)

  const refreshRecords = useCallback(async () => {
    try {
      const data = await readChat('/records?states=blocked,unknown,failed&limit=50')
      setRecords(data.records || [])
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '加载聊天记录失败')
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    readChat('/profile')
      .then((data) => {
        if (cancelled) return
        const val: Profile = { ...initial, ...(data.profile || data) }
        val.resume = {
          work: val.resume?.work || [],
          project: val.resume?.project || [],
          education: val.resume?.education || [],
          skills: val.resume?.skills || [],
        }
        val.followUp = {
          enabled: val.followUp?.enabled ?? false,
          cooldownHours: val.followUp?.cooldownHours ?? 168,
          maxPerConversation: val.followUp?.maxPerConversation ?? 1,
        }
        setProfile(val)

        // 提取历史事实：排除由服务端派生 ID 标记的事实
        const derivedIds = new Set(val.derivedFactIds || [])
        const legacy = (val.facts || [])
          .filter((f) => !derivedIds.has(f.id))
          .map((f) => f.text)
          .join('\n')
        setLegacyFactsText(legacy)
        setLoaded(true)
      })
      .catch((error) => {
        if (!cancelled) setNotice(error instanceof Error ? error.message : '加载个人资料失败')
      })

    void refreshRecords()

    return () => {
      cancelled = true
    }
  }, [refreshRecords])

  const save = async () => {
    if (!isSectionsValid) {
      setNotice('资料中有必填项未填写完整，请核对后再保存。')
      return
    }
    setBusy(true)
    try {
      // 组装历史事实：保留用户在 legacyFactsText 中编辑的内容
      const derivedIds = new Set(profile.derivedFactIds || [])
      // 服务端派生的保留旧项，服务端在 PUT /profile 时会自己基于 derivedFactIds 重新处理
      const existingDerivedFacts = (profile.facts || []).filter((f) => derivedIds.has(f.id))

      const legacyLines = [
        ...new Set(
          legacyFactsText
            .split('\n')
            .map((t) => t.trim())
            .filter(Boolean)
        ),
      ]
      const newLegacyFacts = legacyLines.map((text) => {
        const found = (profile.facts || []).find((f) => !derivedIds.has(f.id) && f.text === text)
        return {
          id: found?.id || `fact-${crypto.randomUUID()}`,
          text,
        }
      })

      const payload: Profile = {
        ...profile,
        facts: [...existingDerivedFacts, ...newLegacyFacts],
      }

      const result = await readChat('/profile', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })

      const updated = result.profile || result
      const nextProfile: Profile = { ...initial, ...updated }
      nextProfile.resume = {
        work: nextProfile.resume?.work || [],
        project: nextProfile.resume?.project || [],
        education: nextProfile.resume?.education || [],
        skills: nextProfile.resume?.skills || [],
      }
      nextProfile.followUp = {
        enabled: nextProfile.followUp?.enabled ?? false,
        cooldownHours: nextProfile.followUp?.cooldownHours ?? 168,
        maxPerConversation: nextProfile.followUp?.maxPerConversation ?? 1,
      }
      setProfile(nextProfile)

      // 更新 legacyFactsText
      const nextDerivedIds = new Set(nextProfile.derivedFactIds || [])
      const nextLegacy = (nextProfile.facts || [])
        .filter((f) => !nextDerivedIds.has(f.id))
        .map((f) => f.text)
        .join('\n')
      setLegacyFactsText(nextLegacy)

      setNotice('资料与授权已保存。')
      await refreshRecords()
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }

  const approveResumeText = async () => {
    setBusy(true)
    try {
      const result = await readChat('/profile/resume-text/approve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          expectedProfileVersion: profile.version,
          expectedTextHash: profile.resumeTextHash,
        }),
      })
      const updated = result.profile || result
      setProfile((prev) => ({
        ...prev,
        ...updated,
        approvedResumeTextHash: updated.approvedResumeTextHash,
        version: updated.version,
      }))
      setNotice('简历文本已成功批准！')
    } catch (error: any) {
      if (error?.status === 409 || error?.message?.includes('409')) {
        setNotice('资料已变化，请重新保存后再批准。')
      } else {
        setNotice(error instanceof Error ? error.message : '批准失败')
      }
    } finally {
      setBusy(false)
    }
  }

  const handleReconcile = async (id: string, confirmed: boolean) => {
    setBusy(true)
    try {
      await readChat(`/records/${encodeURIComponent(id)}/reconcile`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmed }),
      })
      setNotice(`已更新核对状态为：${confirmed ? '已发送' : '未发送'}`)
      await refreshRecords()
    } catch (error: any) {
      if (error?.status === 409 || error?.message?.includes('409')) {
        setNotice('记录已变化，请刷新。')
      } else {
        setNotice(error instanceof Error ? error.message : '核对状态更新失败')
      }
    } finally {
      setBusy(false)
    }
  }

  const handleCheckCandidates = async () => {
    setLoadingCandidates(true)
    try {
      const data = await readChat('/follow-up/candidates')
      setCandidates(data.candidates || [])
    } catch (error: any) {
      setNotice(error instanceof Error ? error.message : '获取可跟进会话失败')
    } finally {
      setLoadingCandidates(false)
    }
  }

  const handleSendFollowUp = async (conversationId: string) => {
    setSendingFollowUpId(conversationId)
    try {
      await readChat(`/follow-up/${encodeURIComponent(conversationId)}`, {
        method: 'POST',
      })
      setNotice('已发送')
      await handleCheckCandidates()
    } catch (error: any) {
      setNotice(error instanceof Error ? error.message : '跟进发送失败')
    } finally {
      setSendingFollowUpId(null)
    }
  }

  const isResumeTextApproved = Boolean(
    profile.approvedResumeTextHash && profile.approvedResumeTextHash === profile.resumeTextHash
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>BOSS AI 工作台</CardTitle>
        <CardDescription>
          维护真实结构化资料、简历纯文本及 AI 自动回复策略。未批准内容不会注入 AI 事实。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* 只读工作地点 */}
        <div className="rounded-lg border bg-muted/20 p-3 text-xs text-muted-foreground">
          <span className="font-semibold text-foreground">接受的工作地点：</span>
          <span>工作地点来自搜索配置</span>
          <span className="ml-2 font-medium text-foreground">
            [{profile.acceptedCities && profile.acceptedCities.length > 0 ? profile.acceptedCities.join('、') : '未配置'}]
          </span>
        </div>

        {/* 四类结构化资料 + 历史事实 */}
        <ProfileSections
          value={profile.resume || initialResume}
          onChange={(resume) => setProfile((p) => ({ ...p, resume }))}
          legacyFacts={legacyFactsText}
          onLegacyFactsChange={setLegacyFactsText}
          disabled={busy || !loaded}
          onValidationChange={setIsSectionsValid}
        />

        {/* 简历文本区 */}
        <div className="space-y-3 rounded-lg border p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h4 className="text-sm font-semibold">简历纯文本</h4>
              <p className="text-xs text-muted-foreground">
                可粘贴完整简历文本（上限 20000 字符）。保存后需点击批准才可生效；未批准不注入 AI 事实库。
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span
                className={`text-xs px-2 py-0.5 rounded border ${
                  isResumeTextApproved
                    ? 'border-green-200 bg-green-50 text-green-700'
                    : 'border-amber-200 bg-amber-50 text-amber-700'
                }`}
              >
                {isResumeTextApproved ? '文本已批准' : '文本未批准，AI 不会使用'}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy || !loaded || !profile.resumeText?.trim()}
                onClick={() => void approveResumeText()}
              >
                批准文本
              </Button>
            </div>
          </div>
          <textarea
            rows={6}
            disabled={busy || !loaded}
            value={profile.resumeText || ''}
            onChange={(e) => setProfile((p) => ({ ...p, resumeText: e.target.value }))}
            placeholder="粘贴简历文本内容..."
            className="w-full rounded border bg-background px-3 py-2 text-xs font-mono"
            maxLength={20000}
          />
        </div>

        {/* 开关与跟进策略设置 */}
        <div className="space-y-4 rounded-lg border p-4">
          <h4 className="text-sm font-semibold">自动化与回复策略</h4>
          
          <div className="space-y-2">
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-0.5"
                checked={Boolean(profile.autoReply)}
                disabled={busy || !loaded}
                onChange={(e) => setProfile((p) => ({ ...p, autoReply: e.target.checked }))}
              />
              <div>
                <span className="font-medium">AI 自动回复</span>
                <p className="text-xs text-muted-foreground">
                  开启才在许可范围内自动处理。普通问答依据已知事实自动回复，缺资料不发送。
                </p>
              </div>
            </label>
          </div>

          <div className="space-y-3 border-t pt-3">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={Boolean(profile.followUp?.enabled)}
                disabled={busy || !loaded}
                onChange={(e) =>
                  setProfile((p) => ({
                    ...p,
                    followUp: {
                      enabled: e.target.checked,
                      cooldownHours: p.followUp?.cooldownHours ?? 168,
                      maxPerConversation: p.followUp?.maxPerConversation ?? 1,
                    },
                  }))
                }
              />
              <span className="font-medium">启用 AI 跟进</span>
            </label>

            <div className="grid gap-4 sm:grid-cols-2 pl-6">
              <label className="space-y-1 text-xs">
                <span className="font-medium text-foreground">跟进冷却时间（小时，24 - 720）</span>
                <input
                  type="number"
                  min={24}
                  max={720}
                  disabled={busy || !loaded || !profile.followUp?.enabled}
                  value={profile.followUp?.cooldownHours ?? 168}
                  onChange={(e) =>
                    setProfile((p) => ({
                      ...p,
                      followUp: {
                        enabled: p.followUp?.enabled ?? false,
                        cooldownHours: Math.min(720, Math.max(24, Number(e.target.value) || 24)),
                        maxPerConversation: p.followUp?.maxPerConversation ?? 1,
                      },
                    }))
                  }
                  className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                />
              </label>

              <label className="space-y-1 text-xs">
                <span className="font-medium text-foreground">每会话最大跟进次数（1 - 5）</span>
                <input
                  type="number"
                  min={1}
                  max={5}
                  disabled={busy || !loaded || !profile.followUp?.enabled}
                  value={profile.followUp?.maxPerConversation ?? 1}
                  onChange={(e) =>
                    setProfile((p) => ({
                      ...p,
                      followUp: {
                        enabled: p.followUp?.enabled ?? false,
                        cooldownHours: p.followUp?.cooldownHours ?? 168,
                        maxPerConversation: Math.min(5, Math.max(1, Number(e.target.value) || 1)),
                      },
                    }))
                  }
                  className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                />
              </label>
            </div>

            <div className="pl-6 pt-1 space-y-3">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy || !loaded || !profile.followUp?.enabled || loadingCandidates}
                title={
                  !profile.autoReply
                    ? '需先开启 AI 自动回复'
                    : !profile.followUp?.enabled
                    ? '需先开启 AI 跟进'
                    : '检查可跟进会话'
                }
                onClick={() => void handleCheckCandidates()}
                className="text-xs"
              >
                {loadingCandidates ? '正在检查...' : '检查可跟进会话'}
              </Button>

              {candidates !== null && (
                <div className="space-y-2 border-t pt-2">
                  {candidates.length === 0 ? (
                    <p className="text-xs text-muted-foreground">暂无可跟进会话</p>
                  ) : (
                    <div className="space-y-1.5">
                      {candidates.map((c) => (
                        <div
                          key={c.conversationId}
                          className="flex flex-wrap items-center justify-between gap-2 rounded border bg-card p-2 text-xs"
                        >
                          <div className="space-y-0.5 min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="font-mono font-medium truncate max-w-[200px]" title={c.conversationId}>
                                {c.conversationId.length > 24
                                  ? `${c.conversationId.slice(0, 10)}...${c.conversationId.slice(-8)}`
                                  : c.conversationId}
                              </span>
                              <span className="text-muted-foreground">已跟进 {c.followUpCount} 次</span>
                            </div>
                            <div className="text-muted-foreground text-[11px]">
                              最近出站：{c.lastOutboundAt ? new Date(c.lastOutboundAt).toLocaleString() : '无发送记录'}
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            {c.eligible ? (
                              <Button
                                type="button"
                                size="sm"
                                variant="default"
                                disabled={busy || sendingFollowUpId === c.conversationId}
                                onClick={() => void handleSendFollowUp(c.conversationId)}
                                className="h-7 text-xs px-2.5"
                              >
                                {sendingFollowUpId === c.conversationId ? '发送中...' : '发送跟进'}
                              </Button>
                            ) : (
                              <div className="flex items-center gap-1.5">
                                <span className="text-muted-foreground text-[11px]">
                                  {FOLLOW_UP_REASONS[c.reasonCode] || c.reasonCode || '不可跟进'}
                                </span>
                                <Button
                                  type="button"
                                  size="sm"
                                  variant="outline"
                                  disabled={true}
                                  className="h-7 text-xs px-2.5 opacity-50"
                                >
                                  发送跟进
                                </Button>
                              </div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 保存与检查操作条 */}
        <div className="flex flex-wrap items-center gap-3">
          <Button disabled={busy || !loaded || !isSectionsValid} onClick={() => void save()}>
            保存资料与授权
          </Button>
          <Button
            variant="outline"
            disabled={busy || !loaded}
            onClick={async () => {
              setBusy(true)
              try {
                await readChat('/scan', { method: 'POST' })
                await refreshRecords()
                setNotice('已检查新消息。')
              } catch (e) {
                setNotice(e instanceof Error ? e.message : '检查失败')
              } finally {
                setBusy(false)
              }
            }}
          >
            检查新消息
          </Button>
        </div>

        {notice && (
          <p role="status" className="text-sm font-medium text-muted-foreground">
            {notice}
          </p>
        )}

        {/* 聊天记录列表 */}
        <div className="space-y-3 pt-2">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold">处理记录（待人工核实 / 阻断 / 失败）</h4>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={busy}
              className="text-xs text-muted-foreground hover:text-foreground"
              onClick={() => void refreshRecords()}
            >
              刷新记录
            </Button>
          </div>
          <ChatRecords records={records} onReconcile={handleReconcile} busy={busy} />
        </div>
      </CardContent>
    </Card>
  )
}
