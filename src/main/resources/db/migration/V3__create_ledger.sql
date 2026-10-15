-- One money movement. Its two ledger entries (one DEBIT, one CREDIT of the same amount) are in ledger_entry.
create table operation
(
    id           uuid primary key,
    type         varchar(20)    not null,
    amount       numeric(19, 2) not null,
    currency     varchar(3)     not null,
    description  varchar(140),
    -- the user who started it: the admin for deposits and withdrawals, the sender for transfers
    initiated_by bigint         not null references app_user (id),
    created_at   timestamptz    not null default now(),
    constraint ck_operation_type check (type in ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    constraint ck_operation_amount_positive check (amount > 0),
    constraint ck_operation_currency check (currency in ('UZS', 'USD'))
);

-- Sign convention: for every account, balance = sum(CREDIT) - sum(DEBIT).
create table ledger_entry
(
    id            bigint generated always as identity primary key,
    operation_id  uuid           not null references operation (id),
    account_id    bigint         not null references account (id),
    direction     varchar(6)     not null,
    amount        numeric(19, 2) not null,
    balance_after numeric(19, 2) not null,
    created_at    timestamptz    not null default now(),
    constraint ck_ledger_entry_direction check (direction in ('DEBIT', 'CREDIT')),
    constraint ck_ledger_entry_amount_positive check (amount > 0),
    -- at most one DEBIT and one CREDIT per operation; its index also serves lookups by operation_id
    constraint uk_ledger_entry_operation_direction unique (operation_id, direction)
);

-- The bank's cash accounts: deposits are debited from them, withdrawals credited to them. No owner, may go negative.
create unique index uk_account_system_currency on account (currency) where type = 'SYSTEM';

insert into account (number, owner_id, type, currency, balance, status)
select lpad(nextval('account_number_seq')::text, 20, '0'), null, 'SYSTEM', currency, 0, 'ACTIVE'
from (values ('UZS'), ('USD')) as system_currency (currency);
