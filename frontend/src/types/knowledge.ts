export type RoleTier = 'protagonist' | 'major' | 'minor' | 'background';

export interface CharacterFact {
  id: string | null;
  fact: string;
  kind: string;
  chapter: string | null;
  until: string | null;
}

export interface IdentityReveal {
  link_id: string;
  relation: string;
  role: string;
  other_character_id: string;
  other_character_name: string;
  revealed_in: string | null;
  evidence: string;
  active_chapters: string[];
}

export interface CharacterAsOf {
  id: string;
  name: string;
  aliases: string[];
  description: string;
  description_from: string | null;
  first_chapter: string | null;
  role_tier: RoleTier | string | null;
  entity_type: string | null;
  gender: string | null;
  voice_base: string | null;
  voice_profile: string;
  visual_base: string;
  chapter_mentions: Record<string, string>;
  facts: CharacterFact[];
  reveals: IdentityReveal[];
}

export interface CharacterDelta {
  isNew: boolean;
  newFactsCount: number;
  newAliases: string[];
  newRevealsCount: number;
  hasAnyChange: boolean;
}

export interface ChapterSummaryItem {
  chapter_id: string;
  volume_num: number;
  chapter_num: number;
  order_index: number;
  title: string | null;
  is_service: boolean;
  teaser: string | null;
  synopsis: string | null;
}

export interface VolumeSummaryItem {
  volume_num: number;
  summary: string;
}

export interface BookSummaries {
  as_of: string | null;
  chapters: ChapterSummaryItem[];
  volumes: VolumeSummaryItem[];
}
