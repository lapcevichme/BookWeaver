from fastapi import APIRouter

from api.projects.dashboard import router as dashboard_router
from api.projects.crud import router as crud_router
from api.projects.chapters import router as chapters_router
from api.projects.knowledge import router as knowledge_router
from api.projects.characters import router as characters_router
from api.projects.metrics import router as metrics_router

router = APIRouter(
    prefix="/api/v1/projects",
    tags=["Projects Management"]
)

router.include_router(dashboard_router)
router.include_router(crud_router)
router.include_router(chapters_router)
router.include_router(knowledge_router)
router.include_router(characters_router)
router.include_router(metrics_router)
