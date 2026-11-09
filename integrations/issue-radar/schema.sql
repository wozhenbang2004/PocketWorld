-- Apply with: npx wrangler d1 execute pocketworld-notices --remote --file=./schema.sql
CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  source_key TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  origin TEXT NOT NULL,
  url TEXT NOT NULL,
  priority TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS watched_repos (
  name TEXT PRIMARY KEY,
  initialized INTEGER NOT NULL DEFAULT 0,
  checked_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS watched_issues (
  issue_key TEXT PRIMARY KEY,
  welcoming INTEGER NOT NULL DEFAULT 0,
  seen_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at);
