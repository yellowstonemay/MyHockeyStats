-- Email verification for password signup.
--
-- Before: POST /api/auth/signup created a working account from any address and
--         returned a JWT immediately — nobody proved they owned the inbox.
-- After:  the account is created unverified, a single-use link is emailed, and
--         sign-in is refused until the address is confirmed.
--
-- Existing users are grandfathered in as verified: they are already using the
-- site and must not be locked out.
--
-- Social sign-in marks accounts verified in code, because Google/Facebook
-- already confirmed the address.
--
-- Guarded so it no-ops on a fresh database (Flyway runs before Hibernate).

DO $$
BEGIN
    IF to_regclass('users') IS NULL THEN
        RAISE NOTICE 'V20260916_02 skipped: users table not present yet (fresh database)';
        RETURN;
    END IF;

    ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified boolean NOT NULL DEFAULT false;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at timestamp without time zone;

    -- Nobody who already had an account should be locked out.
    UPDATE users
       SET email_verified = true,
           email_verified_at = COALESCE(email_verified_at, created_at, now())
     WHERE email_verified = false;

    -- Single-use verification tokens. Only the SHA-256 hash is stored, so a
    -- database leak cannot be replayed against the verify endpoint.
    CREATE TABLE IF NOT EXISTS email_verification_tokens (
        id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id     bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash  varchar(128) NOT NULL UNIQUE,
        purpose     varchar(32)  NOT NULL DEFAULT 'SIGNUP',
        expires_at  timestamp without time zone NOT NULL,
        consumed_at timestamp without time zone,
        created_at  timestamp without time zone NOT NULL DEFAULT now()
    );

    CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user
        ON email_verification_tokens (user_id, purpose);
    CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_hash
        ON email_verification_tokens (token_hash);
END $$;
