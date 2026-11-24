import argparse
import io
import json
import logging
import random
import re
import time
import uuid
import hashlib
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from concurrent.futures import ThreadPoolExecutor

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

try:
    from ebooklib import epub
    from bs4 import BeautifulSoup

    EBOOKLIB_AVAILABLE = True
except ImportError:
    EBOOKLIB_AVAILABLE = False
    print("⚠️ ebooklib и/или beautifulsoup4 не установлены - epub-вывод будет недоступен")
    print("   pip install ebooklib beautifulsoup4 lxml")

try:
    from PIL import Image, UnidentifiedImageError

    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False
    print("⚠️ Pillow не установлен - картинки будут встраиваться БЕЗ сжатия (возможен огромный размер EPUB)")
    print("   pip install Pillow")

try:
    from tqdm import tqdm

    TQDM_AVAILABLE = True
except ImportError:
    TQDM_AVAILABLE = False
    print("⚠️ tqdm не установлен - красивого прогресс-бара не будет")
    print("   pip install tqdm")

import config

logger = logging.getLogger(__name__)

API_TIMEOUT = (3.0, 15.0)
MEDIA_TIMEOUT = (5.0, 30.0)

MAX_RETRIES = 5
DELAY_BASE = 1.8
DELAY_JITTER = 0.9

DEFAULT_USER_AGENT = (
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
    '(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'
)

RANOBE_API_FIELDS = [
    "background", "eng_name", "otherNames", "summary", "releaseDate",
    "type_id", "caution", "views", "close_view", "rate_avg", "rate",
    "genres", "tags", "teams", "user", "franchise", "authors", "publisher",
    "userRating", "moderated", "metadata", "metadata.count",
    "metadata.close_comments", "manga_status_id", "chap_count",
    "status_id", "artists", "format"
]

EPUB_DEFAULT_CSS = """
body { font-family: serif; line-height: 1.7; margin: 5% 8%; }
h1, h2 { text-align: center; margin: 1.5em 0; }
p { margin: 0.8em 0; text-indent: 1.2em; }
img { max-width: 100%; height: auto; display: block; margin: 1.5em auto; }
"""


