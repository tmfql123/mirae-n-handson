CREATE OR REPLACE VIEW v_item_public AS
SELECT
  i.id,
  i.unit_id,
  u.code AS unit_code,
  u.name AS unit_name,
  u.grade AS unit_grade,
  i.title,
  i.stem,
  i.level,
  i.created_at,
  i.updated_at,
  (SELECT GROUP_CONCAT(t.name ORDER BY t.id SEPARATOR ',')
     FROM item_tag it
     JOIN tag t ON t.id = it.tag_id
    WHERE it.item_id = i.id) AS tag_names
FROM item i
JOIN unit u ON u.id = i.unit_id
WHERE i.status = 'A';
