"""
Централизованный модуль для управления и форматирования всех промптов.
Использует XML-структурирование для четкого разделения контекста и текста.
"""
import json
from typing import List, Dict, Optional

from core.data_models import (
    CharacterArchive,
    CharacterReconResult,
    LlmReconResult,
    LlmCharacterPatchList,
    CharacterPatchList,
    EmotionMap, RawChapterSummary, ChapterSummary, LlmRawScenario
)
from utils.prompt_utils import generate_human_schema


def format_volume_summary_prompt(volume_num: int, chapter_summaries: List[str]) -> str:
    """
    Промпт для создания 'Рекапа' целого тома на основе пересказов его глав.
    """
    joined_summaries = "\n\n".join(chapter_summaries)

    return f"""
ТЫ — ГЛАВНЫЙ РЕДАКТОР КНИЖНОЙ СЕРИИ.
Мы закончили обработку ТОМА {volume_num}.

ТВОЯ ЗАДАЧА:
Напиши **ГЛОБАЛЬНОЕ САММАРИ (Recap)** для всего этого тома.
Твой текст будет использоваться как контекст для следующего тома, чтобы ИИ не забыл, что произошло.

ИНСТРУКЦИЯ:
1. Прочитай краткие содержания всех глав ниже.
2. Выдели основные сюжетные арки, ключевые поворотные моменты и итог тома.
3. Игнорируй мелкие детали ("попил чаю"), фокусируйся на глобальных изменениях (кто умер, кто влюбился, куда уехали, как изменился мир).
4. Объем: 300-500 слов.

ПЕРЕСКАЗЫ ГЛАВ ТОМА {volume_num}:
{joined_summaries}

ТВОЙ ОТВЕТ (ТОЛЬКО ТЕКСТ САММАРИ):
"""


def format_summary_generation_prompt(
        chapter_text: str,
        chapter_id: str,
        previous_summaries: list[ChapterSummary],
        prev_volume_summary: Optional[str] = None
) -> str:
    """Формирует промпт для генерации пересказа главы."""
    schema_description = generate_human_schema(RawChapterSummary)

    global_context_block = ""
    if prev_volume_summary:
        global_context_block = f"""
ГЛОБАЛЬНЫЙ КОНТЕКСТ (СОБЫТИЯ ПРЕДЫДУЩЕГО ТОМА)
В прошлом томе произошло следующее:
{prev_volume_summary}
"""

    previous_context_str = ""
    if previous_summaries:
        context_lines = ["КОНТЕКСТ ПРЕДЫДУЩИХ ГЛАВ (ДЛЯ СВЯЗНОСТИ):"]
        for summary in previous_summaries:
            context_lines.append(f"Глава {summary.chapter_id}:\n{summary.synopsis}\n")
        previous_context_str = "\n".join(context_lines)

    return f"""
ТЫ — ОПЫТНЫЙ ЛИТЕРАТУРНЫЙ РЕДАКТОР.

ТВОЯ ЗАДАЧА:
Прочитай "Текст главы" и создай для него ДВА типа пересказа: "тизер" и "конспект".
УЧИТЫВАЙ КОНТЕКСТ ПРЕДЫДУЩИХ ГЛАВ, если он предоставлен.

{global_context_block}

{previous_context_str}

**!!! ВАЖНЫЕ ПРАВИЛА БЕЗОПАСНОСТИ !!!**
**- Избегай прямого упоминания и детального описания сцен насилия или сексуального контента.**
**- Используй нейтральные и литературные формулировки.**

ФОРМАТ ОТВЕТА (JSON):
{schema_description}

ТЕКСТ ГЛАВЫ (ID: {chapter_id}):
{chapter_text}

ТВОЙ ОТВЕТ (ТОЛЬКО JSON):
"""


