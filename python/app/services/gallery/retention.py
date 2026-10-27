"""Retain active/previous builds; clean only journaled, unpublished PostgreSQL builds."""
import json
import re
from pathlib import Path

from app.services.gallery.source import atomic_json
from app.services.gallery.builder import PREFIX, gallery_target, GalleryBuildCancelled


class GalleryRetention:
    def __init__(self, store, state_dir, alias, cancelled=None):
        if not re.fullmatch(PREFIX + r'[a-z0-9][a-z0-9-]{0,60}', alias):
            raise ValueError('Invalid retention alias')
        self.store = store
        self.cancelled = cancelled
        # The '-postgresql' suffix predates the Elasticsearch removal; keep it so existing journals are reused.
        self.path = Path(state_dir) / (alias + '-postgresql-retention.json')
        self.journal = {'known': [], 'published': []}
        if self.path.exists():
            if self.path.is_symlink() or self.path.stat().st_size > 16384:
                raise ValueError('Invalid retention journal')
            value = json.loads(self.path.read_text())
            if (set(value) != {'known', 'published'} or any(not isinstance(value[k], list) for k in value)
                    or any(not isinstance(name, str) or not re.fullmatch(PREFIX + r'build-[a-f0-9]{24}', name)
                           for names in value.values() for name in names)
                    or len(value['known']) > 9 or len(value['published']) > 2
                    or not set(value['published']).issubset(value['known'])):
                raise ValueError('Invalid retention journal')
            self.journal = value

    def save(self):
        atomic_json(self.path, self.journal)

    def check_cancelled(self):
        if self.cancelled is not None and self.cancelled():
            raise GalleryBuildCancelled('Gallery retention stopped')

    def prune(self, keep):
        for name in list(self.journal['known']):
            self.check_cancelled()
            if name in keep:
                continue
            if not self.store.delete_unpublished(name, check_cancelled=self.check_cancelled):
                continue
            self.journal['known'].remove(name)
            self.save()

    def prepare(self, snapshot_hash):
        self.prepare_target(gallery_target(snapshot_hash))

    def prepare_target(self, target):
        if not isinstance(target, str) or not re.fullmatch(PREFIX + r'build-[a-f0-9]{24}', target):
            raise ValueError('Invalid retention target')
        self.prune(set(self.journal['published']) | {target})
        if target not in self.journal['known']:
            if len(self.journal['known']) >= 8:
                raise RuntimeError('Gallery retention is blocked by referenced builds')
            self.journal['known'].append(target)
            self.save()  # Record before creation, so interrupted staging can be cleaned next time.

    def published(self, index):
        if index not in self.journal['known']:
            self.journal['known'].append(index)
        self.journal['published'] = [name for name in self.journal['published'] if name != index] + [index]
        self.journal['published'] = self.journal['published'][-2:]
        self.save()
        self.prune(set(self.journal['published']))
