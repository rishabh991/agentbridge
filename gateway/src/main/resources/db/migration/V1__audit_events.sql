create table audit_events (
    event_id        uuid         primary key,
    occurred_at     timestamptz  not null,
    api_key_id      varchar(64),
    tool            varchar(128) not null,
    upstream        varchar(64)  not null,
    arguments       text         not null,
    outcome         varchar(32)  not null,
    http_status     integer,
    latency_millis  bigint       not null,
    idempotency_key varchar(128),
    provider        varchar(32),
    tokens_in       bigint       not null default 0,
    tokens_out      bigint       not null default 0,
    cost_micros     bigint       not null default 0
);

create index idx_audit_occurred_at on audit_events (occurred_at desc);
create index idx_audit_tool on audit_events (tool, occurred_at desc);
create index idx_audit_outcome on audit_events (outcome, occurred_at desc);
