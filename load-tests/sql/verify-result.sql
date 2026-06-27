SET @offer_id := 900001;
SET @user_id_start := 1000000;
SET @user_id_end := 1004999;

SELECT
  COUNT(*) AS order_count
FROM orders
WHERE offer_id = @offer_id
  AND user_id BETWEEN @user_id_start AND @user_id_end;

SELECT
  user_id,
  COUNT(*) AS order_count
FROM orders
WHERE offer_id = @offer_id
GROUP BY user_id
HAVING COUNT(*) > 1;

SELECT
  offer_id,
  stock AS database_remaining_stock
FROM flash_sale_offers
WHERE offer_id = @offer_id;

SELECT
  status,
  COUNT(*) AS order_count
FROM orders
WHERE offer_id = @offer_id
GROUP BY status
ORDER BY status;
