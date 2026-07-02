-- Ledger view over sold_items, joined with sales/customers/products.
--
-- si_id is the surrogate row number: it has an intentional gap at 4
-- (sold_items has 1,2,3,5,6 — see sold_items.csv). The row-by-number
-- query below returns no rows for si_id=4, which SqlLedger treats as
-- a null row (32 zero bytes as input hash).
--
-- For use as the chain's sql-config (GET/PUT /{user}/{chain}/sql-config).

-- row-count query
SELECT MAX(si_id) FROM sold_items;

-- row-by-number query
SELECT si.item_count, si.price, s.txn_date, c.cust_guid, p.prod_guid
FROM sold_items si
JOIN sales     s ON s.sale_id = si.sale_id
JOIN customers c ON c.cust_id = s.cust_id
JOIN products  p ON p.prod_id = si.prod_id
WHERE si.si_id = ?;
