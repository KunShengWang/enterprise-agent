import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { normalizeMarkdown } from './markdownNormalizer'

export function renderMarkdown(source: string): string {
  if (!source.trim()) return ''

  const html = marked.parse(normalizeMarkdown(source), {
    async: false,
    breaks: true,
    gfm: true,
  })

  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['audio', 'form', 'iframe', 'img', 'style', 'video'],
    FORBID_ATTR: ['style', 'onerror', 'onload'],
  })
}