class RanobeLibLoader:
    """
    Класс для загрузки ранобэ с ranobelib.me в формат txt или epub.
    """

    def __init__(self, debug_requests: bool = False, verify_ssl: bool = True):
        self.api_url = getattr(config, 'RANOBELIB_API_BASE_URL', 'https://api.lib.social/api/manga')
        self.img_url = getattr(config, 'RANOBELIB_IMAGE_BASE_URL', 'https://cover.imglib.info')
        self.headers = getattr(config, 'RANOBELIB_HEADERS', {}).copy()

        user_token = getattr(config, 'RANOBELIB_USER_TOKEN', None)
        if user_token:
            self.headers['Authorization'] = f"Bearer {user_token}"
        else:
            logger.warning("⚠️ RANOBELIB_USER_TOKEN не найден. Доступ к API может быть ограничен.")

        self.debug_requests = debug_requests
        self.verify_ssl = verify_ssl

        self.max_image_width = 1200
        self.image_quality = 75

        self.session = requests.Session()
        self.session.headers.update(self.headers)

        retries = Retry(
            total=MAX_RETRIES,
            backoff_factor=DELAY_BASE,
            status_forcelist=[429, 500, 502, 503, 504],
            allowed_methods=["GET", "POST"]
        )
        adapter = HTTPAdapter(max_retries=retries)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)

        logger.info("RanobeLibLoader инициализирован.")

    def _reset_state(self):
        self.epub_book = None
        self.chapter_items = []
        self.volumes = {}
        self.embedded_images = {}
        self.output_format = "txt"

    def _debug_log_request(self, method: str, url: str, response: Optional[requests.Response] = None, **kwargs):
        if not self.debug_requests:
            return

        print(f"\n[DEBUG REQUEST] {method} {url}")
        print("Headers:", json.dumps(dict(self.session.headers), indent=2, ensure_ascii=False))

        if 'data' in kwargs or 'json' in kwargs:
            body = kwargs.get('data') or kwargs.get('json')
            if isinstance(body, dict):
                print("Body:", json.dumps(body, indent=2, ensure_ascii=False))
            else:
                print("Body:", body)

        if response:
            print(f"[DEBUG RESPONSE] Status: {response.status_code}")
            print("Response Headers:", dict(response.headers))
            try:
                content = response.json()
                print("Response JSON:", json.dumps(content, indent=2, ensure_ascii=False))
            except json.JSONDecodeError:
                print("Response Text (first 1000 chars):", response.text[:1000])

    def run(self, url: str, output_dir: Optional[Path] = None, download_images: bool = False,
            max_volume: Optional[int] = None, output_format: str = "txt"):

        self._reset_state()
        self.output_format = output_format.lower()

        if self.output_format == "epub" and not EBOOKLIB_AVAILABLE:
            logger.error("Невозможно создать EPUB: ebooklib не установлен")
            return

        full_id, numeric_id = self._extract_ids(url)
        if not full_id:
            logger.error(f"Некорректный URL: {url}")
            return

        manga_info = self._fetch_ranobe_data(full_id)
        if not manga_info:
            return

        branches = self._fetch_branches(numeric_id)
        branch_id = self._select_best_branch(branches)

        if not branch_id:
            logger.error("Не удалось определить ветку перевода.")
            return

        ranobe_title = self._format_label(manga_info)
        slug = manga_info.get('slug', full_id)
        folder_name = f"{slug} [{numeric_id}]"
        folder_name = re.sub(r'[<>:"/\\|?*]', '_', folder_name)

        base_path = output_dir if output_dir else getattr(config, 'INPUT_DIR', Path("downloads"))
        save_dir = base_path / folder_name
        save_dir.mkdir(parents=True, exist_ok=True)

        self.cache_dir = save_dir / ".cache"
        self.chapters_cache_dir = self.cache_dir / "chapters"
        self.images_cache_dir = self.cache_dir / "images"
        self.chapters_cache_dir.mkdir(parents=True, exist_ok=True)
        self.images_cache_dir.mkdir(parents=True, exist_ok=True)

        logger.info(f"📁 Директория сохранения: {save_dir}")

        cover_filename = self._download_cover(manga_info, save_dir)
        self._save_metadata(save_dir, manga_info, url, cover_filename)

        chapters = self._fetch_chapters(full_id)
        if not chapters:
            logger.warning("Главы не найдены.")
            return

        if max_volume:
            chapters = [c for c in chapters if int(c.get('volume', 0)) <= max_volume]
            logger.info(f"Фильтр включен: загрузка до {max_volume} тома.")

        total = len(chapters)
        logger.info(f"Найдено глав для загрузки: {total}")

        if self.output_format == "epub":
            self._init_epub_book(manga_info, url, cover_filename, save_dir)

        if TQDM_AVAILABLE:
            chapter_iterator = tqdm(chapters, desc="Скачивание", unit="гл",
                                    bar_format="{l_bar}{bar}| {n_fmt}/{total_fmt} [{elapsed}<{remaining}]")
        else:
            chapter_iterator = chapters

        with ThreadPoolExecutor(max_workers=5) as executor:
            for i, ch in enumerate(chapter_iterator):
                vol = ch.get('volume', '1')
                num = ch.get('number', '0')
                ch_name = ch.get('name', '').strip()

                ch_display = f"Том {vol} Глава {num}"
                if ch_name:
                    ch_display += f" - {ch_name}"

                if TQDM_AVAILABLE:
                    short_display = (ch_display[:40] + '..') if len(ch_display) > 40 else ch_display
                    chapter_iterator.set_postfix_str(short_display)
                elif i == 0 or (i + 1) % 5 == 0 or i == total - 1:
                    logger.info(f"--> Обработка: {ch_display} ({i + 1}/{total})")

                try:
                    safe_ch_name = re.sub(r'[<>:"/\\|?*]', '_',
                                          ch_name[:80]) if ch_name else f"ch_{num.replace('.', '_')}"

                    if self.output_format == "txt":
                        vol_path = save_dir / f"vol_{str(vol).zfill(2)}"
                        file_path = vol_path / f"{num.replace('.', '_')} — {safe_ch_name}.txt"
                        if file_path.exists() and file_path.stat().st_size > 0:
                            continue
                        vol_path.mkdir(exist_ok=True)

                    cache_file = self.chapters_cache_dir / f"v{vol}_c{num}.json"
                    data = None

                    if cache_file.exists():
                        try:
                            data = json.loads(cache_file.read_text(encoding='utf-8'))
                        except json.JSONDecodeError:
                            pass

                    if not data:
                        data = self._fetch_chapter_content(full_id, vol, num, branch_id)
                        if data:
                            cache_file.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
                            time.sleep(DELAY_BASE + random.uniform(0, DELAY_JITTER))

                    if not data:
                        logger.warning(f"Пустой контент: Том {vol} Глава {num}")
                        continue

                    text = self._parse_content_node(data.get('content'), data.get('attachments', []), download_images)
                    chapter_url = f"https://ranobelib.me/ru/{manga_info.get('slug_url') or full_id}/read/v{vol}/c{num}?bid={branch_id}"

                    if self.output_format == "epub":
                        self._add_chapter_to_epub(text, vol, num, ch_display, chapter_url, executor)
                    else:
                        content_to_write = f"{ranobe_title}\n{ch_display}\n\n{text}"
                        file_path.write_text(content_to_write, encoding='utf-8')

                except Exception as e:
                    logger.error(f"Ошибка при обработке {vol}-{num}: {e}")

        if self.output_format == "epub":
            self._finalize_epub(save_dir, ranobe_title)

        logger.info("🎉 Загрузка ранобэ завершена!")

    def _make_request(self, method: str, url: str, is_media: bool = False, **kwargs) -> Optional[requests.Response]:
        timeout = MEDIA_TIMEOUT if is_media else API_TIMEOUT
        try:
            response = self.session.request(
                method, url, verify=self.verify_ssl, timeout=timeout, **kwargs
            )
            self._debug_log_request(method, url, response, **kwargs)
            response.raise_for_status()
            return response
        except requests.exceptions.RequestException as e:
            self._debug_log_request(method, url, getattr(e, 'response', None), **kwargs)
            logger.error(f"Ошибка сети [{method} {url}]: {e}")
            return None

    def _extract_ids(self, url: str) -> Tuple[Optional[str], Optional[str]]:
        match = re.search(r'ranobelib\.me/(?:ru/book/)?((\d+)--[\w\-]+)', url)
        if match:
            return match.group(1), match.group(2)

        match_simple = re.search(r'ranobelib\.me/(?:ru/book/)?([\w\-]+)', url)
        if match_simple:
            full_id = match_simple.group(1)
            numeric_id_match = re.match(r'^\d+', full_id)
            numeric_id = numeric_id_match.group(0) if numeric_id_match else None
            return full_id, numeric_id
        return None, None

    def _fetch_ranobe_data(self, full_id: str) -> Optional[Dict]:
        url = f"{self.api_url}/manga/{full_id}"
        params = [("fields[]", field) for field in RANOBE_API_FIELDS]

        resp = self._make_request("GET", url, params=params)
        if resp:
            data = resp.json().get('data')
            if data:
                return data

        logger.warning("Запрос с fields[] провалился - пробуем минимальный")
        resp = self._make_request("GET", url)
        return resp.json().get('data') if resp else None

    def _fetch_branches(self, numeric_id: str) -> List[Dict]:
        url = f"{self.api_url}/branches/{numeric_id}?team_defaults=1"
        resp = self._make_request("GET", url)
        return resp.json().get('data', []) if resp else []

    def _select_best_branch(self, branches: List[Dict]) -> Optional[int]:
        if not branches:
            return None
        selected = branches[0]
        team_name = selected['teams'][0].get('name') if selected.get('teams') else "Unknown"
        logger.info(f"Выбрана команда: {team_name} (ID: {selected.get('id')})")
        return selected.get('id')

    def _fetch_chapters(self, full_id: str) -> List[Dict]:
        url = f"{self.api_url}/manga/{full_id}/chapters"
        resp = self._make_request("GET", url)
        if not resp:
            return []

        chapters = resp.json().get('data', [])
        chapters.sort(key=lambda x: x.get('index', 0))

        for ch in chapters:
            ch_name = ch.get('name', '').strip()
            if not ch_name and ch.get('number'):
                ch_name = f"Глава {ch.get('number')}"
            ch['display_name'] = ch_name

        return chapters

    def _fetch_chapter_content(self, full_id: str, volume: str, number: str, branch_id: int) -> Optional[Dict]:
        url = f"{self.api_url}/manga/{full_id}/chapter"
        params = {'volume': volume, 'number': number, 'branch_id': branch_id}
        resp = self._make_request("GET", url, params=params)
        return resp.json().get('data', {}) if resp else None

    def _parse_content_node(self, content_node: Any, attachments: List[Dict], include_images: bool) -> str:
        if not isinstance(content_node, dict): return ""

        image_map = {}
        for att in attachments:
            img_url = att.get('url', '')
            if img_url.startswith('/uploads/'):
                img_url = f"https://ranobelib.me{img_url}"
            elif not img_url.startswith('http'):
                img_url = f"{self.img_url}{img_url}"
            image_map[att['name']] = img_url

        text_parts = []

        def _extract(node):
            if isinstance(node, list):
                for item in node: _extract(item)
                return
            if not isinstance(node, dict): return

            t = node.get('type')

            if include_images and t == 'image':
                try:
                    imgs = node.get('attrs', {}).get('images', [])
                    if imgs and (img_id := imgs[0].get('image')) in image_map:
                        text_parts.append(f"\n[Image: {image_map[img_id]}]\n")
                except Exception as exc:
                    logger.debug(f"Ошибка при разборе тега изображения: {exc}")

            if t == 'text' and 'text' in node:
                text_parts.append(node['text'])

            if t == 'hardBreak':
                text_parts.append('\n')

            if 'content' in node and isinstance(node['content'], list):
                for child in node['content']: _extract(child)
                if t in ('paragraph', 'div'):
                    text_parts.append('\n\n')

        _extract(content_node)
        return re.sub(r'\n{3,}', '\n\n', "".join(text_parts)).strip()

    def _optimize_image_data(self, image_data: bytes) -> Tuple[bytes, str]:
        if not PIL_AVAILABLE:
            return image_data, 'jpg'

        try:
            img = Image.open(io.BytesIO(image_data))

            if img.mode in ('RGBA', 'P', 'LA'):
                background = Image.new('RGB', img.size, (255, 255, 255))
                if img.mode == 'RGBA':
                    background.paste(img, mask=img.split()[3])
                else:
                    background.paste(img)
                img = background
            elif img.mode != 'RGB':
                img = img.convert('RGB')

            if img.width > self.max_image_width:
                ratio = self.max_image_width / float(img.width)
                new_height = int((float(img.height) * float(ratio)))
                img = img.resize((self.max_image_width, new_height), Image.Resampling.LANCZOS)

            out_io = io.BytesIO()
            img.save(out_io, format='JPEG', quality=self.image_quality, optimize=True)
            return out_io.getvalue(), 'jpg'
        except (OSError, IOError, UnidentifiedImageError) as e:
            if not TQDM_AVAILABLE:
                logger.warning(f"      ⚠️ Не удалось оптимизировать картинку, используем оригинал: {e}")
            return image_data, 'jpg'

    def _get_image_headers(self, referer: str = 'https://ranobelib.me/') -> dict:
        headers = self.headers.copy()
        headers.update({
            'User-Agent': DEFAULT_USER_AGENT,
            'Referer': referer,
            'sec-ch-ua': '"Chromium";v="140", "Not=A?Brand";v="24", "Google Chrome";v="140"',
            'sec-ch-ua-mobile': '?0',
            'sec-ch-ua-platform': '"Windows"',
            'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8'
        })
        return headers

    def _download_cover(self, manga_info: Dict, folder_path: Path) -> Optional[str]:
        cover_obj = manga_info.get('cover') or manga_info.get('background') or {}
        url = cover_obj.get('default') if isinstance(cover_obj, dict) else cover_obj

        if not url: return None
        if not url.startswith('http'): url = f"{self.img_url}{url}"

        try:
            resp = self._make_request("GET", url, is_media=True, headers=self._get_image_headers())
            if not resp: return None

            image_data = resp.content
            ext = 'jpg'

            if PIL_AVAILABLE:
                image_data, ext = self._optimize_image_data(image_data)
            else:
                if 'png' in resp.headers.get('content-type', '') or url.endswith('.png'):
                    ext = 'png'

            filename = f"cover.{ext}"
            (folder_path / filename).write_bytes(image_data)
            return filename
        except requests.exceptions.RequestException as e:
            logger.warning(f"Не удалось скачать обложку: {e}")
            return None

    def _save_metadata(self, folder_path: Path, manga_info: Dict, source_url: str, cover_filename: Optional[str]):
        authors = [p.get('name', '').strip() for p in manga_info.get('authors', []) if p.get('name')]
        artists = [p.get('name', '').strip() for p in manga_info.get('artists', []) if p.get('name')]

        meta = {
            "title": self._format_label(manga_info),
            "rus_name": manga_info.get('rus_name'),
            "eng_name": manga_info.get('eng_name'),
            "alternative_titles": manga_info.get('otherNames', []),
            "author": ", ".join(authors) if authors else "Unknown",
            "artist": ", ".join(artists) if artists else "Unknown",
            "description": manga_info.get('summary', '').strip(),
            "source_url": source_url,
            "type": manga_info.get('type', {}).get('label'),
            "status": manga_info.get('status', {}).get('label'),
            "scanlate_status": manga_info.get('scanlateStatus', {}).get('label'),
            "year": manga_info.get('releaseDate'),
            "release_date_string": manga_info.get('releaseDateString'),
            "chapters_uploaded": manga_info.get('items_count', {}).get('uploaded'),
            "franchise": manga_info.get('franchise', [{}])[0].get('name'),
            "genres": [g.get('name') for g in manga_info.get('genres', [])],
            "tags": [t.get('name') for t in manga_info.get('tags', [])],
            "age_restriction": manga_info.get('ageRestriction', {}).get('label'),
            "publishers": [p.get('name') for p in manga_info.get('publisher', [])],
            "cover_image": cover_filename,
            "language": "ru",
            "rating": manga_info.get('rating', {}).get('average'),
            "views": manga_info.get('views', {}).get('total')
        }

        meta = {k: v for k, v in meta.items() if v is not None}

        meta_path = folder_path / 'metadata.json'
        with open(meta_path, 'w', encoding='utf-8') as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)

    def _format_label(self, data: Dict) -> str:
        return (data.get('rus_name') or data.get('name') or data.get('eng_name') or 'unknown_ranobe').strip()

    def _init_epub_book(self, manga_info: Dict, source_url: str, cover_filename: Optional[str], save_dir: Path):
        self.epub_book = epub.EpubBook()
        self.epub_book.set_identifier(f"ranobelib-{uuid.uuid4().hex[:12]}")
        title = self._format_label(manga_info)
        self.epub_book.set_title(title)
        self.epub_book.set_language("ru")

        authors_str = ", ".join(p.get('name', '') for p in manga_info.get('authors', [])) or "Неизвестный автор"
        self.epub_book.add_author(authors_str)
        self.epub_book.add_metadata('DC', 'creator', authors_str)

        if summary := manga_info.get('summary'):
            self.epub_book.add_metadata('DC', 'description', summary)

        self.epub_book.add_metadata('DC', 'publisher', 'RanobeLib')
        self.epub_book.add_metadata('DC', 'source', source_url)

        if year := manga_info.get('releaseDate'):
            self.epub_book.add_metadata('DC', 'date', str(year))

        for g in manga_info.get('genres', []):
            if name := g.get('name'):
                self.epub_book.add_metadata('DC', 'subject', name)

        if cover_filename and (cover_path := save_dir / cover_filename).exists():
            with open(cover_path, 'rb') as f:
                self.epub_book.set_cover(f"images/cover.{cover_filename.split('.')[-1]}", f.read(), create_page=True)

        css = epub.EpubItem(
            uid="style_default",
            file_name="style.css",
            media_type="text/css",
            content=EPUB_DEFAULT_CSS.encode("utf-8")
        )
        self.epub_book.add_item(css)

    def _download_image_task(self, url: str, referer: str) -> Optional[Tuple[str, str, Any]]:
        if url in self.embedded_images:
            return None

        img_hash = hashlib.md5(url.encode('utf-8')).hexdigest()

        cached_data = None
        cached_ext = 'jpg'
        for ext in ['jpg', 'png', 'webp', 'gif']:
            p = self.images_cache_dir / f"{img_hash}.{ext}"
            if p.exists():
                cached_data = p.read_bytes()
                cached_ext = ext
                break

        try:
            if cached_data:
                image_data = cached_data
                ext = cached_ext
            else:
                resp = self._make_request("GET", url, is_media=True, headers=self._get_image_headers(referer))
                if resp and resp.status_code == 200:
                    image_data = resp.content
                    ext = 'jpg'

                    if PIL_AVAILABLE:
                        image_data, ext = self._optimize_image_data(image_data)
                    else:
                        content_type = resp.headers.get('content-type', '').lower()
                        if 'png' in content_type or url.lower().endswith('.png'):
                            ext = 'png'
                        elif 'webp' in content_type or url.lower().endswith('.webp'):
                            ext = 'webp'
                        elif 'gif' in content_type or url.lower().endswith('.gif'):
                            ext = 'gif'

                    (self.images_cache_dir / f"{img_hash}.{ext}").write_bytes(image_data)
                else:
                    if not TQDM_AVAILABLE and resp:
                        logger.warning(f"       ⚠️ Не удалось скачать иллюстрацию (код {resp.status_code})")
                    return None

            img_id = img_hash[:10]
            file_name = f"images/img_{img_id}.{ext}"

            img_item = epub.EpubItem(
                uid=f"image_{img_id}",
                file_name=file_name,
                media_type=f"image/{'jpeg' if ext == 'jpg' else ext}",
                content=image_data
            )
            return url, file_name, img_item
        except requests.exceptions.RequestException as e:
            if not TQDM_AVAILABLE:
                logger.warning(f"      ⚠️ Ошибка скачивания иллюстрации: {e}")

        return None

    def _add_chapter_to_epub(self, text: str, vol: str, num: str, chapter_title: str, chapter_url: str,
                             executor: ThreadPoolExecutor):
        image_urls = list(set(re.findall(r'\[Image:\s*(.*?)\s*\]', text)))
        image_urls = [u for u in image_urls if u not in self.embedded_images]

        if image_urls:
            results = executor.map(lambda u: self._download_image_task(u, chapter_url), image_urls)
            for res in results:
                if res:
                    url, file_name, img_item = res
                    self.epub_book.add_item(img_item)
                    self.embedded_images[url] = file_name

        file_name = f"content/vol_{vol.zfill(2)}_ch_{num.replace('.', '_')}.xhtml"
        c = epub.EpubHtml(title=chapter_title, file_name=file_name, lang="ru")
        c.add_link(href="../style.css", rel="stylesheet", type="text/css")
        c.content = self._text_to_xhtml(chapter_title, text).encode("utf-8")

        self.epub_book.add_item(c)
        self.chapter_items.append(c)

        if vol not in self.volumes:
            self.volumes[vol] = []
        self.volumes[vol].append(c)

    def _text_to_xhtml(self, chapter_title: str, text: str) -> str:
        soup = BeautifulSoup(
            '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ru" lang="ru">'
            '<head><meta charset="utf-8"/><title></title>'
            '<link rel="stylesheet" href="../style.css" type="text/css"/></head>'
            '<body></body></html>',
            "lxml"
        )

        soup.title.string = chapter_title

        body = soup.body
        h1 = soup.new_tag("h1")
        h1.string = chapter_title
        body.append(h1)

        for para in [p.strip() for p in re.split(r'\n\s*\n', text.strip()) if p.strip()]:
            p = soup.new_tag("p")
            if "[Image:" in para:
                parts = re.split(r'(\[Image:.*?\])', para)
                for part in parts:
                    if part.startswith("[Image:") and part.endswith("]"):
                        url = part[7:-1].strip()
                        img_src = f"../{self.embedded_images[url]}" if url in self.embedded_images else url
                        img = soup.new_tag("img", src=img_src, alt="Иллюстрация")
                        p.append(img)
                    elif part.strip():
                        p.append(part)
            else:
                p.string = para
            body.append(p)

        html_content = str(soup)
        return f'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n{html_content}'

    def _finalize_epub(self, save_dir: Path, title: str):
        if not self.epub_book or not self.chapter_items:
            return

        toc = []
        for vol_num, chapters in self.volumes.items():
            vol_title = f"Том {vol_num}"
            toc.append((epub.Section(vol_title), tuple(chapters)))

        self.epub_book.toc = tuple(toc)

        nav = epub.EpubNav()
        nav.file_name = 'nav.xhtml'
        nav.title = 'Содержание'
        nav.add_link(href='style.css', rel='stylesheet', type='text/css')
        self.epub_book.add_item(nav)
        self.epub_book.add_item(epub.EpubNcx())

        spine = ['cover'] if self.epub_book.get_item_with_id('cover') else []
        spine += [nav] + self.chapter_items
        self.epub_book.spine = spine

        safe_title = re.sub(r'[^\w\s-]', '', title).strip().replace(' ', '_')
        path = save_dir / f"{safe_title}.epub"
        epub.write_epub(str(path), self.epub_book)
        logger.info(f"EPUB сохранён: {path}")


if __name__ == "__main__":
    import sys
    from utils.setup_logging import setup_logging

    setup_logging()

    parser = argparse.ArgumentParser(description="RanobeLib downloader (txt / epub)")
    parser.add_argument("url", nargs="?", help="URL тайтла")
    parser.add_argument("--format", choices=["txt", "epub"], default="txt")
    parser.add_argument("--images", action="store_true", help="Скачивать иллюстрации и встраивать их прямо в EPUB")
    parser.add_argument("--max-volume", type=int)
    parser.add_argument("--debug", "--debug-requests", action="store_true", help="Показывать все запросы и ответы")
    parser.add_argument("--insecure", action="store_true",
                        help="Отключить проверку SSL сертификатов (полезно при проблемах с провайдером)")

    args = parser.parse_args()

    if not args.url:
        try:
            args.url = input("Введите URL тайтла: ").strip()
        except KeyboardInterrupt:
            sys.exit(0)

    if args.url:
        print("--- RanobeLib Pipeline Loader ---")
        loader = RanobeLibLoader(
            debug_requests=args.debug,
            verify_ssl=not args.insecure
        )

        loader.run(
            url=args.url,
            download_images=args.images,
            max_volume=args.max_volume,
            output_format=args.format
        )