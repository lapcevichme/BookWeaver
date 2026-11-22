export type Locale = 'ru' | 'en';

export interface Dictionary {
  app: {
    name: string;
    libraryTitle: string;
    librarySubtitle: string;
    backToLibrary: string;
  };
  theme: {
    toLight: string;
    toDark: string;
    paletteLabel: string;
  };
  language: {
    switchLabel: string;
  };
  books: {
    searchPlaceholder: string;
    loading: string;
    empty: string;
    errorTitle: string;
    errorHint: string;
    retry: string;
    unknownAuthor: string;
    chaptersCount: string;
    continueReading: string;
    startReading: string;
  };
  bookmark: {
    shieldActive: string;
    volumeShort: string;
    volumeFull: string;
    chapterShort: string;
    chapterFull: string;
    prevChapter: string;
    nextChapter: string;
    pickChapter: string;
    searchChapterPlaceholder: string;
    noChaptersFound: string;
    noChapters: string;
  };
  tabs: {
    read: string;
    codex: string;
    recap: string;
  };
  reader: {
    loadingChapter: string;
    chapterTextMissing: string;
    chapterTextHint: string;
    inThisChapter: string;
    openCodex: string;
    nextChapterCta: string;
    prevChapterCta: string;
    fontSmaller: string;
    fontLarger: string;
    highlightNames: string;
    openFullDossier: string;
    latestFacts: string;
    closeSheet: string;
    settingsTitle: string;
    fontSize: string;
    lineHeight: string;
    lineTight: string;
    lineNormal: string;
    lineRelaxed: string;
    columnWidth: string;
    widthNarrow: string;
    widthStandard: string;
    widthWide: string;
    fontFamily: string;
    fontSans: string;
    fontSerif: string;
    keyboardHint: string;
  };
  quickSearch: {
    triggerLabel: string;
    placeholder: string;
    charactersGroup: string;
    chaptersGroup: string;
    empty: string;
    hint: string;
  };
  codex: {
    searchPlaceholder: string;
    loading: string;
    error: string;
    empty: string;
    emptySearch: string;
    backToList: string;
    filterAll: string;
    filterChanged: string;
    newInChapter: string;
    revealBadge: string;
    firstSeen: string;
    descriptionAsOf: string;
    aliasesTitle: string;
    revealsTitle: string;
    revealedIn: string;
    tabOverview: string;
    tabChronicle: string;
    tabFacts: string;
    factsTitle: string;
    factsRetired: string;
    factKindAll: string;
    noFacts: string;
    noDescription: string;
    chronicleTitle: string;
    noChronicle: string;
    untilChapter: string;
    newBadgeShort: string;
    notFound: string;
    searchFactsPlaceholder: string;
    searchChroniclePlaceholder: string;
    showMore: string;
    remaining: string;
    roles: {
      protagonist: string;
      major: string;
      minor: string;
      background: string;
    };
    factKinds: Record<string, string>;
    relations: Record<string, string>;
  };
  recap: {
    loading: string;
    error: string;
    empty: string;
    volumesTitle: string;
    chaptersTitle: string;
    showSynopsis: string;
    hideSynopsis: string;
    noSummaryYet: string;
    completedVolume: string;
  };
}
