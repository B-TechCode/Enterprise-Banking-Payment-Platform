-- Scope idempotency fingerprints to their owner.
--
-- account_hold.request_fingerprint and account.request_fingerprint were each
-- unique across the whole table, so an Idempotency-Key one customer had used
-- could not be used by any other: a collision failed the write as a database
-- error. Holds become unique per account, matching account_transaction's
-- uk_tx_account_idem; account creation becomes unique per customer.
--
-- Why this is a migration and not an annotation change: Hibernate's
-- ddl-auto: update never drops a constraint that is no longer declared. Editing
-- the entities alone would give new databases the new constraints while every
-- existing database kept the old global ones as well, and the global ones would
-- go on winning. FingerprintMigrationIT proves this migration against the
-- schema Hibernate actually generated before the change.
--
-- Safe on existing rows by construction: values unique across a table are
-- unique within any account or customer, so neither new constraint can be
-- violated by data that satisfied the old one. Rows without a key keep NULL,
-- and Postgres treats NULLs as distinct, as before.
--
-- Stored fingerprints are not rewritten. New keyless account creates are
-- fingerprinted with SHA-256 (64 hex characters) where the old code used a
-- 32-bit hashCode (at most 8), so old and new values can never collide. The
-- cost, accepted deliberately: a keyless create sent before this deploy and
-- retried after it will not match its first attempt, and makes a second
-- account. Only keyless creates, and only across the one deploy. A backfill
-- was rejected because a legacy hash cannot be told apart from a client's own
-- 8-character key, and recomputing from today's nickname and display name
-- would fingerprint the account as it is now, not the request that made it.
--
-- Written to do nothing on a fresh database. Flyway runs before Hibernate, so
-- there the tables do not exist yet; Hibernate then creates them with these
-- constraints already declared on the entities.
--
-- The two ADD CONSTRAINT blocks below are redundant while ddl-auto is update:
-- Hibernate adds both from the entity declarations anyway, and removing them
-- from here leaves FingerprintMigrationIT green. They stay because dropping the
-- old constraints and adding the new ones is one change, and a migration that
-- performs only half of it relies on a setting this service is meant to leave
-- behind (docs/BACKLOG.md item 8, ddl-auto: validate). Under validate, nothing
-- but this file would create them.

DO $$
DECLARE
    old_uniqueness record;
BEGIN
    -- Every unique index on request_fingerprint alone, whether it backs a
    -- constraint (@Column(unique = true), under a Hibernate-generated name) or
    -- stands alone (account's idx_account_fingerprint). Matched on what it
    -- covers rather than on its name, since the generated names are hashes.
    FOR old_uniqueness IN
        SELECT tbl.relname AS table_name,
               idx.relname AS index_name,
               con.conname AS constraint_name
        FROM pg_index ix
        JOIN pg_class idx ON idx.oid = ix.indexrelid
        JOIN pg_class tbl ON tbl.oid = ix.indrelid
        JOIN pg_namespace ns ON ns.oid = tbl.relnamespace
        JOIN pg_attribute att ON att.attrelid = tbl.oid AND att.attnum = ix.indkey[0]
        LEFT JOIN pg_constraint con ON con.conindid = ix.indexrelid
        WHERE ns.nspname = current_schema()
          AND tbl.relname IN ('account', 'account_hold')
          AND ix.indisunique
          AND ix.indnatts = 1
          AND att.attname = 'request_fingerprint'
    LOOP
        IF old_uniqueness.constraint_name IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I',
                           old_uniqueness.table_name, old_uniqueness.constraint_name);
        ELSE
            EXECUTE format('DROP INDEX %I', old_uniqueness.index_name);
        END IF;
    END LOOP;

    IF to_regclass('account_hold') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = 'account_hold'::regclass
                         AND conname = 'uk_hold_account_fingerprint') THEN
            ALTER TABLE account_hold
                ADD CONSTRAINT uk_hold_account_fingerprint UNIQUE (account_id, request_fingerprint);
        END IF;
    END IF;

    IF to_regclass('account') IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid = 'account'::regclass
                         AND conname = 'uk_account_customer_fingerprint') THEN
            ALTER TABLE account
                ADD CONSTRAINT uk_account_customer_fingerprint UNIQUE (customer_id, request_fingerprint);
        END IF;
    END IF;
END $$;
