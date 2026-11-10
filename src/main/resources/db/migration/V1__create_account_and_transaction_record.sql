create table account
(
    id         varchar(64)              not null,
    balance    bigint                   not null,
    created_at timestamp with time zone not null,
    constraint pk_account primary key (id),
    -- last line of defence; the service never lets it get this far
    constraint ck_account_balance_non_negative check (balance >= 0)
);

create table transaction_record
(
    id                     varchar(64)              not null,
    type                   varchar(16)              not null,
    source_account_id      varchar(64),
    destination_account_id varchar(64),
    amount                 bigint                   not null,
    status                 varchar(16)              not null,
    rejection_reason       varchar(32),
    created_at             timestamp with time zone not null,
    -- the primary key is what makes a transactionId apply at most once
    constraint pk_transaction_record primary key (id),
    constraint fk_transaction_record_source foreign key (source_account_id) references account (id),
    constraint fk_transaction_record_destination foreign key (destination_account_id) references account (id),
    constraint ck_transaction_record_amount_positive check (amount > 0),
    constraint ck_transaction_record_accounts check (
        (type = 'CREDIT' and source_account_id is null and destination_account_id is not null)
            or (type = 'DEBIT' and source_account_id is not null and destination_account_id is null)
            or (type = 'TRANSFER' and source_account_id is not null and destination_account_id is not null
            and source_account_id <> destination_account_id)
        )
);

create index ix_transaction_record_source on transaction_record (source_account_id);
create index ix_transaction_record_destination on transaction_record (destination_account_id);
