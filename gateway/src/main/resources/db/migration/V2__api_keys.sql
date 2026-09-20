create table api_keys (
    key_id              varchar(64)  primary key,
    key_hash            varchar(64)  not null unique,
    label               varchar(128) not null unique,
    requests_per_minute integer      not null check (requests_per_minute > 0),
    enabled             boolean      not null default true,
    created_at          timestamptz  not null
);

create table api_key_scopes (
    key_id varchar(64) not null references api_keys (key_id) on delete cascade,
    scope  varchar(64) not null,
    primary key (key_id, scope)
);

-- The hash is what is looked up on every request.
create index idx_api_keys_hash on api_keys (key_hash) where enabled;
