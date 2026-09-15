-- The overview now links to these analysis pages with the active app and time scope. Keep the
-- routes enabled and authorized, but remove four low-frequency drill-downs from the main sidebar.
UPDATE system_menus
SET visible = FALSE,
    updated_at = (extract(epoch from now()) * 1000)::bigint
WHERE parent_id = (SELECT id FROM system_menus WHERE parent_id = 0 AND path = '/pulse' LIMIT 1)
  AND component IN (
      'pulse/events/index',
      'pulse/versions/index',
      'pulse/crash-issues/index',
      'pulse/performance/index'
  );
