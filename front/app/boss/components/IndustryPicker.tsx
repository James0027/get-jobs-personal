'use client'

import { useState } from 'react'

export interface IndustryOption { id: number; name: string; code: string }
export interface IndustryGroup { code: string; name: string; options: IndustryOption[] }

export default function IndustryPicker({ options, groups, selected, onChange }: {
  options: IndustryOption[]; groups?: IndustryGroup[]; selected: string[]; onChange: (codes: string[]) => void
}) {
  const [active, setActive] = useState('')
  const [query, setQuery] = useState('')
  const categories = [...(groups || []), { code: 'all', name: '全部行业', options }]
  const groupedCodes = new Set((groups || []).flatMap(g => g.options.map(o => o.code)))
  const unclassified = options.filter(o => o.code !== '0' && !groupedCodes.has(o.code))
  if (unclassified.length) categories.push({ code: 'unclassified', name: '待核实分类', options: unclassified })
  const current = categories.find(g => g.code === active) || categories[0]
  const visible = current.options.filter(o => o.name.includes(query.trim()))
  const toggle = (code: string) => onChange(code === '0' ? ['0'] : selected.includes(code)
    ? selected.filter(s => s !== code) : [...selected.filter(s => s !== '0'), code])
  return <div className="space-y-3">
    <div className="flex items-center gap-2">
      <input aria-label="搜索细分行业" value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索细分行业" className="w-full rounded-xl border bg-background px-3 py-2 text-sm" />
      <button type="button" onClick={() => onChange([])} className="shrink-0 text-sm text-muted-foreground hover:text-foreground">清空</button>
    </div>
    <div className="grid min-h-56 grid-cols-[130px_minmax(0,1fr)] overflow-hidden rounded-xl border bg-background/60">
      <div className="space-y-1 border-r bg-muted/30 p-2">
        {categories.map(g => <button type="button" key={g.code} aria-pressed={current.code === g.code} onClick={() => setActive(g.code)} className={`w-full rounded-lg px-3 py-2 text-left text-sm ${current.code === g.code ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}`}>{g.name}</button>)}
      </div>
      <div className="flex max-h-72 flex-wrap content-start gap-2 overflow-y-auto p-3">
        {visible.map(o => <button type="button" key={o.code} aria-pressed={selected.includes(o.code)} onClick={() => toggle(o.code)} className={`rounded-lg border px-3 py-2 text-sm transition-colors ${selected.includes(o.code) ? 'border-primary bg-primary/10 text-primary' : 'hover:bg-muted'}`}>{o.name}</button>)}
        {!visible.length && <p className="text-sm text-muted-foreground">没有匹配的行业</p>}
      </div>
    </div>
    <div className="flex flex-wrap gap-2">{selected.map(code => <button type="button" key={code} onClick={() => toggle(code)} className="rounded-full bg-primary/10 px-3 py-1 text-xs text-primary">{options.find(o => o.code === code)?.name || code} ×</button>)}</div>
    {unclassified.length > 0 && <p className="text-xs text-muted-foreground">未核实官方父子关系的行业保留在“待核实分类”，仍可选择。</p>}
  </div>
}
