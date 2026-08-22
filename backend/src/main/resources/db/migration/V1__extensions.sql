-- Phase 1: extensions only.
-- pg_trgm backs fuzzy/typo-tolerant product title search (Phase 4) and is
-- created first so later migrations can index on it without ordering pain.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
