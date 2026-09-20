create table orders (
    id            uuid         primary key,
    customer_id   varchar(128) not null,
    sku           varchar(64)  not null,
    quantity      integer      not null check (quantity > 0),
    amount_cents  bigint       not null check (amount_cents >= 0),
    currency      varchar(3)   not null,
    status        varchar(16)  not null,
    created_at    timestamptz  not null
);

create index idx_orders_customer on orders (customer_id, created_at desc);
create index idx_orders_status on orders (status, created_at desc);

create table payments (
    id           uuid        primary key,
    order_id     uuid        not null references orders (id),
    amount_cents bigint      not null check (amount_cents > 0),
    status       varchar(16) not null,
    captured_at  timestamptz not null
);

create index idx_payments_order on payments (order_id);

create table idempotency_records (
    idempotency_key     varchar(128) primary key,
    request_fingerprint varchar(64)  not null,
    resource_id         uuid         not null,
    created_at          timestamptz  not null
);
