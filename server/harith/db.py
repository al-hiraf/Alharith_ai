"""قاعدة البيانات: SQLite بوضع WAL مع ترحيلات مرقّمة. كل الجداول مفصولة حسب user_id."""
from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

MIGRATIONS: list[str] = [
    # 1 — البنية الأساسية
    """
    CREATE TABLE users(
        id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'user', display_name TEXT NOT NULL DEFAULT '',
        timezone TEXT NOT NULL DEFAULT 'Asia/Riyadh', disabled INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL);
    CREATE TABLE sessions(token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        created_at TEXT NOT NULL, expires_at TEXT NOT NULL);
    CREATE TABLE api_tokens(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL, created_at TEXT NOT NULL, last_used TEXT);
    CREATE TABLE telegram_links(chat_id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        tg_username TEXT, created_at TEXT NOT NULL);
    CREATE TABLE link_codes(code TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at TEXT NOT NULL);
    CREATE TABLE settings_kv(user_id INTEGER NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
        PRIMARY KEY(user_id, key));

    CREATE TABLE projects(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL, goal TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '',
        status TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
    CREATE TABLE project_entries(id INTEGER PRIMARY KEY, project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
        user_id INTEGER NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, owner TEXT NOT NULL DEFAULT '',
        due_at TEXT, status TEXT NOT NULL DEFAULT 'open', created_at TEXT NOT NULL);

    CREATE TABLE tasks(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
        title TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', priority TEXT NOT NULL DEFAULT 'normal',
        status TEXT NOT NULL DEFAULT 'open', due_at TEXT, scope TEXT NOT NULL DEFAULT '',
        parent_id INTEGER REFERENCES tasks(id) ON DELETE CASCADE, source TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, completed_at TEXT);
    CREATE INDEX tasks_user ON tasks(user_id, status);

    CREATE TABLE reminders(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        text TEXT NOT NULL, due_at TEXT NOT NULL, recur TEXT NOT NULL DEFAULT 'none',
        status TEXT NOT NULL DEFAULT 'pending', task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
        attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, created_at TEXT NOT NULL, sent_at TEXT);
    CREATE INDEX reminders_due ON reminders(status, due_at);

    CREATE TABLE memories(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        kind TEXT NOT NULL DEFAULT 'fact', content TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, expires_at TEXT);
    CREATE VIRTUAL TABLE memories_fts USING fts5(content, content='memories', content_rowid='id');
    CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
        INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content); END;
    CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN
        INSERT INTO memories_fts(memories_fts, rowid, content) VALUES ('delete', old.id, old.content); END;
    CREATE TRIGGER memories_au AFTER UPDATE ON memories BEGIN
        INSERT INTO memories_fts(memories_fts, rowid, content) VALUES ('delete', old.id, old.content);
        INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content); END;

    CREATE TABLE messages(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        channel TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, run_id TEXT, created_at TEXT NOT NULL);
    CREATE INDEX messages_user ON messages(user_id, id);
    CREATE VIRTUAL TABLE messages_fts USING fts5(content, content='messages', content_rowid='id');
    CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
        INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content); END;
    CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
        INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.id, old.content); END;

    CREATE TABLE habits(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL, target_per_week INTEGER NOT NULL DEFAULT 7, archived INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL);
    CREATE TABLE habit_checks(habit_id INTEGER NOT NULL REFERENCES habits(id) ON DELETE CASCADE, day TEXT NOT NULL,
        PRIMARY KEY(habit_id, day));

    CREATE TABLE files(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL, name TEXT NOT NULL, rel_path TEXT NOT NULL,
        size INTEGER NOT NULL, mime TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL);

    CREATE TABLE jobs(id INTEGER PRIMARY KEY, user_id INTEGER, kind TEXT NOT NULL, payload TEXT NOT NULL DEFAULT '{}',
        run_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
        max_attempts INTEGER NOT NULL DEFAULT 3, last_error TEXT, dedupe_key TEXT UNIQUE,
        created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT);
    CREATE INDEX jobs_due ON jobs(status, run_at);

    CREATE TABLE operations(id INTEGER PRIMARY KEY, user_id INTEGER, run_id TEXT, kind TEXT NOT NULL,
        tool TEXT NOT NULL DEFAULT '', args TEXT NOT NULL DEFAULT '', level INTEGER NOT NULL DEFAULT 1,
        status TEXT NOT NULL, result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, finished_at TEXT);
    CREATE INDEX operations_user ON operations(user_id, id);

    CREATE TABLE runs(id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, channel TEXT NOT NULL, input TEXT NOT NULL,
        status TEXT NOT NULL, output TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, finished_at TEXT);

    CREATE TABLE approvals(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        tool TEXT NOT NULL, args TEXT NOT NULL, summary TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
        result TEXT NOT NULL DEFAULT '', channel TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, decided_at TEXT);

    CREATE TABLE usage(id INTEGER PRIMARY KEY, user_id INTEGER, provider TEXT NOT NULL, model TEXT NOT NULL,
        input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
        cost_usd REAL NOT NULL DEFAULT 0, created_at TEXT NOT NULL);

    CREATE TABLE processed_keys(key TEXT PRIMARY KEY, created_at TEXT NOT NULL);
    CREATE TABLE event_log(id INTEGER PRIMARY KEY, level TEXT NOT NULL, source TEXT NOT NULL,
        message TEXT NOT NULL, created_at TEXT NOT NULL);
    """,
    # 2 — مزامنة تطبيق الجوال (العقل المشترك)
    """
    ALTER TABLE tasks ADD COLUMN client_ref TEXT;
    ALTER TABLE memories ADD COLUMN client_ref TEXT;
    CREATE UNIQUE INDEX tasks_client_ref ON tasks(user_id, client_ref);
    CREATE UNIQUE INDEX memories_client_ref ON memories(user_id, client_ref);
    CREATE TABLE deletions(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, kind TEXT NOT NULL, ref TEXT NOT NULL,
        deleted_at TEXT NOT NULL);
    CREATE INDEX deletions_user ON deletions(user_id, deleted_at);
    CREATE TRIGGER tasks_del AFTER DELETE ON tasks BEGIN
        INSERT INTO deletions(user_id, kind, ref, deleted_at)
        VALUES (old.user_id, 'task', COALESCE(old.client_ref, 's' || old.id), strftime('%Y-%m-%dT%H:%M:%SZ','now')); END;
    CREATE TRIGGER memories_del AFTER DELETE ON memories BEGIN
        INSERT INTO deletions(user_id, kind, ref, deleted_at)
        VALUES (old.user_id, 'memory', COALESCE(old.client_ref, 's' || old.id), strftime('%Y-%m-%dT%H:%M:%SZ','now')); END;
    """,
    # 3 — أجهزة المستخدمين ومفاتيحهم الفرعية من OpenRouter
    """
    CREATE TABLE devices(id INTEGER PRIMARY KEY, device_id TEXT UNIQUE NOT NULL, name TEXT NOT NULL DEFAULT '',
        key_hash TEXT, key_label TEXT NOT NULL DEFAULT '', limit_usd REAL NOT NULL DEFAULT 0,
        disabled INTEGER NOT NULL DEFAULT 0, ip TEXT NOT NULL DEFAULT '', app_version TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, last_seen TEXT NOT NULL);
    """,
    # 4 — الأعمال: مساحات الشركات، المالية، الفواتير والعروض، العملاء والموردون، KPI، الاجتماعات، سجل التعديلات
    """
    CREATE TABLE workspaces(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'company', currency TEXT NOT NULL DEFAULT 'SAR',
        vat_rate REAL NOT NULL DEFAULT 15, goals TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
        status TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
    ALTER TABLE projects ADD COLUMN workspace_id INTEGER REFERENCES workspaces(id) ON DELETE SET NULL;
    ALTER TABLE projects ADD COLUMN budget_minor INTEGER;
    CREATE TABLE contacts(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER REFERENCES workspaces(id) ON DELETE SET NULL, kind TEXT NOT NULL DEFAULT 'client',
        name TEXT NOT NULL, company TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '',
        email TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT '', stage TEXT NOT NULL DEFAULT '',
        contract_end TEXT, notes TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'active',
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
    CREATE TABLE invoices(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
        project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
        contact_id INTEGER REFERENCES contacts(id) ON DELETE SET NULL,
        doc_kind TEXT NOT NULL DEFAULT 'invoice', number TEXT NOT NULL, title TEXT NOT NULL DEFAULT '',
        items TEXT NOT NULL DEFAULT '[]', subtotal_minor INTEGER NOT NULL DEFAULT 0, vat_rate REAL NOT NULL DEFAULT 0,
        vat_minor INTEGER NOT NULL DEFAULT 0, total_minor INTEGER NOT NULL DEFAULT 0,
        paid_minor INTEGER NOT NULL DEFAULT 0, currency TEXT NOT NULL DEFAULT 'SAR',
        issued_on TEXT NOT NULL, due_on TEXT, status TEXT NOT NULL DEFAULT 'draft', notes TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(user_id, number));
    CREATE TABLE ledger(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
        project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
        contact_id INTEGER REFERENCES contacts(id) ON DELETE SET NULL,
        invoice_id INTEGER REFERENCES invoices(id) ON DELETE SET NULL,
        kind TEXT NOT NULL CHECK(kind IN ('income','expense')), amount_minor INTEGER NOT NULL CHECK(amount_minor>0),
        currency TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'عام', note TEXT NOT NULL DEFAULT '',
        occurred_on TEXT NOT NULL, source TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL);
    CREATE INDEX ledger_ws_day ON ledger(workspace_id, occurred_on);
    CREATE TABLE budgets(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, category TEXT NOT NULL,
        month TEXT NOT NULL DEFAULT '', amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
        UNIQUE(workspace_id, category, month));
    CREATE TABLE recurring(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, kind TEXT NOT NULL,
        amount_minor INTEGER NOT NULL, currency TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'عام',
        note TEXT NOT NULL DEFAULT '', day_of_month INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1,
        last_alert TEXT, created_at TEXT NOT NULL);
    CREATE TABLE kpis(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
        project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL, name TEXT NOT NULL,
        target REAL, current REAL, unit TEXT NOT NULL DEFAULT '', direction TEXT NOT NULL DEFAULT 'up',
        updated_at TEXT NOT NULL, UNIQUE(workspace_id, name));
    CREATE TABLE meetings(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id INTEGER REFERENCES workspaces(id) ON DELETE SET NULL,
        project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL, title TEXT NOT NULL, starts_at TEXT,
        duration_min INTEGER NOT NULL DEFAULT 60, attendees TEXT NOT NULL DEFAULT '', agenda TEXT NOT NULL DEFAULT '',
        minutes TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'planned', reminder_id INTEGER,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
    CREATE TABLE audit(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, workspace_id INTEGER, entity TEXT NOT NULL,
        entity_id INTEGER, action TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL);
    CREATE INDEX audit_ws ON audit(workspace_id, id);
    """,
    # 5 — رموز ربط تطبيق الجوال (6 أرقام، 10 دقائق، استخدام واحد)
    """
    CREATE TABLE pair_codes(code TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at TEXT NOT NULL);
    """,
]


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def now_iso() -> str:
    return iso(utcnow())  # type: ignore[return-value]


