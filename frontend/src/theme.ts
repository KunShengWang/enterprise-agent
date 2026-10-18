/**
 * 主题解析与应用。
 *
 * 优先级：URL `?theme=dark|light` > localStorage > 浅色（默认）。
 * URL 参数存在是为了让 headless 截图能稳定地分别渲染两套主题——
 * 每次截图都是全新的浏览器 profile，localStorage 不跨进程留存。
 *
 * 浅色是产品默认（2026-10-18 用户确认）；深色保留为可选项，
 * 但在 styles.css 的 252 处硬编码背景色迁移完成前，深色只是半成品。
 */
export type Theme = 'dark' | 'light'

const STORAGE_KEY = 'enterprise-agent-theme'

function isTheme(value: unknown): value is Theme {
  return value === 'dark' || value === 'light'
}

/** 只读解析，不产生副作用。 */
export function resolveTheme(): Theme {
  if (typeof window === 'undefined') return 'light'
  const fromUrl = new URLSearchParams(window.location.search).get('theme')
  if (isTheme(fromUrl)) return fromUrl
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    if (isTheme(stored)) return stored
  } catch {
    /* localStorage 被禁用时静默回退到默认主题 */
  }
  return 'light'
}

/** 应用到 <html data-theme>，可选持久化。 */
export function applyTheme(theme: Theme, persist = true): void {
  document.documentElement.dataset.theme = theme
  if (!persist) return
  try {
    window.localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    /* 无法持久化不影响本次会话内的主题 */
  }
}

/** 首帧前调用：让页面在挂载前就有正确的 data-theme，避免闪一下另一套主题。 */
export function bootstrapTheme(): Theme {
  const theme = resolveTheme()
  applyTheme(theme, false)
  return theme
}

export function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

export function toggleTheme(): Theme {
  const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(next)
  return next
}