# --- ПРОМПТЫ ДЛЯ АНАЛИЗА ПЕРСОНАЖЕЙ ---
def format_character_recon_prompt(chapter_text: str, known_characters_json: str,
                                  chapter_summary: Optional[str] = None) -> str:
    """
    Промпт для 'умной разведки'.
    Теперь включает контекст саммари для устранения неоднозначностей.
    """
    schema_description = generate_human_schema(LlmReconResult)

    summary_block = ""
    if chapter_summary:
        summary_block = f"""
=== КОНТЕКСТ ГЛАВЫ ===
Используй это краткое содержание как "истину" для разрешения споров о именах.
Если в тексте написано "Служанка", а в саммари сказано, что это Маомао - используй имя "Маомао".
САММАРИ:
{chapter_summary}
"""

    return f"""
Твоя задача - провести "разведку" персонажей в тексте главы.

ИНСТРУКЦИЯ:
1.  Изучи `СПИСОК ИЗВЕСТНЫХ ПЕРСОНАЖЕЙ`.
2.  Внимательно прочитай `ТЕКСТ ГЛАВЫ`.
3.  Сопоставь упоминания в тексте с персонажами из списка.
4.  Определи персонажей, которых нет в списке.

{summary_block}

ПРАВИЛА:
-   В `mentioned_existing_refs` пиши только `ref` персонажей из списка, без изменений. Не придумывай новые `ref`.
-   В `newly_discovered_names` включай только тех, кого точно нет.
-   Игнорируй общие понятия ("девушка", "солдат"), если они не являются важными действующими лицами согласно Саммари.

ФОРМАТ ОТВЕТА (JSON):
{schema_description}

СПИСОК ИЗВЕСТНЫХ ПЕРСОНАЖЕЙ:
{known_characters_json}

ТЕКСТ ГЛАВЫ:
{chapter_text}

ТВОЙ ОТВЕТ (ТОЛЬКО JSON):
"""


def format_character_patch_prompt(
        characters_to_update_json: str,
        new_candidates: List[str],
        directory_json: str,
        chapter_text: str,
        volume: int,
        chapter: int
) -> str:
    """
    Промпт патча персонажей: модель возвращает ТОЛЬКО изменения, которые внесла глава.
    Персонажи адресуются короткими `ref` (не UUID): модели их не нужно копировать посимвольно.
    Порядок блоков: статичные правила -> данные книги -> текст главы (для кэша провайдера).
    """
    schema_description = generate_human_schema(LlmCharacterPatchList)

    return f"""
<role>
Ты ведешь базу персонажей книги и обновляешь ее по мере чтения, глава за главой.
Верни ТОЛЬКО изменения: то, что эта глава добавила к уже известному о персонажах.
</role>

<what_to_return>
Ответ — один JSON-объект {{"patches": [...]}}. Патч создается ТОЛЬКО для:
1. Персонажей из <characters_to_update>. Укажи их `ref` в точности как во входных данных.
2. Кандидатов из <new_candidates>:
   - если кандидат — уже известный персонаж под другим именем, прозвищем или титулом
     (он есть в <characters_to_update> или <directory>), верни патч с `ref` этого персонажа
     и добавь имя кандидата в `aliases`;
   - иначе это новый персонаж: `ref` не пиши.
Не создавай патчи для тех, кого нет в этих списках. Не придумывай `ref`.
Если у персонажа из <characters_to_update> в этой главе ничего не изменилось, верни патч только с `ref` и `current_chapter_action`.
</what_to_return>

<who_is_a_character>
- `person`: люди и человекоподобные существа.
- `animal`: животные и монстры, только если есть кличка или активное участие в сюжете.
- НЕ персонажи (патч не создавай): предметы, яды, письма, организации, абстракции,
  безликие группы ("Толпа", "Солдаты"), даже если они важны для событий.
</who_is_a_character>

<naming>
`name` — каноническое имя персонажа. Приоритет:
1. Имя собственное или кличка ("Хоннян", "Гарри").
2. Титул + имя ("Доктор Ли", "Супруга Гёкуё").
3. Роль ("Доктор", "Служанка") — только если имени в тексте нет.
- Узнали настоящее имя — можно повысить уровень ("Командующая Кацураги" -> "Мисато"); старое имя автоматически станет алиасом.
- Понижать нельзя: если персонажа "Хоннян" весь текст называют "Доктор", имя остается "Хоннян".
- Безымянную массовку, которая не действует в сцене, не создавай.
</naming>

<fields>
Пропускай ключ, если по нему нечего сказать. Не пиши null.
- `current_chapter_action`: одно предложение — что персонаж делает в этой главе. Обязательно в каждом патче.
- `aliases`: только НОВЫЕ имена, прозвища и титулы, впервые появившиеся в этой главе.
- `description_update`: полное актуальное описание персонажа на русском, 2-4 предложения:
  кто он, роль в сюжете, характер, отношения с другими. Это синтез `description` из входных
  данных и новых фактов этой главы. Пиши ТОЛЬКО если глава добавила о персонаже что-то
  существенное. Только то, что читатель знает к концу этой главы: не додумывай будущее.
  Для нового персонажа — обязательно.
- `role_tier`: 'protagonist' | 'major' | 'minor' | 'background'.
- `entity_type`, `gender`: для новых персонажей.
- `visual_base`: только если у персонажа оно пустое. Постоянные физические черты на английском
  (волосы, глаза, возраст, телосложение, приметы): "1girl, young adult, pale skin, long red hair, green eyes, freckles".
- `voice_profile`: для НОВОГО персонажа обязательно; для известного — только если у него пусто.
  3-5 тегов на английском: пол и возраст, тембр, манера ("young female, calm, soft voice").
  Если голос в тексте не описан, выведи из возраста, характера и роли.
- `timeline_visual_update`: только если в этой главе внешность ИЗМЕНИЛАСЬ или впервые описана
  (одежда, ранение, прическа). `description` — на русском. `image_prompt` — на английском по формуле
  visual_base + одежда/состояние + окружение; без слов 'realistic', 'anime', '4k'.
  Чувствительные роли заменяй визуальными синонимами (Slave -> Servant).
- `timeline_voice_update`: только если голос физически изменился (повзрослел, охрип, болеет).
</fields>

<output_format>
{schema_description}
</output_format>

<characters_to_update>
{characters_to_update_json}
</characters_to_update>

<new_candidates>
{json.dumps(new_candidates, ensure_ascii=False)}
</new_candidates>

<directory>
Остальные известные персонажи — только для сверки кандидатов. Их не обновляй, кроме случая,
когда кандидат оказался одним из них.
{directory_json}
</directory>

<chapter_text volume="{volume}" chapter="{chapter}">
{chapter_text}
</chapter_text>

ТВОЙ ОТВЕТ: ровно один JSON-объект вида {{"patches": [ ... ]}}.
"""

