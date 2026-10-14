-- "user" is a reserved word in PostgreSQL, hence app_user.
create table app_user
(
    id            bigint generated always as identity primary key,
    username      varchar(50)  not null,
    password_hash varchar(100) not null,
    full_name     varchar(100) not null,
    role          varchar(20)  not null,
    created_at    timestamptz  not null default now(),
    constraint uk_app_user_username unique (username),
    -- the app lowercases usernames; this keeps "Azamat" and "azamat" from ever becoming two users
    constraint ck_app_user_username_lowercase check (username = lower(username)),
    constraint ck_app_user_role check (role in ('CUSTOMER', 'ADMIN'))
);
