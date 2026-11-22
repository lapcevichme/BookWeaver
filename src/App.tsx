import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { MotionConfig } from 'motion/react';
import { BookLayout, useBookContext } from './features/book/BookLayout';
import { CharacterDetailPage } from './features/codex/CharacterDetailPage';
import { CharactersPage } from './features/codex/CharactersPage';
import { LibraryPage } from './features/library/LibraryPage';
import { ReaderPage } from './features/reader/ReaderPage';
import { RecapPage } from './features/recap/RecapPage';
import { ThemeProvider } from './hooks/useTheme';
import { I18nProvider } from './i18n/I18nContext';

function BookIndexRedirect() {
  const { bookId, activeChapter } = useBookContext();
  if (!activeChapter) {
    return <ReaderPage />;
  }
  return (
    <Navigate
      to={`/books/${encodeURIComponent(bookId)}/read/${encodeURIComponent(activeChapter.id)}`}
      replace
    />
  );
}

export function App() {
  return (
    <MotionConfig reducedMotion="user">
      <ThemeProvider>
        <I18nProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/" element={<LibraryPage />} />
              <Route path="/books/:bookId" element={<BookLayout />}>
                <Route index element={<BookIndexRedirect />} />
                <Route path="read/:chapterId" element={<ReaderPage />} />
                <Route path="characters" element={<CharactersPage />} />
                <Route path="characters/:charId" element={<CharacterDetailPage />} />
                <Route path="recap" element={<RecapPage />} />
              </Route>
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </BrowserRouter>
        </I18nProvider>
      </ThemeProvider>
    </MotionConfig>
  );
}