# --- ПРОМПТЫ ДЛЯ ГЕНЕРАЦИИ СЦЕНАРИЯ ---
def format_scenario_generation_prompt(
        text_chunk: str,
        character_archive: CharacterArchive,
        chapter_summary: Optional[str] = None,
        chunk_index: int = 0,
        total_chunks: int = 1,
        previous_context: str = "",
        character_profiles: Optional[Dict[str, str]] = None
) -> str:
    """
    Формирует промпт для генерации "сырого" сценария главы (или её части).
    `character_profiles` — имя -> описание на момент главы. Если не задано, берутся все
    персонажи архива с первым описанием (старое поведение).
    Порядок блоков: сначала статичные правила (одинаковые для всех вызовов — попадают
    в кэш провайдера), затем данные книги, в самом конце текст текущего чанка.
    """
    schema_description = generate_human_schema(LlmRawScenario)

    if character_profiles is None:
        character_profiles = {char.name: char.spoiler_free_description for char in character_archive.characters}
    character_profiles_block = "\n".join(f"- {name}: {desc}" for name, desc in character_profiles.items())

    summary_block = ""
    if chapter_summary:
        summary_block = f"""
<global_context>
КОНСПЕКТ ГЛАВЫ:
{chapter_summary}
</global_context>
"""

    context_info = ""
    if total_chunks > 1:
        context_info = f"""
<chunk_info>
ВНИМАНИЕ: Это ЧАСТЬ {chunk_index + 1} из {total_chunks} всей главы. Не пытайся завершить сюжет, если текст обрывается — просто обработай то, что видишь.
</chunk_info>
"""

    previous_block = ""
    if previous_context:
        previous_block = f"""
<previous_context>
Конец предыдущей части главы — ТОЛЬКО для понимания, кто говорит и что происходит.
НЕ включай этот текст в сценарий: он уже обработан.
{previous_context}
</previous_context>
"""

    few_shot_example = """
=== ПРИМЕР ПРАВИЛЬНОГО ДРОБЛЕНИЯ (ОБЯЗАТЕЛЬНО К ИСПОЛНЕНИЮ) ===
ИСХОДНЫЙ ТЕКСТ (Один абзац): 
"Она скрестила руки и поворчала. Романтика её не интересовала. Доставив корзину с бельём, она вышла к красному лакированному зданию центральной зоны. Резьба была повсюду, каждая деталь была продумана."

ПЛОХО (Слияние всего в один блок `narration` - ИГРА СЛОМАЕТСЯ):
{"scenario": [
  {"type": "narration", "text": "Она скрестила руки и поворчала. Романтика её не интересовала. Доставив корзину с бельём, она вышла к красному лакированному зданию центральной зоны. Резьба была повсюду, каждая деталь была продумана."}
]}

ОТЛИЧНО (Разделение на 3 логичных блока - ТАК НУЖНО ДЕЛАТЬ ТЕБЕ):
{"scenario": [
  {"type": "narration", "text": "Она скрестила руки и поворчала. Романтика её не интересовала."},
  {"type": "narration", "text": "Доставив корзину с бельём, она вышла к красному лакированному зданию центральной зоны."},
  {"type": "narration", "text": "Резьба была повсюду, каждая деталь была продумана."}
]}
"""

    return f"""
<role>
ТЫ — ИИ-РЕЖИССЕР, который превращает текст книги в детализированный сценарий для визуальной аудио-новеллы.
Твоя задача — прочитать текст и скрупулезно преобразовать его в список записей сценария внутри одного JSON-объекта `{{"scenario": [...]}}`.
</role>

<scenario_rules>
1.  **Типы записей (`type`):**
    -   `narration`: Текст, который читает Рассказчик.
    -   `dialogue`: Прямая речь (слова, произнесенные вслух).
    -   `thought`: Внутренние мысли и монологи персонажа (часто выделены курсивом `*текст*` или мысленными кавычками).
    -   `image`: Иллюстрация. Используй ТОЛЬКО если в тексте встречается маркдаун-тег картинки в формате `![описание](путь/к/файлу)`.

2.  **Заполнение полей в зависимости от типа (`type`):**
    -   **Для `dialogue` и `thought`:** `speaker` — это каноническое имя персонажа. `text` — очищенная реплика (слова автора удаляются, но маркдаун вроде `*` сохраняется).
    -   **Для `narration`:** только `type` и `text`. Ключ `speaker` не пиши — рассказчик подставляется автоматически.
    -   **Чтение писем и записок:** Если в тексте цитируется письмо или надпись, которую герой читает про себя, это `narration` или `thought`. НЕ делай это прямой речью автора письма.
    -   **Для `image`:** Обязательно извлеки ссылку из круглых скобок маркдауна и запиши её в поле `src` (например: `../images/img1.jpg`). Только `type` и `src`, без `text` и `speaker`.

3.  **Обработка неизвестных говорящих:**
    -   Если говорящего невозможно определить (реплика из толпы), используй краткую роль (например, "Голос из толпы", "Служанка").

4.  **Разделение для смены сцен (КРИТИЧНО):**
    -   Если персонаж совершает физический переход (например, выходит из восточной зоны в центральную), ты **ОБЯЗАН** разорвать абзац так, чтобы прибытие в новую локацию оказалось в отдельном, следующем блоке `narration`. Иначе игровой движок не сможет сменить фон!

</scenario_rules>

<example>
{few_shot_example}
</example>

<json_cleaning>
!!! ПРАВИЛО ЭКОНОМИИ ТОКЕНОВ !!!
- Не выводи поля со значением `null` — просто пропускай ключ.
- Ключ `src` пиши только для `image`.
- Ключ `speaker` пиши только для `dialogue` и `thought`.
</json_cleaning>

<output_format>
{schema_description}
</output_format>

<character_profiles>
{character_profiles_block}
</character_profiles>
{summary_block}{context_info}{previous_block}
<text_to_process>
{text_chunk}
</text_to_process>

ТВОЙ ОТВЕТ: ровно один JSON-объект вида {{"scenario": [ ... ]}}.
Каждая запись — отдельный элемент массива `scenario`. Обработай ВЕСЬ текст до последнего абзаца, ничего не пропуская.
"""


