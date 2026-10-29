import os
import types
import unittest
from unittest.mock import MagicMock, Mock, create_autospec, patch
from app.entrypoints.vision_api import validate_focus_gallery
from app.vision.coat_color import VERSION as COLOR_VERSION
from app.gallery.builder import gallery_mapping
from app.gallery.pg_store import ColorGalleryRefreshRequired, GalleryUnavailable, PostgresqlGalleryStore
from app.vision.sam3_focus import FOCUS_VERSION

INDEX = "animals-lost-dinov3-sam3-eval-v1"


def store_with_head(row):
    """A real PostgresqlGalleryStore whose validation query returns row = (metadata, completed, expected, matching count)."""
    connection = MagicMock()
    connection.execute.return_value.fetchone.return_value = row
    pool = MagicMock()
    pool.connection.return_value.__enter__.return_value = connection
    return PostgresqlGalleryStore(pool), connection


class FocusStartupTest(unittest.TestCase):
    def test_only_populated_matching_complete_gallery_can_start(self):
        metadata = gallery_mapping("a" * 64)["_meta"]
        stale_model = dict(metadata, model_version="old")
        encoder = types.SimpleNamespace(model_version=FOCUS_VERSION)
        with patch.dict(os.environ, {"LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                                      "LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}, clear=True):
            for row in [None, (stale_model, True, 200, 200), (metadata, False, 200, 200), (metadata, True, 200, 0),
                        (metadata, True, 200, 199)]:
                store, _ = store_with_head(row)
                with self.subTest(row=row and row[1:]), patch("app.gallery.storage.get_postgresql_store", return_value=store):
                    with self.assertRaises(RuntimeError):
                        validate_focus_gallery(encoder)
            store, connection = store_with_head((metadata, True, 200, 200))
            with patch("app.gallery.storage.get_postgresql_store", return_value=store):
                validate_focus_gallery(encoder)
            self.assertEqual(connection.execute.call_args.args[1], (FOCUS_VERSION, INDEX))

    def test_missing_published_gallery_is_reported_as_unavailable(self):
        store, _ = store_with_head(None)
        with patch.dict(os.environ, {"LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                                      "LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}, clear=True), \
                patch("app.gallery.storage.get_postgresql_store", return_value=store):
            with self.assertRaises(GalleryUnavailable):
                validate_focus_gallery(types.SimpleNamespace(model_version=FOCUS_VERSION))

    def test_sam3_lifespan_checks_gallery_before_accepting_requests(self):
        import asyncio
        from unittest.mock import AsyncMock
        from app.entrypoints.vision_api import lifespan
        async def enter():
            async with lifespan(None):
                self.fail("Invalid SAM 3 gallery must not become ready")
        with patch.dict(os.environ, {"INTERNAL_API_KEY": "test-key", "LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                                      "LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}), \
                patch("app.gallery.storage.get_postgresql_store", return_value=Mock()), \
                patch("app.entrypoints.vision_api.anyio.to_thread.run_sync", new_callable=AsyncMock, side_effect=[object(), RuntimeError("gallery mismatch")]) as run:
            with self.assertRaisesRegex(RuntimeError, "gallery mismatch"):
                asyncio.run(enter())
            self.assertEqual(run.call_count, 2)

    def test_missing_gallery_stops_startup_unless_refresh_can_build_it(self):
        import asyncio
        from unittest.mock import AsyncMock
        from app.entrypoints.vision_api import lifespan
        async def enter():
            async with lifespan(None):
                self.fail("A missing gallery must not become ready when refresh is disabled")
        with patch.dict(os.environ, {"INTERNAL_API_KEY": "test-key", "LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                                      "LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}, clear=True), \
                patch("app.gallery.storage.get_postgresql_store", return_value=Mock()), \
                patch("app.entrypoints.vision_api.anyio.to_thread.run_sync", new_callable=AsyncMock,
                      side_effect=[object(), GalleryUnavailable("No published PostgreSQL gallery")]):
            with self.assertRaises(GalleryUnavailable):
                asyncio.run(enter())

    def test_enabled_sync_rebuilds_a_stale_color_gallery_before_becoming_ready(self):
        import asyncio
        import tempfile
        from contextlib import nullcontext
        from types import SimpleNamespace
        from unittest.mock import AsyncMock
        from app.entrypoints.vision_api import lifespan
        from app.gallery import runtime as gallery_runtime
        app = SimpleNamespace(state=SimpleNamespace())
        refresh = Mock(ready=False)
        source = Mock()
        store = create_autospec(PostgresqlGalleryStore, instance=True)
        environment = {
            "INTERNAL_API_KEY": "test-key",
            "LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus",
            "LOST_SEARCH_INDEX": INDEX,
            "LOST_SEARCH_COAT_COLOR_WEIGHT": ".1",
            "LOST_GALLERY_SYNC_ENABLED": "true",
            "LOST_GALLERY_PROTOCOL": "v2",
            "LOST_GALLERY_SOURCE_URL": "http://127.0.0.1/feed",
            "LOST_GALLERY_SOURCE_KEY": "private",
            "LOST_GALLERY_R2_HOST": "r2.invalid",
            "LOST_GALLERY_PHOTO_ROOT": "/tmp/photos",
        }
        async def enter():
            async with lifespan(app):
                self.assertIs(app.state.gallery_refresh, refresh)
        with tempfile.TemporaryDirectory() as directory:
            environment["LOST_GALLERY_STATE_DIR"] = directory
            with patch.dict(os.environ, environment, clear=True), \
                    patch("app.gallery.storage.get_postgresql_store", return_value=store), \
                    patch("app.entrypoints.vision_api.anyio.to_thread.run_sync", new_callable=AsyncMock,
                          side_effect=[object(), ColorGalleryRefreshRequired("stale color"), None]) as run, \
                    patch.object(gallery_runtime, "runtime_owner", return_value=nullcontext()), \
                    patch.object(gallery_runtime, "GalleryRefresh", return_value=refresh) as factory, \
                    patch("app.gallery.pages.PagedGallerySource", return_value=source):
                asyncio.run(enter())
        self.assertIs(factory.call_args.args[0], store)
        self.assertFalse(factory.call_args.kwargs["ready"])
        refresh.start.assert_called_once_with()
        self.assertEqual(run.call_count, 3)
        self.assertIs(run.call_args_list[-1].args[0], refresh.close)

    def test_color_ranking_requires_new_gallery_contract_but_disabled_mode_can_start_on_old_gallery(self):
        encoder = types.SimpleNamespace(model_version=FOCUS_VERSION)
        for enabled in (False, True):
            store = create_autospec(PostgresqlGalleryStore, instance=True)
            weight = ".12" if enabled else "0"
            environment = {"LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                           "LOST_SEARCH_COAT_COLOR_WEIGHT": weight}
            with self.subTest(enabled=enabled), patch.dict(os.environ, environment, clear=True), \
                    patch("app.gallery.storage.get_postgresql_store", return_value=store):
                validate_focus_gallery(encoder)
            # The store receives the color contract only when color ranking is enabled.
            store.validate.assert_called_once_with(INDEX, FOCUS_VERSION, COLOR_VERSION if enabled else None)

    def test_enabled_color_ranking_rejects_a_gallery_built_before_the_color_contract(self):
        metadata = gallery_mapping("a" * 64)["_meta"]
        metadata.pop("coat_color_version")
        store, _ = store_with_head((metadata, True, 1, 1))
        with patch.dict(os.environ, {"LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                                      "LOST_SEARCH_COAT_COLOR_WEIGHT": ".12"}, clear=True), \
                patch("app.gallery.storage.get_postgresql_store", return_value=store):
            with self.assertRaises(ColorGalleryRefreshRequired):
                validate_focus_gallery(types.SimpleNamespace(model_version=FOCUS_VERSION))

    def test_disabled_color_ranking_accepts_a_gallery_built_before_the_color_contract(self):
        metadata = gallery_mapping("a" * 64)["_meta"]
        metadata.pop("coat_color_version")
        store, _ = store_with_head((metadata, True, 1, 1))
        with patch.dict(os.environ, {"LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_INDEX": INDEX,
                                      "LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}, clear=True), \
                patch("app.gallery.storage.get_postgresql_store", return_value=store):
            validate_focus_gallery(types.SimpleNamespace(model_version=FOCUS_VERSION))
