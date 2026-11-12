CREATE TABLE product (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_code UNIQUE (product_code)
);

CREATE TABLE inventory (
    product_id BIGINT PRIMARY KEY,
    quantity BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_inventory_quantity CHECK (quantity >= 0)
);

CREATE TABLE stock_movement (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL,
    movement_type VARCHAR(10) NOT NULL,
    quantity BIGINT NOT NULL,
    quantity_after BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_stock_movement_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT ck_stock_movement_type CHECK (movement_type IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_movement_quantity_after CHECK (quantity_after >= 0)
);