def format_sound_design_prompt(
        scenario_json: str,
        ambient_menu: str,
        sfx_menu: str,
        schema_description: str
) -> str:
    return f"""
<role>
ТЫ — ЗВУКОРЕЖИССЕР АУДИОКНИГИ. Твоя задача — выбрать фоновые шумы и эффекты строго из предоставленных меню.
</role>

<instructions>
Для каждой записи сценария (`entry_id`) определи:
1. `ambient`: Фоновый звук локации. Анализируй текст. Если локация или атмосфера изменились (герой вышел на улицу, началась драка) — ОБЯЗАТЕЛЬНО смени ambient на подходящий. Не копируй один и тот же звук на все 100 строк! Если нужна тишина - выбери "none".
2. `sfx`: Звуковой эффект. Назначай ТОЛЬКО при прямом совпадении действия.
</instructions>

<sfx_strict_rules>
- Принцип белого списка: Используй только ID из меню.
- Запрет на фантазию: НЕ притягивай звуки за уши. Если герой "доел суп" — это НЕ звук наливания жидкости `pour_liquid`. Если герой просто повернулся — это НЕ `footsteps`. Звук должен быть буквальным (например, "он ударил кулаком по столу" -> `punch_hit`).
- Если подходящего звука нет, оставь поле пустым.
</sfx_strict_rules>

<json_cleaning>
Если `sfx` или `ambient` отсутствуют или равны 'none', просто НЕ ВЫВОДИ этот ключ в JSON-объекте для конкретного `entry_id`.
</json_cleaning>

<ambient_menu>
{ambient_menu}
</ambient_menu>

<sfx_menu>
{sfx_menu}
</sfx_menu>

<scenario>
{scenario_json}
</scenario>

<output_format>
{schema_description}
</output_format>

ТВОЙ ОТВЕТ (ТОЛЬКО JSON):
"""


