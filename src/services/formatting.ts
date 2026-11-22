import type { Locale } from '../i18n/types';

export function pluralizeRu(count: number, forms: [string, string, string]): string {
  const abs = Math.abs(count) % 100;
  const lastDigit = abs % 10;
  if (abs >= 11 && abs <= 19) return forms[2];
  if (lastDigit === 1) return forms[0];
  if (lastDigit >= 2 && lastDigit <= 4) return forms[1];
  return forms[2];
}

export function formatFactCount(count: number, locale: Locale, withPlus = false): string {
  const prefix = withPlus ? '+' : '';
  if (locale === 'ru') {
    return `${prefix}${count} ${pluralizeRu(count, ['факт', 'факта', 'фактов'])}`;
  }
  return `${prefix}${count} ${count === 1 ? 'fact' : 'facts'}`;
}

export function formatChapterCount(count: number, locale: Locale): string {
  if (locale === 'ru') {
    return `${count} ${pluralizeRu(count, ['глава', 'главы', 'глав'])}`;
  }
  return `${count} ${count === 1 ? 'chapter' : 'chapters'}`;
}

export function formatSavedBookmarkLabel(
  chapterId: string,
  volShort: string,
  chapShort: string,
): string {
  const match = chapterId.match(/vol_(\d+)_chap_([\d.]+)/);
  if (match) {
    return `${volShort} ${match[1]} · ${chapShort} ${match[2]}`;
  }
  return chapterId;
}

export function cleanChapterTitle(title: string | null | undefined): string {
  if (!title) return '';
  const cleaned = title
    .replace(/^#+\s*/, '')
    .replace(
      /^(?:том|vol\.?|volume)\s*\d+\s*[,·\-–—]?\s*(?:глава|гл\.?|ch\.?|chapter)\s*[\d.]+\s*[:\-–—.]*\s*/i,
      '',
    )
    .replace(/^(?:глава|гл\.?|ch\.?|chapter)\s*[\d.]+\s*[:\-–—.]*\s*/i, '')
    .trim();
  return cleaned || title.replace(/^#+\s*/, '').trim();
}

export type ChapterBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'heading'; text: string }
  | { type: 'sceneBreak' }
  | { type: 'illustration'; alt: string; filename: string | null };

const SCENE_BREAK_RE = /^(?:\\\*|\*|-|_){3,}(?:\s*(?:\\\*|\*|-|_))*$/;
const IMAGE_MD_GLOBAL_RE = /!\[([^\]]*)\]\(([^)]+)\)/g;
const LONE_PUNCTUATION_RE = /^[.,;:!?…\-–—]+$/;

function cleanInlineMarkdown(line: string): string {
  return line
    .replace(/\\([*_~`#\[\]()>+\-!])/g, '$1')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/__(.+?)__/g, '$1')
    .trim();
}

function extractFilename(rawSrc: string): string | null {
  const cleaned = rawSrc.trim().split(/[?#]/)[0];
  const parts = cleaned.split('/');
  const last = parts[parts.length - 1];
  return last && !last.includes('..') ? last : null;
}

export function parseChapterBlocks(rawText: string, headerTitle?: string | null): ChapterBlock[] {
  const lines = rawText.split(/\r?\n/);
  const blocks: ChapterBlock[] = [];
  const normalizedHeader = headerTitle ? cleanChapterTitle(headerTitle).toLowerCase() : '';

  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim();
    if (!trimmed) continue;

    if (SCENE_BREAK_RE.test(trimmed)) {
      blocks.push({ type: 'sceneBreak' });
      continue;
    }

    // Handle standalone or inline Markdown images (and drop trailing lone punctuation like ".")
    if (trimmed.includes('![')) {
      IMAGE_MD_GLOBAL_RE.lastIndex = 0;
      let cursor = 0;
      let matchedAny = false;
      let match: RegExpExecArray | null;

      while ((match = IMAGE_MD_GLOBAL_RE.exec(trimmed)) !== null) {
        matchedAny = true;
        const before = cleanInlineMarkdown(trimmed.slice(cursor, match.index));
        if (before && !LONE_PUNCTUATION_RE.test(before)) {
          blocks.push({ type: 'paragraph', text: before });
        }
        blocks.push({
          type: 'illustration',
          alt: match[1].trim() || 'Illustration',
          filename: extractFilename(match[2]),
        });
        cursor = match.index + match[0].length;
      }

      if (matchedAny) {
        const after = cleanInlineMarkdown(trimmed.slice(cursor));
        if (after && !LONE_PUNCTUATION_RE.test(after)) {
          blocks.push({ type: 'paragraph', text: after });
        }
        continue;
      }
    }

    if (trimmed.startsWith('#')) {
      const headingText = cleanInlineMarkdown(trimmed.replace(/^#+\s*/, ''));
      if (
        blocks.length === 0 &&
        normalizedHeader &&
        (cleanChapterTitle(headingText).toLowerCase() === normalizedHeader ||
          headingText.toLowerCase().includes(normalizedHeader))
      ) {
        continue;
      }
      blocks.push({ type: 'heading', text: headingText });
      continue;
    }

    const cleaned = cleanInlineMarkdown(trimmed);
    if (cleaned && !(LONE_PUNCTUATION_RE.test(cleaned) && blocks[blocks.length - 1]?.type === 'illustration')) {
      blocks.push({ type: 'paragraph', text: cleaned });
    }
  }

  return blocks;
}
