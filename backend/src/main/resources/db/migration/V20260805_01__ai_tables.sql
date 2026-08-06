-- AI season-report tables (feature 2: DeepSeek insights with weekly/daily cost caps)
-- ai_insights: cached AI report prose per user+season (1/week/user enforced in code)
-- ai_usage_daily: daily cost ledger for the global cost cap

CREATE TABLE IF NOT EXISTS ai_insights (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    season_year    integer,
    source_json    text,
    insights_json  text NOT NULL,
    model          varchar(64),
    input_tokens   integer DEFAULT 0,
    output_tokens  integer DEFAULT 0,
    cost_usd       numeric(12,6) DEFAULT 0,
    created_at     timestamp without time zone DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ai_insights_user_created ON ai_insights(user_id, created_at);

CREATE TABLE IF NOT EXISTS ai_usage_daily (
    day            date PRIMARY KEY,
    calls          integer DEFAULT 0,
    input_tokens   bigint DEFAULT 0,
    output_tokens  bigint DEFAULT 0,
    cost_usd       numeric(12,6) DEFAULT 0
);
