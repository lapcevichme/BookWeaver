import re
import json
import zipfile
import ebooklib
from ebooklib import epub
from bs4 import BeautifulSoup
from markdownify import markdownify as md
from pathlib import Path
from typing import Dict, Optional, Tuple, Any, List
from utils.text_utils import clean_raw_text


class BookParser:
    """Базовый интерфейс для парсеров книг."""

    def parse(self, file_path: Path) -> Tuple[
        Dict[int, Dict[int, str]], Dict[str, Any], Optional[bytes], Dict[str, bytes]]:
        """
        Возвращает:
        1. Структуру {volume: {chapter: text}}
        2. Словарь метаданных {'title': str, 'author': str, 'tags': list, 'language': str, ...}
        3. Байты обложки (если есть)
        4. Словарь картинок {filename: bytes}
        """
        raise NotImplementedError


class EpubParser(BookParser):
    def parse(self, file_path: Path) -> Tuple[
        Dict[int, Dict[int, str]], Dict[str, Any], Optional[bytes], Dict[str, bytes]]:
        epub.warnings.simplefilter('ignore')

        book = epub.read_epub(str(file_path))

        title = self._extract_metadata(book, 'title')
        author = self._extract_metadata(book, 'creator')
        description = self._extract_metadata(book, 'description')
        language = self._extract_metadata(book, 'language')
        tags = self._extract_list_metadata(book, 'subject')

        cover_bytes = self._extract_cover(book)
        images_dict = self._extract_all_images(book)
        volumes = self._parse_content(book)

        meta = {
            "title": title,
            "author": author,
            "description": description,
            "language": language or "ru",
            "tags": tags,
            "source": "epub_import"
        }

        return volumes, meta, cover_bytes, images_dict

    def _extract_metadata(self, book, meta_type: str) -> Optional[str]:
        try:
            meta = book.get_metadata('DC', meta_type)
            if meta:
                return meta[0][0]
        except:
            pass
        return None

    def _extract_list_metadata(self, book, meta_type: str) -> List[str]:
        results = []
        try:
            meta_list = book.get_metadata('DC', meta_type)
            for item in meta_list:
                if isinstance(item, tuple):
                    results.append(item[0])
                else:
                    results.append(str(item))
        except:
            pass
        return results

    def _extract_cover(self, book) -> Optional[bytes]:
        try:
            cover_items = list(book.get_items_of_type(ebooklib.ITEM_COVER))
            if cover_items:
                return cover_items[0].get_content()
            for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
                if 'cover' in item.get_name().lower():
                    return item.get_content()
        except:
            pass
        return None

    def _extract_all_images(self, book) -> Dict[str, bytes]:
        images = {}
        for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
            name = item.get_name().lower()
            if 'cover' not in name:
                flat_name = Path(item.file_name).name
                images[flat_name] = item.get_content()
        return images

    def _get_flat_toc_links(self, toc_items):
        links = []
        for item in toc_items:
            if isinstance(item, epub.Link):
                links.append(item)
            elif isinstance(item, (list, tuple)):
                links.extend(self._get_flat_toc_links(item))
            elif hasattr(item, 'children'):
                links.extend(self._get_flat_toc_links(item.children))
        return links

    def _parse_content(self, book) -> Dict[int, Dict[Any, str]]:
        if not book.toc:
            return self._parse_content_linear(book)

        content_map = {item.file_name: item.get_content() for item in book.get_items_of_type(ebooklib.ITEM_DOCUMENT)}
        volumes = {}
        flat_links = self._get_flat_toc_links(book.toc)
        current_vol = 1
        chapter_counter = 1

        for link in flat_links:
            href = link.href.split('#')[0]
            title = link.title

            vol_match = re.search(r'(?:том|volume)\s*(\d+)', title, re.IGNORECASE)
            if vol_match: current_vol = int(vol_match.group(1))

            chap_match = re.search(r'(?:глава|chapter)\s*(\d+(?:\.\d+)?)', title, re.IGNORECASE)
            if chap_match:
                c_str = chap_match.group(1)
                current_chap = str(int(c_str)) if '.' not in c_str else c_str
            else:
                current_chap = str(chapter_counter)
                chapter_counter += 1

            if href in content_map:
                raw_html = content_map[href].decode('utf-8') if isinstance(content_map[href], bytes) else content_map[
                    href]
                soup = BeautifulSoup(raw_html, 'html.parser')

                body = soup.find('body') or soup

                for img in body.find_all('img'):
                    src = img.get('src')
                    if src:
                        flat_name = Path(src).name
                        img['src'] = f"../images/{flat_name}"

                text = md(str(body), heading_style="ATX", strip=['a', 'script', 'style'])
                text = clean_raw_text(text)

                if not text: continue

                if title and not text.lstrip().startswith('#'):
                    text = f"# {title}\n\n{text}"

                if current_vol not in volumes: volumes[current_vol] = {}
                if current_chap in volumes[current_vol]:
                    volumes[current_vol][current_chap] += f"\n\n{text}"
                else:
                    volumes[current_vol][current_chap] = text

        return volumes

    def _parse_content_linear(self, book):
        volumes = {1: {}}
        chap_count = 1
        for item in book.get_items_of_type(ebooklib.ITEM_DOCUMENT):
            raw_html = item.get_content().decode('utf-8') if isinstance(item.get_content(),
                                                                        bytes) else item.get_content()
            soup = BeautifulSoup(raw_html, 'html.parser')
            body = soup.find('body') or soup

            for img in body.find_all('img'):
                src = img.get('src')
                if src:
                    flat_name = Path(src).name
                    img['src'] = f"../images/{flat_name}"

            text = md(str(body), heading_style="ATX", strip=['a', 'script', 'style'])
            text = re.sub(r'\n{3,}', '\n\n', text).strip()

            if len(text) > 100:
                volumes[1][chap_count] = text
                chap_count += 1
        return volumes


