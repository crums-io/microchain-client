CREATE TABLE customers (
  cust_id   BIGINT PRIMARY KEY,
  cust_guid VARCHAR(36)
);

CREATE TABLE products (
  prod_id   BIGINT PRIMARY KEY,
  prod_guid VARCHAR(36)
);

CREATE TABLE sales (
  sale_id  BIGINT PRIMARY KEY,
  cust_id  BIGINT,
  txn_date DATE
);

CREATE TABLE sold_items (
  si_id      BIGINT PRIMARY KEY,
  sale_id    BIGINT,
  prod_id    BIGINT,
  item_count INT,
  price      DECIMAL(10,2)
);
