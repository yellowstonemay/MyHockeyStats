create table if not exists integration_import_run (
    id uuid primary key,
    source varchar(32) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    started_at timestamp not null,
    ended_at timestamp,
    processed_count integer not null default 0,
    accepted_count integer not null default 0,
    rejected_count integer not null default 0,
    duplicate_skipped_count integer not null default 0,
    error_summary text
);

create table if not exists integration_imported_player_record (
    id uuid primary key,
    source varchar(32) not null,
    source_player_id varchar(128),
    source_team_id varchar(128),
    source_team_name varchar(255),
    source_club_id varchar(128),
    source_club_name varchar(255),
    player_name_raw varchar(255) not null,
    player_name_normalized varchar(255) not null,
    birth_month integer not null,
    birth_year integer not null,
    season_label varchar(32),
    import_run_id uuid references integration_import_run(id),
    source_hash varchar(255),
    created_at timestamp not null,
    unique (source, source_hash)
);

create table if not exists integration_imported_game_record (
    id uuid primary key,
    imported_player_record_id uuid not null references integration_imported_player_record(id),
    source_game_id varchar(128),
    game_date date,
    opponent_name varchar(255),
    final_score_text varchar(64),
    goals integer,
    assists integer,
    points integer,
    import_run_id uuid references integration_import_run(id),
    logical_game_key varchar(255)
);

create table if not exists integration_match_link (
    id uuid primary key,
    user_id bigint not null references users(id),
    source varchar(32) not null,
    imported_player_record_id uuid not null references integration_imported_player_record(id),
    link_state varchar(32) not null,
    match_method varchar(32) not null,
    confidence_score numeric(5,4),
    confirmed_at timestamp,
    last_verified_at timestamp,
    identity_fingerprint varchar(512),
    invalidated_reason varchar(512)
);

create table if not exists integration_source_conflict (
    id uuid primary key,
    user_id bigint not null references users(id),
    logical_record_key varchar(255) not null,
    conflict_type varchar(32) not null,
    field_name varchar(128) not null,
    source_values_json text not null,
    is_active boolean not null default true,
    detected_at timestamp not null,
    resolved_at timestamp
);

create index if not exists idx_integration_player_name_birth on integration_imported_player_record(player_name_normalized, birth_year, birth_month);
create index if not exists idx_integration_game_logical_key on integration_imported_game_record(logical_game_key);
create index if not exists idx_integration_match_user_source on integration_match_link(user_id, source);
create index if not exists idx_integration_conflict_user_key on integration_source_conflict(user_id, logical_record_key);
