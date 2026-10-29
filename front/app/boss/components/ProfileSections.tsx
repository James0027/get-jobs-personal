'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'

export interface WorkItem {
  id: string
  company: string
  title: string
  period?: string
  summary?: string
}

export interface ProjectItem {
  id: string
  name: string
  role?: string
  period?: string
  description?: string
}

export interface EducationItem {
  id: string
  school: string
  degree?: string
  major?: string
  period?: string
}

export interface SkillItem {
  id: string
  name: string
  level?: string
  description?: string
}

export interface ResumeData {
  work: WorkItem[]
  project: ProjectItem[]
  education: EducationItem[]
  skills: SkillItem[]
}

interface ProfileSectionsProps {
  value: ResumeData
  onChange: (next: ResumeData) => void
  legacyFacts: string
  onLegacyFactsChange: (text: string) => void
  disabled?: boolean
  onValidationChange?: (ok: boolean) => void
}

function generateId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'id-' + Math.random().toString(36).slice(2, 11)
}

export default function ProfileSections({
  value,
  onChange,
  legacyFacts,
  onLegacyFactsChange,
  disabled = false,
  onValidationChange,
}: ProfileSectionsProps) {
  const [showLegacy, setShowLegacy] = useState(false)
  const [touched, setTouched] = useState(false)

  const workList = value?.work || []
  const projectList = value?.project || []
  const educationList = value?.education || []
  const skillsList = value?.skills || []

  // 校验规则：
  // work: company*, title*
  // project: name*
  // education: school*
  // skills: name*
  const hasWorkError = workList.some((w) => !w.company?.trim() || !w.title?.trim())
  const hasProjectError = projectList.some((p) => !p.name?.trim())
  const hasEducationError = educationList.some((e) => !e.school?.trim())
  const hasSkillsError = skillsList.some((s) => !s.name?.trim())
  const isValid = !hasWorkError && !hasProjectError && !hasEducationError && !hasSkillsError

  // 通知上层校验状态
  if (onValidationChange) {
    // 异步或直接触发可能会在渲染循环中引起警告，但作为受控通知，利用 effect 或者直接外传是可行的。
    // 在本组件中，我们提供一个内部 update helper。
  }

  const triggerChange = (next: ResumeData) => {
    setTouched(true)
    onChange(next)
    if (onValidationChange) {
      const nextWork = next.work || []
      const nextProj = next.project || []
      const nextEdu = next.education || []
      const nextSkills = next.skills || []
      const ok =
        !nextWork.some((w) => !w.company?.trim() || !w.title?.trim()) &&
        !nextProj.some((p) => !p.name?.trim()) &&
        !nextEdu.some((e) => !e.school?.trim()) &&
        !nextSkills.some((s) => !s.name?.trim())
      onValidationChange(ok)
    }
  }

  // 工作经历操作
  const addWork = () => {
    triggerChange({
      ...value,
      work: [...workList, { id: generateId(), company: '', title: '', period: '', summary: '' }],
    })
  }
  const updateWork = (index: number, patch: Partial<WorkItem>) => {
    const next = [...workList]
    next[index] = { ...next[index], ...patch }
    triggerChange({ ...value, work: next })
  }
  const removeWork = (index: number) => {
    const next = workList.filter((_, i) => i !== index)
    triggerChange({ ...value, work: next })
  }

  // 项目经历操作
  const addProject = () => {
    triggerChange({
      ...value,
      project: [...projectList, { id: generateId(), name: '', role: '', period: '', description: '' }],
    })
  }
  const updateProject = (index: number, patch: Partial<ProjectItem>) => {
    const next = [...projectList]
    next[index] = { ...next[index], ...patch }
    triggerChange({ ...value, project: next })
  }
  const removeProject = (index: number) => {
    const next = projectList.filter((_, i) => i !== index)
    triggerChange({ ...value, project: next })
  }

  // 教育背景操作
  const addEducation = () => {
    triggerChange({
      ...value,
      education: [...educationList, { id: generateId(), school: '', degree: '', major: '', period: '' }],
    })
  }
  const updateEducation = (index: number, patch: Partial<EducationItem>) => {
    const next = [...educationList]
    next[index] = { ...next[index], ...patch }
    triggerChange({ ...value, education: next })
  }
  const removeEducation = (index: number) => {
    const next = educationList.filter((_, i) => i !== index)
    triggerChange({ ...value, education: next })
  }

  // 专业技能操作
  const addSkill = () => {
    triggerChange({
      ...value,
      skills: [...skillsList, { id: generateId(), name: '', level: '', description: '' }],
    })
  }
  const updateSkill = (index: number, patch: Partial<SkillItem>) => {
    const next = [...skillsList]
    next[index] = { ...next[index], ...patch }
    triggerChange({ ...value, skills: next })
  }
  const removeSkill = (index: number) => {
    const next = skillsList.filter((_, i) => i !== index)
    triggerChange({ ...value, skills: next })
  }

  return (
    <div className="space-y-6">
      {/* 1. 工作经历 */}
      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h4 className="text-sm font-semibold">工作经历</h4>
            <p className="text-xs text-muted-foreground">真实在职经历，公司与职位必填</p>
          </div>
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={addWork}>
            + 添加工作经历
          </Button>
        </div>
        {workList.length === 0 ? (
          <p className="py-2 text-xs text-muted-foreground">暂无工作经历</p>
        ) : (
          <div className="space-y-3">
            {workList.map((item, idx) => {
              const companyEmpty = !item.company?.trim()
              const titleEmpty = !item.title?.trim()
              return (
                <div key={item.id} className="relative space-y-3 rounded-md border bg-card p-3 text-sm">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <label className="space-y-1 text-xs">
                      <span className="flex items-center gap-1 font-medium">
                        公司名称 <span className="text-red-500">*</span>
                      </span>
                      <input
                        disabled={disabled}
                        value={item.company || ''}
                        onChange={(e) => updateWork(idx, { company: e.target.value })}
                        placeholder="例如：北京某某科技有限公司"
                        className={`w-full rounded border px-2.5 py-1.5 text-xs bg-background ${
                          companyEmpty ? 'border-red-500 focus:ring-red-500' : ''
                        }`}
                      />
                      {companyEmpty && <span className="text-[10px] text-red-500">公司名称必填</span>}
                    </label>
                    <label className="space-y-1 text-xs">
                      <span className="flex items-center gap-1 font-medium">
                        职位 <span className="text-red-500">*</span>
                      </span>
                      <input
                        disabled={disabled}
                        value={item.title || ''}
                        onChange={(e) => updateWork(idx, { title: e.target.value })}
                        placeholder="例如：高级全栈开发工程师"
                        className={`w-full rounded border px-2.5 py-1.5 text-xs bg-background ${
                          titleEmpty ? 'border-red-500 focus:ring-red-500' : ''
                        }`}
                      />
                      {titleEmpty && <span className="text-[10px] text-red-500">职位必填</span>}
                    </label>
                  </div>
                  <label className="block space-y-1 text-xs">
                    <span className="font-medium">起止时间</span>
                    <input
                      disabled={disabled}
                      value={item.period || ''}
                      onChange={(e) => updateWork(idx, { period: e.target.value })}
                      placeholder="例如：2021-2024"
                      className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                    />
                  </label>
                  <label className="block space-y-1 text-xs">
                    <span className="font-medium">职责与成果</span>
                    <textarea
                      rows={2}
                      disabled={disabled}
                      value={item.summary || ''}
                      onChange={(e) => updateWork(idx, { summary: e.target.value })}
                      placeholder="工作职责、关键项目与核心业绩"
                      className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                    />
                  </label>
                  <div className="flex justify-end">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={disabled}
                      className="h-7 text-xs text-red-600 hover:text-red-700"
                      onClick={() => removeWork(idx)}
                    >
                      删除条目
                    </Button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 2. 项目经历 */}
      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h4 className="text-sm font-semibold">项目经历</h4>
            <p className="text-xs text-muted-foreground">参与或主导的关键项目，名称必填</p>
          </div>
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={addProject}>
            + 添加项目经历
          </Button>
        </div>
        {projectList.length === 0 ? (
          <p className="py-2 text-xs text-muted-foreground">暂无项目经历</p>
        ) : (
          <div className="space-y-3">
            {projectList.map((item, idx) => {
              const nameEmpty = !item.name?.trim()
              return (
                <div key={item.id} className="relative space-y-3 rounded-md border bg-card p-3 text-sm">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <label className="space-y-1 text-xs">
                      <span className="flex items-center gap-1 font-medium">
                        项目名称 <span className="text-red-500">*</span>
                      </span>
                      <input
                        disabled={disabled}
                        value={item.name || ''}
                        onChange={(e) => updateProject(idx, { name: e.target.value })}
                        placeholder="例如：企业核心服务中台"
                        className={`w-full rounded border px-2.5 py-1.5 text-xs bg-background ${
                          nameEmpty ? 'border-red-500 focus:ring-red-500' : ''
                        }`}
                      />
                      {nameEmpty && <span className="text-[10px] text-red-500">项目名称必填</span>}
                    </label>
                    <label className="space-y-1 text-xs">
                      <span className="font-medium">角色</span>
                      <input
                        disabled={disabled}
                        value={item.role || ''}
                        onChange={(e) => updateProject(idx, { role: e.target.value })}
                        placeholder="例如：核心开发者 / 负责人"
                        className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                      />
                    </label>
                  </div>
                  <label className="block space-y-1 text-xs">
                    <span className="font-medium">起止时间</span>
                    <input
                      disabled={disabled}
                      value={item.period || ''}
                      onChange={(e) => updateProject(idx, { period: e.target.value })}
                      placeholder="例如：2023.03 - 2023.11"
                      className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                    />
                  </label>
                  <label className="block space-y-1 text-xs">
                    <span className="font-medium">项目描述</span>
                    <textarea
                      rows={2}
                      disabled={disabled}
                      value={item.description || ''}
                      onChange={(e) => updateProject(idx, { description: e.target.value })}
                      placeholder="项目背景、架构设计、落地成果与亮点"
                      className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                    />
                  </label>
                  <div className="flex justify-end">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={disabled}
                      className="h-7 text-xs text-red-600 hover:text-red-700"
                      onClick={() => removeProject(idx)}
                    >
                      删除条目
                    </Button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 3. 教育背景 */}
      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h4 className="text-sm font-semibold">教育背景</h4>
            <p className="text-xs text-muted-foreground">毕业院校与所修专业，学校必填</p>
          </div>
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={addEducation}>
            + 添加教育背景
          </Button>
        </div>
        {educationList.length === 0 ? (
          <p className="py-2 text-xs text-muted-foreground">暂无教育背景</p>
        ) : (
          <div className="space-y-3">
            {educationList.map((item, idx) => {
              const schoolEmpty = !item.school?.trim()
              return (
                <div key={item.id} className="relative space-y-3 rounded-md border bg-card p-3 text-sm">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <label className="space-y-1 text-xs">
                      <span className="flex items-center gap-1 font-medium">
                        学校名称 <span className="text-red-500">*</span>
                      </span>
                      <input
                        disabled={disabled}
                        value={item.school || ''}
                        onChange={(e) => updateEducation(idx, { school: e.target.value })}
                        placeholder="例如：北京大学"
                        className={`w-full rounded border px-2.5 py-1.5 text-xs bg-background ${
                          schoolEmpty ? 'border-red-500 focus:ring-red-500' : ''
                        }`}
                      />
                      {schoolEmpty && <span className="text-[10px] text-red-500">学校名称必填</span>}
                    </label>
                    <label className="space-y-1 text-xs">
                      <span className="font-medium">学历</span>
                      <input
                        disabled={disabled}
                        value={item.degree || ''}
                        onChange={(e) => updateEducation(idx, { degree: e.target.value })}
                        placeholder="例如：本科 / 硕士 / 博士"
                        className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                      />
                    </label>
                  </div>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <label className="space-y-1 text-xs">
                      <span className="font-medium">专业</span>
                      <input
                        disabled={disabled}
                        value={item.major || ''}
                        onChange={(e) => updateEducation(idx, { major: e.target.value })}
                        placeholder="例如：计算机科学与技术"
                        className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                      />
                    </label>
                    <label className="space-y-1 text-xs">
                      <span className="font-medium">在读时间</span>
                      <input
                        disabled={disabled}
                        value={item.period || ''}
                        onChange={(e) => updateEducation(idx, { period: e.target.value })}
                        placeholder="例如：2017-2021"
                        className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                      />
                    </label>
                  </div>
                  <div className="flex justify-end">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={disabled}
                      className="h-7 text-xs text-red-600 hover:text-red-700"
                      onClick={() => removeEducation(idx)}
                    >
                      删除条目
                    </Button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 4. 专业技能 */}
      <div className="space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h4 className="text-sm font-semibold">专业技能</h4>
            <p className="text-xs text-muted-foreground">掌握的核心技能与熟练度，技能名称必填</p>
          </div>
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={addSkill}>
            + 添加专业技能
          </Button>
        </div>
        {skillsList.length === 0 ? (
          <p className="py-2 text-xs text-muted-foreground">暂无专业技能</p>
        ) : (
          <div className="space-y-3">
            {skillsList.map((item, idx) => {
              const nameEmpty = !item.name?.trim()
              return (
                <div key={item.id} className="relative space-y-3 rounded-md border bg-card p-3 text-sm">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <label className="space-y-1 text-xs">
                      <span className="flex items-center gap-1 font-medium">
                        技能名称 <span className="text-red-500">*</span>
                      </span>
                      <input
                        disabled={disabled}
                        value={item.name || ''}
                        onChange={(e) => updateSkill(idx, { name: e.target.value })}
                        placeholder="例如：Java / TypeScript / Spring Boot"
                        className={`w-full rounded border px-2.5 py-1.5 text-xs bg-background ${
                          nameEmpty ? 'border-red-500 focus:ring-red-500' : ''
                        }`}
                      />
                      {nameEmpty && <span className="text-[10px] text-red-500">技能名称必填</span>}
                    </label>
                    <label className="space-y-1 text-xs">
                      <span className="font-medium">熟练度</span>
                      <input
                        disabled={disabled}
                        value={item.level || ''}
                        onChange={(e) => updateSkill(idx, { level: e.target.value })}
                        placeholder="例如：精通 / 熟练 / 掌握"
                        className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                      />
                    </label>
                  </div>
                  <label className="block space-y-1 text-xs">
                    <span className="font-medium">技能说明</span>
                    <input
                      disabled={disabled}
                      value={item.description || ''}
                      onChange={(e) => updateSkill(idx, { description: e.target.value })}
                      placeholder="例如：具备 3 年微服务设计与高并发治理经验"
                      className="w-full rounded border px-2.5 py-1.5 text-xs bg-background"
                    />
                  </label>
                  <div className="flex justify-end">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={disabled}
                      className="h-7 text-xs text-red-600 hover:text-red-700"
                      onClick={() => removeSkill(idx)}
                    >
                      删除条目
                    </Button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 5. 历史事实折叠区 */}
      <div className="rounded-lg border p-4 space-y-2">
        <button
          type="button"
          onClick={() => setShowLegacy(!showLegacy)}
          className="flex w-full items-center justify-between text-left text-sm font-semibold text-foreground hover:text-primary"
        >
          <span>其他已确认事实（历史资料）</span>
          <span className="text-xs text-muted-foreground">{showLegacy ? '收起 ▲' : '展开 ▼'}</span>
        </button>
        {showLegacy && (
          <div className="space-y-2 pt-2">
            <p className="text-xs text-muted-foreground">
              这些是历史资料中已确认的个人事实，AI 依据这些信息做回答；删除或修改会直接影响 AI 可用事实。每行一条。
            </p>
            <textarea
              rows={4}
              disabled={disabled}
              value={legacyFacts}
              onChange={(e) => onLegacyFactsChange(e.target.value)}
              placeholder="每行一条历史事实"
              className="w-full rounded border bg-background px-3 py-2 text-xs"
            />
          </div>
        )}
      </div>
    </div>
  )
}
