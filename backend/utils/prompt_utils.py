from typing import Type, get_origin, get_args, Union, Literal
from enum import Enum
import inspect
from pydantic import BaseModel

def generate_human_schema(model: Type[BaseModel], indent: int = 0, exclude: frozenset = frozenset()) -> str:
    """
    Рекурсивно генерирует простое, человекочитаемое описание Pydantic-модели
    для использования в промптах LLM. exclude — имена полей, которых модели не показываем (на любом уровне).
    """
    lines = []
    prefix = " " * indent

    for field_name, field_info in model.model_fields.items():
        if field_name in exclude:
            continue
        type_desc, nested_models = _describe_type(field_info.annotation)

        description = f"({type_desc})"
        if field_info.description:
            description += f" - {field_info.description}"

        lines.append(f"{prefix}- `{field_name}` {description}")

        for nested in nested_models:
            lines.append(generate_human_schema(nested, indent=indent + 2, exclude=exclude))

    return "\n".join(lines)

def _describe_type(type_obj) -> tuple[str, list[Type[BaseModel]]]:
    """Возвращает описание типа и список вложенных моделей для рекурсии."""
    origin = get_origin(type_obj)
    args = get_args(type_obj)
    nested_models = []

    if inspect.isclass(type_obj) and issubclass(type_obj, BaseModel):
        nested_models.append(type_obj)
        return type_obj.__name__, nested_models

    if inspect.isclass(type_obj) and issubclass(type_obj, Enum):
        return f"Enum[{', '.join([f"'{e.value}'" for e in type_obj])}]", []

    if origin is Literal:
        return f"Literal[{', '.join([f"'{a}'" for a in args])}]", []

    if origin is Union:
        non_none_args = [t for t in args if t is not type(None)]
        descriptions = []
        for arg in non_none_args:
            desc, nested = _describe_type(arg)
            descriptions.append(desc)
            nested_models.extend(nested)

        if len(descriptions) == 1:
            return f"Optional[{descriptions[0]}]", nested_models
        return f"Union[{' | '.join(descriptions)}]", nested_models

    if origin:
        inner_types = []
        for arg in args:
            desc, nested = _describe_type(arg)
            inner_types.append(desc)
            nested_models.extend(nested)

        type_name = getattr(origin, '__name__', str(origin)).capitalize()
        return f"{type_name}[{', '.join(inner_types)}]", nested_models

    return (type_obj.__name__ if hasattr(type_obj, '__name__') else str(type_obj)), []