class TxtParser(BookParser):
    def parse(self, file_path: Path) -> Tuple[
        Dict[int, Dict[Any, str]], Dict[str, Any], Optional[bytes], Dict[str, bytes]]:
        full_text = file_path.read_text(encoding='utf-8')

        meta = {
            "source": "txt_import",
            "tags": [],
            "description": "",
            "language": "ru"
        }

        pattern = re.compile(
            r'^\s*(?=.*(?:том|volume|глава|chapter))(?:(том|volume)\s*(\d+))?\s*(?:(глава|chapter)\s*(\d+(?:\.\d+)?))?\s*$',
            re.IGNORECASE | re.MULTILINE
        )
        headers = list(pattern.finditer(full_text))
        volumes = {}

        if not headers:
            return {1: {"1": full_text}}, meta, None, {}

        content_splits = [full_text[h.end():(headers[i + 1].start() if i + 1 < len(headers) else None)]
                          for i, h in enumerate(headers)]

        prologue = full_text[:headers[0].start()].strip()
        if prologue:
            content_splits[0] = f"ВВЕДЕНИЕ:\n{prologue}\n\n{content_splits[0]}"

        current_vol = 1
        for i, match in enumerate(headers):
            _, vol_num, _, chap_num = match.groups()
            if vol_num: current_vol = int(vol_num)
            if chap_num:
                c_str = chap_num
                c_num = str(int(c_str)) if '.' not in c_str else c_str
            else:
                c_num = str(i + 1)

            text = content_splits[i].strip()
            if text:
                if current_vol not in volumes: volumes[current_vol] = {}
                volumes[current_vol][c_num] = text

        return volumes, meta, None, {}


