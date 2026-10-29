"""PostgreSQL gallery storage; import does not open a pool or contact a database."""
from contextlib import contextmanager
import threading

from app.settings import PostgresSettings

_lock = threading.Lock()
_store = None


def backend():
    return PostgresSettings.load().backend


def get_postgresql_store():
    global _store
    settings = PostgresSettings.load()
    with _lock:
        if _store is None:
            from psycopg_pool import ConnectionPool
            from app.gallery.pg_store import PostgresqlGalleryStore
            if not settings.dsn:
                raise RuntimeError('LOST_PG_DSN is required')
            pool = ConnectionPool(settings.dsn, min_size=0, max_size=settings.pool_max_size, max_waiting=4, timeout=3,
                                  kwargs={'autocommit': True, 'connect_timeout': 3,
                                          'application_name': 'pawbridge-lost-vector'},
                                  max_idle=60, max_lifetime=600, reconnect_timeout=5,
                                  check=ConnectionPool.check_connection, open=False,
                                  name='lost-vector', num_workers=1)
            pool.open()
            _store = PostgresqlGalleryStore(pool)
        return _store


def close_postgresql_store():
    global _store
    with _lock:
        if _store is not None:
            _store.pool.close()
            _store = None


@contextmanager
def storage_session():
    try:
        yield get_postgresql_store()
    finally:
        close_postgresql_store()
