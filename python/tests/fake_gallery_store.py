"""In-memory stand-in for PostgresqlGalleryStore, limited to the full-build calls of build_gallery
and the retention/refresh calls of GalleryRefresh. Signatures and return shapes follow
app/gallery/pg_store.py; SQL transactions are replaced by dictionaries.
"""
from copy import deepcopy

from app.gallery.builder import CONTRACT, PREFIX, gallery_mapping
from app.vision.sam3_focus import FOCUS_VERSION
import re


class FakeGalleryStore:
    def __init__(self):
        self.builds = {}   # build_key -> {'metadata', 'expected_count', 'completed', 'documents': {id: document}}
        self.heads = {}    # alias -> build_key

    def seed(self, build_key, documents, metadata=None, expected_count=None, completed=True, alias=None):
        """Test setup helper: store an existing build and optionally make it the head of an alias."""
        self.builds[build_key] = {
            'metadata': deepcopy(metadata if metadata is not None else gallery_mapping('b' * 64)['_meta']),
            'expected_count': len(documents) if expected_count is None else expected_count,
            'completed': completed,
            'documents': {document['id']: deepcopy(document) for document in documents}}
        if alias is not None:
            self.heads[alias] = build_key

    @staticmethod
    def names(alias, target):
        if (not re.fullmatch(PREFIX + r'[a-z0-9][a-z0-9-]{0,60}', alias) or '-build-' in alias
                or not re.fullmatch(PREFIX + r'build-[a-f0-9]{24}', target)):
            raise ValueError('Invalid gallery identity')

    def build_target(self, target, snapshot_hash, total):
        return target

    def begin(self, alias, target, snapshot_hash, total):
        self.names(alias, target)
        meta = gallery_mapping(snapshot_hash)['_meta']
        old = self.heads.setdefault(alias, None)
        build = self.builds.setdefault(target, {'metadata': deepcopy(meta), 'expected_count': total,
                                                'completed': False, 'documents': {}})
        if (build['metadata'], build['expected_count']) != (meta, total):
            raise RuntimeError('Existing staging gallery has a different contract')
        if old:
            previous = self.builds.get(old)
            if not previous or previous['metadata'].get('contract') != CONTRACT or not previous['completed']:
                raise RuntimeError('Refusing to replace an unowned or incomplete gallery')
        return old

    @staticmethod
    def result_index(target):
        return target

    def completed(self, target, total):
        build = self.builds.get(target)
        return bool(build) and (build['completed'], build['expected_count']) == (True, total)

    def count(self, target):
        return len(self.builds[target]['documents']) if target in self.builds else 0

    def documents(self, old_index, target, ids):
        if not 1 <= len(ids) <= 100:
            raise ValueError('Gallery reads must be bounded to 100 animals')
        wanted = {int(i) for i in ids}
        cache = {}
        for source in dict.fromkeys(x for x in (old_index, target) if x):
            for animal_id, document in self.builds.get(source, {'documents': {}})['documents'].items():
                if animal_id in wanted:
                    cache[animal_id] = deepcopy(document)
        return cache

    def write_page(self, target, documents):
        if not 1 <= len(documents) <= 100:
            raise ValueError('Gallery writes must be bounded to 100 animals')
        build = self.builds.get(target)
        if not build or build['completed']:
            raise RuntimeError('Published galleries are immutable')
        if any(d['model_version'] != build['metadata']['model_version'] or
               d.get('coat_color_version') != build['metadata']['coat_color_version'] for d in documents):
            raise ValueError('Gallery feature versions differ from the build')
        for document in documents:
            build['documents'][document['id']] = deepcopy(document)

    def publish(self, alias, target, old_index, total, check_cancelled):
        self.names(alias, target)
        if self.heads.get(alias) != old_index:
            raise RuntimeError('Gallery head changed during the build')
        build = self.builds[target]
        count = len(build['documents'])
        if build['expected_count'] != total or count != total:
            raise RuntimeError('Staging gallery count differs from the complete snapshot')
        check_cancelled()
        build['completed'] = True
        self.heads[alias] = target
        return count

    def published(self, result):
        build = self.builds.get(self.heads.get(result['alias']))
        return bool(build) and (
            self.heads[result['alias']], build['metadata'], build['expected_count'], build['completed'],
            len(build['documents'])) == (result['index'], gallery_mapping(result['snapshot_sha256'])['_meta'],
                                         result['records'], True, result['records'])

    def delete_unpublished(self, target, *, check_cancelled=None):
        if check_cancelled is not None:
            check_cancelled()
        build = self.builds.get(target)
        if not build:
            return True
        if build['metadata'].get('contract') != CONTRACT or build['metadata'].get('model_version') != FOCUS_VERSION:
            raise RuntimeError('Refusing to delete an unowned gallery')
        if target in self.heads.values():
            return False
        del self.builds[target]
        return True