class ZipParser(BookParser):
    def parse(self, file_path: Path) -> Tuple[
        Dict[int, Dict[Any, str]], Dict[str, Any], Optional[bytes], Dict[str, bytes]]:
        volumes = {}
        meta = {"source": "zip_import", "tags": [], "language": "ru"}
        cover_bytes = None
        images_dict = {}

        with zipfile.ZipFile(file_path, 'r') as zf:
            file_names = zf.namelist()

            meta_file = next((f for f in file_names if f.endswith('metadata.json')), None)
            if meta_file:
                try:
                    data = json.loads(zf.read(meta_file).decode('utf-8'))
                    meta.update(data)
                except Exception as e:
                    print(f"Ошибка чтения metadata.json: {e}")

            for name in file_names:
                if 'cover' in name.lower() and name.endswith(('.jpg', '.png', '.jpeg')):
                    cover_bytes = zf.read(name)
                    continue

                if name.endswith(('.jpg', '.png', '.jpeg', '.webp', '.gif')):
                    flat_name = Path(name).name
                    images_dict[flat_name] = zf.read(name)

            for name in file_names:
                if not name.endswith(('.txt', '.md')) or 'info.txt' in name:
                    continue

                path_obj = Path(name)
                vol_part = next((p for p in path_obj.parts if 'vol_' in p), None)
                chap_part = path_obj.name

                if vol_part and chap_part:
                    v_match = re.search(r'vol_(\d+)', vol_part)
                    c_match = re.search(r'chapter_(\d+(?:\.\d+)?)', chap_part)
                    if not c_match:
                        c_match = re.search(r'(\d+(?:\.\d+)?)', chap_part)

                    if v_match and c_match:
                        vol = int(v_match.group(1))
                        chap_str = c_match.group(1)
                        chap = str(int(chap_str)) if '.' not in chap_str else chap_str

                        text = zf.read(name).decode('utf-8', errors='replace')

                        if vol not in volumes: volumes[vol] = {}
                        volumes[vol][chap] = text

        return volumes, meta, cover_bytes, images_dict


class FolderParser(BookParser):
    """Парсер для каталогов со структурой vol_*/chapter_*.txt или .md (например выгрузка RanobeLib)."""
    def parse(self, folder_path: Path) -> Tuple[
        Dict[int, Dict[Any, str]], Dict[str, Any], Optional[bytes], Dict[str, bytes]]:
        volumes = {}
        meta = {"source": "folder_import", "tags": [], "language": "ru"}
        cover_bytes = None
        images_dict = {}

        meta_file = folder_path / "metadata.json"
        if meta_file.exists():
            try:
                data = json.loads(meta_file.read_text(encoding='utf-8'))
                meta.update(data)
            except Exception as e:
                print(f"Ошибка чтения metadata.json: {e}")

        for p in folder_path.rglob("*"):
            if not p.is_file():
                continue
            if 'cover' in p.name.lower() and p.suffix.lower() in ('.jpg', '.png', '.jpeg'):
                cover_bytes = p.read_bytes()
            elif p.suffix.lower() in ('.jpg', '.png', '.jpeg', '.webp', '.gif'):
                images_dict[p.name] = p.read_bytes()

        for p in folder_path.rglob("*"):
            if not p.is_file() or p.name == "info.txt" or p.suffix.lower() not in ('.txt', '.md'):
                continue

            vol_part = next((part for part in p.parts if 'vol_' in part), None)
            if not vol_part:
                continue

            v_match = re.search(r'vol_(\d+)', vol_part)
            c_match = re.search(r'chapter_(\d+(?:\.\d+)?)', p.name)
            if not c_match:
                c_match = re.search(r'(\d+(?:\.\d+)?)', p.name)

            if v_match and c_match:
                vol = int(v_match.group(1))
                chap_str = c_match.group(1)
                chap = str(int(chap_str)) if '.' not in chap_str else chap_str

                text = p.read_text(encoding='utf-8', errors='replace')
                if vol not in volumes:
                    volumes[vol] = {}
                volumes[vol][chap] = text

        return volumes, meta, cover_bytes, images_dict