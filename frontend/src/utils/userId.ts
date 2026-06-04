const STORAGE_KEY = 'chatbot_user_id'

function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export function getUserId(): string | null {
  return localStorage.getItem(STORAGE_KEY)
}

export function getOrCreateUserId(): string {
  let id = localStorage.getItem(STORAGE_KEY)
  if (!id) {
    id = generateUUID()
    localStorage.setItem(STORAGE_KEY, id)
  }
  return id
}

export function setUserId(id: string): void {
  localStorage.setItem(STORAGE_KEY, id)
}

export function clearUserId(): void {
  localStorage.removeItem(STORAGE_KEY)
}

export function isValidUserId(id: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)
}
