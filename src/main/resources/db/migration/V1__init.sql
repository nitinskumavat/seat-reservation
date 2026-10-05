CREATE TYPE seat_status        AS ENUM ('available', 'held', 'confirmed');
CREATE TYPE reservation_status AS ENUM ('confirmed', 'cancelled');

CREATE TABLE users (
    id         text PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shows (
    id             uuid PRIMARY KEY,
    name           text NOT NULL,
    price_paise    bigint NOT NULL CHECK (price_paise >= 0),
    per_user_limit int NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    int NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    show_id        uuid NOT NULL REFERENCES shows (id),
    label          text NOT NULL,
    status         seat_status NOT NULL DEFAULT 'available',
    reservation_id uuid NULL,
    user_id        text NULL REFERENCES users (id),
    PRIMARY KEY (show_id, label)
);

CREATE TABLE reservations (
    id              uuid PRIMARY KEY,
    show_id         uuid NOT NULL REFERENCES shows (id),
    user_id         text NOT NULL REFERENCES users (id),
    seats           text[] NOT NULL,
    amount_paise    bigint NOT NULL,
    status          reservation_status NOT NULL,
    idempotency_key text NOT NULL,
    request_hash    text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key)
);

CREATE TABLE user_show_counts (
    user_id    text NOT NULL REFERENCES users (id),
    show_id    uuid NOT NULL REFERENCES shows (id),
    seat_count int NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (user_id, show_id)
);