def parse_iso(s: str | None) -> datetime | None:
    if not s:
        return None
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


class DB:
    """غلاف بسيط آمن للخيوط حول sqlite3."""

    def __init__(self, path: Path | str):
        self.path = str(path)
        self._lock = threading.RLock()
        self.conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None, timeout=30)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA foreign_keys=ON")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.migrate()

    # ——— الترحيلات
    def migrate(self) -> None:
        with self._lock:
            self.conn.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            row = self.conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
            version = int(row[0]) if row else 0
            for i, sql in enumerate(MIGRATIONS[version:], start=version + 1):
                self.conn.execute("BEGIN")
                try:
                    for stmt in _split_sql(sql):
                        self.conn.execute(stmt)
                    self.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version',?)", (str(i),))
                    self.conn.execute("COMMIT")
                except Exception:
                    self.conn.execute("ROLLBACK")
                    raise

    @property
    def schema_version(self) -> int:
        row = self.one("SELECT value FROM meta WHERE key='schema_version'")
        return int(row["value"]) if row else 0

    # ——— عمليات عامة
    def execute(self, sql: str, params: Iterable[Any] = ()) -> sqlite3.Cursor:
        with self._lock:
            return self.conn.execute(sql, tuple(params))

    def insert(self, sql: str, params: Iterable[Any] = ()) -> int:
        with self._lock:
            return int(self.conn.execute(sql, tuple(params)).lastrowid)

    def one(self, sql: str, params: Iterable[Any] = ()) -> dict | None:
        with self._lock:
            r = self.conn.execute(sql, tuple(params)).fetchone()
            return dict(r) if r else None

    def all(self, sql: str, params: Iterable[Any] = ()) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self.conn.execute(sql, tuple(params)).fetchall()]

    def tx(self):
        return _Tx(self)

    # ——— إعدادات المستخدم
    def get_setting(self, user_id: int, key: str, default: Any = None) -> Any:
        r = self.one("SELECT value FROM settings_kv WHERE user_id=? AND key=?", (user_id, key))
        if not r:
            return default
        try:
            return json.loads(r["value"])
        except ValueError:
            return r["value"]

    def set_setting(self, user_id: int, key: str, value: Any) -> None:
        self.execute(
            "INSERT INTO settings_kv(user_id,key,value) VALUES(?,?,?) "
            "ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value",
            (user_id, key, json.dumps(value, ensure_ascii=False)),
        )

    # ——— منع التكرار
    def claim_once(self, key: str) -> bool:
        """يعيد True أول مرة فقط لنفس المفتاح (لمنع تنفيذ الطلب نفسه مرتين)."""
        try:
            self.execute("INSERT INTO processed_keys(key, created_at) VALUES(?,?)", (key, now_iso()))
            return True
        except sqlite3.IntegrityError:
            return False

    def log_event(self, level: str, source: str, message: str) -> None:
        try:
            self.execute(
                "INSERT INTO event_log(level,source,message,created_at) VALUES(?,?,?,?)",
                (level, source, message[:4000], now_iso()),
            )
        except Exception:
            pass

    # ——— نسخ احتياطي
    def backup_to(self, dest: Path | str) -> None:
        with self._lock:
            target = sqlite3.connect(str(dest))
            try:
                self.conn.backup(target)
            finally:
                target.close()

    def close(self) -> None:
        with self._lock:
            self.conn.close()


class _Tx:
    def __init__(self, db: DB):
        self.db = db

    def __enter__(self):
        self.db._lock.acquire()
        self.db.conn.execute("BEGIN IMMEDIATE")
        return self.db

    def __exit__(self, exc_type, exc, tb):
        try:
            self.db.conn.execute("ROLLBACK" if exc_type else "COMMIT")
        finally:
            self.db._lock.release()
        return False


def _split_sql(script: str) -> list[str]:
    """تقسيم سكربت SQL إلى جمل مع احترام كتل BEGIN ... END داخل المشغّلات."""
    out, buf = [], []
    for line in script.splitlines():
        buf.append(line)
        candidate = "\n".join(buf).strip()
        if candidate.endswith(";") and sqlite3.complete_statement(candidate):
            out.append(candidate)
            buf = []
    rest = "\n".join(buf).strip()
    if rest:
        out.append(rest)
    return [s for s in out if s.strip(" ;\n")]
