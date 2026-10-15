-- Account numbers: the next value, zero-padded to 20 digits by the app (AccountNumber). Gaps after a rollback are fine.
create sequence account_number_seq;

create table account
(
    id         bigint generated always as identity primary key,
    number     varchar(20)    not null,
    owner_id   bigint references app_user (id),
    type       varchar(20)    not null,
    currency   varchar(3)     not null,
    balance    numeric(19, 2) not null default 0,
    status     varchar(20)    not null default 'ACTIVE',
    created_at timestamptz    not null default now(),
    constraint uk_account_number unique (number),
    constraint ck_account_number_format check (number ~ '^[0-9]{20}$'),
    constraint ck_account_type check (type in ('CUSTOMER', 'SYSTEM')),
    constraint ck_account_currency check (currency in ('UZS', 'USD')),
    constraint ck_account_status check (status in ('ACTIVE', 'FROZEN')),
    -- a customer account always has an owner; a SYSTEM (bank cash) account never does
    constraint ck_account_owner_matches_type check (
        (type = 'CUSTOMER' and owner_id is not null) or (type = 'SYSTEM' and owner_id is null)),
    -- last line of defence behind Account.debit(): SYSTEM accounts may go negative, customer accounts never
    constraint ck_account_customer_balance_non_negative check (type <> 'CUSTOMER' or balance >= 0)
);

-- "my accounts" and the ownership check look accounts up by owner
create index ix_account_owner_id on account (owner_id);
