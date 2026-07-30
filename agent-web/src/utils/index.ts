import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}

/** 生成唯一 ID（简单递增，足够前端使用） */
let _id = 0
export function uniqueId(): string {
  return `msg_${++_id}_${Date.now().toString(36)}`
}
