from typing import List, Optional, Dict, Any
from sqlmodel import SQLModel, Field, Column, JSON
from sqlalchemy.ext.mutable import MutableList
import uuid

class AmbientTrack(SQLModel, table=True):
    id: str = Field(primary_key=True)
    description: str
    tags: List[str] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))

class SFXTrack(SQLModel, table=True):
    id: str = Field(primary_key=True)
    description: str
    tags: List[str] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))

class PronunciationEntry(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    word: str = Field(index=True, unique=True)
    phonemes: str

class SystemSetting(SQLModel, table=True):
    key: str = Field(primary_key=True)
    value_json: str

    @property
    def value(self) -> Any:
        import json
        return json.loads(self.value_json)

    @value.setter
    def value(self, val: Any):
        import json
        self.value_json = json.dumps(val)