def format_emotion_analysis_prompt(
        replicas: List[Dict],
        character_profiles: Dict
) -> str:
    schema_description = generate_human_schema(EmotionMap)
    character_profiles_json = json.dumps(character_profiles, ensure_ascii=False, indent=2)
    replicas_scenario_json = json.dumps(replicas, ensure_ascii=False, indent=2)

    return f"""
<role>
ТЫ — РЕЖИССЕР АУДИОКНИГИ. Для каждой реплики выбери эмоцию из закрытого списка и, где нужно, отметь длинные паузы.
</role>

<emotion_rules>
- Поле `emotion` — РОВНО одна метка: neutral, angry, sad, happy, whisper, scared.
  neutral — спокойная речь, вопросы, объяснения (большинство реплик); angry — гнев, крик, раздражение;
  sad — печаль, усталость, горечь; happy — радость, смех, восторг; whisper — шепот, тихая доверительная речь;
  scared — страх, паника, растерянность.
- Ставь не-neutral, только если эмоция явно следует из текста или ремарки автора. Сомневаешься — neutral.
- Реплики с меткой neutral и без пауз НЕ ВЫВОДИ вовсе.
</emotion_rules>

<pause_rules>
- Поле `tts_text` — тот же текст реплики, слово в слово, но с ПУСТОЙ СТРОКОЙ (\n\n) там, где нужна длинная пауза:
  на многоточии посреди фразы, перед резкой сменой мысли, в заминке.
- Обычные точки и запятые паузы не требуют. Пауза нужна не в каждой реплике.
- НИКАКИХ тегов и пометок в тексте (<|pause|>, [вздох] и т.п.): синтезатор прочитает их вслух.
- Пауза не нужна — не выводи `tts_text`.
Пример: "Ну не знаю... Я подумаю." -> `tts_text`: "Ну не знаю...\n\nЯ подумаю."
</pause_rules>

<character_profiles>
{character_profiles_json}
</character_profiles>

<scenario_input>
{replicas_scenario_json}
</scenario_input>

<output_format>
{schema_description}
</output_format>

ТВОЙ ОТВЕТ (ТОЛЬКО JSON):
"""


