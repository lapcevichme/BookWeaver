import json
import logging
from sqlmodel import Session, select
import config
from core.database import init_system_db, get_system_session
from core.system_models import AmbientTrack, SFXTrack, PronunciationEntry, SystemSetting

logger = logging.getLogger(__name__)

def sync_global_assets():
    """Синхронизирует JSON-файлы из assets/ в системную БД. Источник правды - JSON."""
    init_system_db()
    
    with get_system_session() as session:
        # 1. Ambient
        if config.AMBIENT_LIBRARY_FILE.exists():
            logger.info(f"Syncing Ambient Library from {config.AMBIENT_LIBRARY_FILE.name}...")
            data = json.loads(config.AMBIENT_LIBRARY_FILE.read_text("utf-8"))
            json_ids = set()
            for item in data:
                track = AmbientTrack(
                    id=item['id'],
                    description=item.get('description', ''),
                    tags=item.get('tags', [])
                )
                session.merge(track)
                json_ids.add(item['id'])
            
            # (Опционально) Удаляем из БД то, чего нет в JSON
            db_tracks = session.exec(select(AmbientTrack)).all()
            for t in db_tracks:
                if t.id not in json_ids and t.id != "none":
                    session.delete(t)
        
        # 2. SFX
        if config.SFX_LIBRARY_FILE.exists():
            logger.info("Syncing SFX Library...")
            data = json.loads(config.SFX_LIBRARY_FILE.read_text("utf-8"))
            json_ids = set()
            for sid, desc in data.items():
                track = SFXTrack(id=sid, description=desc)
                session.merge(track)
                json_ids.add(sid)
            
            db_tracks = session.exec(select(SFXTrack)).all()
            for t in db_tracks:
                if t.id not in json_ids:
                    session.delete(t)
        
        # 3. Pronunciation
        if config.PRONUNCIATION_DICT_FILE.exists():
            logger.info("Syncing Pronunciation Dictionary...")
            data = json.loads(config.PRONUNCIATION_DICT_FILE.read_text("utf-8"))
            json_words = set()
            for word, phonemes in data.items():
                existing = session.exec(select(PronunciationEntry).where(PronunciationEntry.word == word)).first()
                if existing:
                    existing.phonemes = phonemes
                    session.add(existing)
                else:
                    entry = PronunciationEntry(word=word, phonemes=phonemes)
                    session.add(entry)
                json_words.add(word)
            
            db_entries = session.exec(select(PronunciationEntry)).all()
            for e in db_entries:
                if e.word not in json_words:
                    session.delete(e)

        # 4. Emotions
        if config.EMOTION_REFERENCE_LIBRARY_FILE.exists():
            logger.info("Syncing Emotion Library...")
            data = config.EMOTION_REFERENCE_LIBRARY_FILE.read_text("utf-8")
            setting = SystemSetting(key="emotion_library", value_json=data)
            session.merge(setting)

        # 5. Role Blacklist
        blacklist_file = config.ASSETS_DIR / "role_blacklist.json"
        if blacklist_file.exists():
            logger.info("Syncing Role Blacklist...")
            data = blacklist_file.read_text("utf-8")
            setting = SystemSetting(key="role_blacklist", value_json=data)
            session.merge(setting)
        
        session.commit()
        logger.info("✅ Global assets synchronization complete.")

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    sync_global_assets()
