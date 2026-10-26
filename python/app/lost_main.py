"""GPU search entry point for the native Mac MPS process."""
from contextlib import asynccontextmanager, nullcontext
import os
import anyio
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from app.routers.lost_search import router
from app.routers.recommendation import router as recommendation_router
from app.services.dinov3 import get_encoder, gallery_index, visual_profile


def validate_focus_gallery(encoder):
    from app.services.lost_storage import get_postgresql_store
    from app.services.coat_color import VERSION as COLOR_VERSION, ranking_weight
    get_postgresql_store().validate(gallery_index(), encoder.model_version,
                                    COLOR_VERSION if ranking_weight() else None)


@asynccontextmanager
async def lifespan(app):
    if not os.getenv("INTERNAL_API_KEY"):
        raise RuntimeError("Internal authentication must be configured")
    gallery_index()
    from app.services.coat_color import ranking_weight
    if ranking_weight() and visual_profile() != "sam3-animal-focus":
        raise RuntimeError("Coat-color ranking requires the SAM 3 gallery")
    enabled = os.getenv("LOST_GALLERY_SYNC_ENABLED", "false").lower() == "true"
    state_dir = os.getenv("LOST_GALLERY_STATE_DIR")
    if enabled and (visual_profile() != "sam3-animal-focus" or not state_dir):
        raise RuntimeError("Automatic gallery refresh requires SAM 3 and a persistent state directory")
    from app.services.gallery_runtime import runtime_owner, GalleryRefresh
    from app.services.gallery_source import GallerySource
    from app.services.gallery_pages import PagedGallerySource
    protocol = os.getenv('LOST_GALLERY_PROTOCOL', 'v1')
    if protocol not in {'v1', 'v2'}:
        raise RuntimeError('Gallery protocol must be v1 or v2')
    source_class = PagedGallerySource if protocol == 'v2' else GallerySource
    from app.services.lost_storage import storage_session
    from app.services.pg_gallery_store import ColorGalleryRefreshRequired, GalleryUnavailable
    if visual_profile() != 'sam3-animal-focus':
        raise RuntimeError('PostgreSQL gallery requires the SAM 3 profile')
    # Acquire ownership before loading weights; CLI and API must use the same state directory.
    with storage_session() as store, (runtime_owner(state_dir) if state_dir else nullcontext()):
        encoder = await anyio.to_thread.run_sync(get_encoder)
        ready = True
        try:
            await anyio.to_thread.run_sync(validate_focus_gallery, encoder)
        except (ColorGalleryRefreshRequired, GalleryUnavailable):
            if not enabled:
                raise
            ready = False
        refresh = None
        if enabled:
            source = source_class(os.environ["LOST_GALLERY_SOURCE_URL"],
                                   os.environ["LOST_GALLERY_SOURCE_KEY"],
                                   os.environ["LOST_GALLERY_R2_HOST"], state_dir,
                                   os.environ["LOST_GALLERY_PHOTO_ROOT"])
            try:
                refresh = GalleryRefresh(store, encoder, source, gallery_index(), state_dir,
                                         interval=int(os.getenv("LOST_GALLERY_INTERVAL_SECONDS", "900")), ready=ready)
                app.state.gallery_refresh = refresh
                refresh.start()
            except BaseException:
                source.close()
                raise
        try:
            yield
        finally:
            if refresh:
                await anyio.to_thread.run_sync(refresh.close)


app = FastAPI(title="PawBridge Lost Animal Search", lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(router, prefix="/internal/animals")
app.include_router(recommendation_router, prefix="/internal/animals")


@app.get("/health")
def health(request: Request):
    refresh = getattr(request.app.state, "gallery_refresh", None)
    ready = refresh is None or refresh.ready
    return JSONResponse({"status": "ok" if ready else "initializing",
                         "modelVersion": get_encoder().model_version,
                         "galleryRefresh": refresh.status if refresh else {"state": "disabled"}},
                        status_code=200 if ready else 503)
