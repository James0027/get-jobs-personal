"use client"
import { usePathname } from 'next/navigation'
import { ReactNode } from 'react'

export default function ContentArea({ children }: { children: ReactNode }) {
  const pathname = usePathname()

  return (
    <main className="flex-1 min-w-0 w-full ml-0 md:ml-64 bg-background h-screen overflow-y-auto overflow-x-hidden flex flex-col">
      <div key={pathname} className="flex-1 min-h-full w-full py-8 px-4 md:px-6 [&>*]:min-h-full">
        {children}
      </div>
    </main>
  )
}