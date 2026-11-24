export interface ProjectCatalogItem {
  id: string;
  title: string;
  author: string | null;
  chapters_count: number;
  volumes_count: number;
  has_cover: boolean;
}

export interface ChapterInfo {
  id: string;
  volume_num: number;
  chapter_num: number;
  title: string | null;
  status: string;
  has_audio: boolean;
}

export interface BookDetails {
  book_id: string;
  title: string;
  author: string | null;
  chapters: ChapterInfo[];
}

export interface ChapterText {
  chapter_id: string;
  volume_num: number;
  chapter_num: number;
  title: string | null;
  text: string;
  source: string;
}

export interface VolumeGroup {
  volumeNum: number;
  chapters: ChapterInfo[];
}

export type CharacterDetailTab = 'overview' | 'chronicle' | 'facts';
