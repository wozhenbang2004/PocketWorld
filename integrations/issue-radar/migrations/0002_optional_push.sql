-- Optional: apply only when the operator explicitly enables PUSH_ENABLED.
-- Subscription endpoint/auth are capabilities. Restrict D1 access and never log/export rows.
CREATE TABLE IF NOT EXISTS push_devices (
  device_id TEXT PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  subscription TEXT NOT NULL,
  challenge TEXT NOT NULL,
  verified INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL,
  last_ack_seq INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL DEFAULT 0,
  attempts INTEGER NOT NULL DEFAULT 0
);