def format_speaker_resolution_prompt(chapter_id: str, chapter_text: str, synopsis: Optional[str],
                                     speakers: List[Dict], characters: List[Dict]) -> str:
    """Кто стоит за подписями говорящих, которых сценарий не связал с персонажами."""
    from core.data_models import LlmSpeakerResolutionList
    schema_description = generate_human_schema(LlmSpeakerResolutionList)
    return f"""
<role>
ТЫ — РЕДАКТОР АУДИОКНИГИ. В сценарии главы {chapter_id} есть реплики, у которых говорящий подписан ролью
или описанием («Евнух», «Служанка 1», «Незнакомец»), а не именем. Для каждой такой подписи определи, кто это:
известный персонаж книги или массовка. От этого зависит, каким голосом звучит реплика.
</role>

<rules>
- `kind: "character"` — только если текст главы и описания персонажей дают явные доводы (внешность, должность,
  действия, продолжение сцены в соседних главах, обращение по имени). `character` — имя ровно как в списке.
  Персонаж может быть не назван в этой главе: рассказчик или героиня еще не знают его имени, но читатель узнает по описанию.
- Сомневаешься — `kind: "extra"`. Ложная связь хуже пропущенной: чужой голос у массовки заметнее, чем голос массовки у персонажа.
- `identity_hidden: true` — если автор НАМЕРЕННО скрывает, кто это (тайна, маскировка, раскрытие позже).
  Связь все равно укажи, если она следует из описаний, но с этим флагом.
- `confidence`: high — несколько независимых доводов; medium — один сильный довод; low — догадка.
- `evidence` — 1-2 коротких довода с опорой на текст.
- Для `extra` обязательно `gender` и `age_group` по тексту (служанки обычно молодые женщины, «старик» — old).
- `person_key` — одинаковый у подписей, за которыми один и тот же человек («Евнух» и «Красивый евнух» в одной сцене),
  и разный у разных людей («Служанка 1» и «Служанка 2» — два человека). Для `character` — имя персонажа.
- Верни ровно по одному объекту на КАЖДУЮ подпись из <speakers>. Новых персонажей не придумывай.
- Описания персонажей могут содержать сведения из более поздних глав — используй их только для опознания.
</rules>

<synopsis>
{synopsis or "(нет)"}
</synopsis>

<characters>
{json.dumps(characters, ensure_ascii=False, indent=1)}
</characters>

<speakers>
{json.dumps(speakers, ensure_ascii=False, indent=1)}
</speakers>

<chapter_text>
{chapter_text}
</chapter_text>

<output_format>
{schema_description}
</output_format>

ТВОЙ ОТВЕТ (ТОЛЬКО JSON):
"""


def format_casting_prompt(candidates: List[Dict], cast: List[Dict]) -> str:
    """Кастинг: базовый голос (без эмоций) для персонажей с репликами."""
    from core.data_models import LlmVoiceCastList
    schema_description = generate_human_schema(LlmVoiceCastList)
    return f"""
<role>
ТЫ — РЕЖИССЕР КАСТИНГА АУДИОКНИГИ. Подбери каждому персонажу из <candidates> базовый голос: каким он звучит
в спокойной речи. По описанию будет сгенерирован образец голоса, которым персонаж озвучивается всю книгу.
</role>

<rules>
- Голос БАЗОВЫЙ: без эмоций момента (гнев, слезы, болезнь), только кто говорит.
- `age` — числом, по тексту; если не сказан — правдоподобная оценка по роли и описанию. Молодая взрослая
  героиня 17-20 лет звучит как взрослая девушка, а не как ребенок.
- `pitch` — относительно нормы для пола и возраста. Не делай всех женщин высокими, а всех мужчин низкими.
- Персонажи одного пола и возраста должны различаться: высотой, тембром, темпом или манерой. Смотри на весь
  <cast>, включая уже подобранные голоса; опиши в `distinct_from`, чем голос отличается от похожих.
- `timbre`, `manner`, `distinct_from` — по-английски, коротко. `instruct_zh` — полное описание голоса на китайском.
- Никаких имен реальных актеров и персонажей других произведений.
- Верни ровно по одному объекту на каждого персонажа из <candidates>.
</rules>

<cast>
{json.dumps(cast, ensure_ascii=False, indent=1)}
</cast>

<candidates>
{json.dumps(candidates, ensure_ascii=False, indent=1)}
</candidates>

<output_format>
{schema_description}
</output_format>

ТВОЙ ОТВЕТ (ТОЛЬКО JSON):
"""
