-- MySQL 8. Set the owner and UTC start time for the observation cohort.
SET time_zone = '+00:00';
SET @owner = 'local-admin';
SET @since = UTC_TIMESTAMP() - INTERVAL 7 DAY;

-- Inspect one scanning round, including candidates rejected before the model call.
-- Replace the scan id with the value in auto_trade_record.scan_id.
SET @scan_id = '';
SELECT a.id, a.ai_observation_id, o.inst_id, o.reason_code, o.model_requested, o.model_reported,
       o.prompt_version, o.threshold_version, o.input_sha256, o.decision_json
FROM ai_entry_observation o
LEFT JOIN auto_trade_record a ON a.ai_observation_id = o.id AND a.user_name = o.user_name
WHERE o.user_name = @owner AND o.scope = 'EXECUTION' AND o.scan_id = @scan_id
ORDER BY o.observed_at;

-- Retrieve the full input separately when reproducing a decision.
SET @observation_id = '';
SELECT id, input_sha256, input_json FROM ai_entry_observation
WHERE id = @observation_id AND user_name = @owner;

SELECT reason_code, COUNT(*) AS event_count,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS share_pct
FROM ai_entry_observation
WHERE user_name = @owner AND scope = 'EXECUTION' AND observed_at >= @since
GROUP BY reason_code
ORDER BY event_count DESC;

-- Share of all execution observations, including eligible model calls and hard denials.
SELECT prompt_version, threshold_version, reason_code, COUNT(*) AS event_count,
       ROUND(100.0 * COUNT(*) /
             SUM(COUNT(*)) OVER (PARTITION BY prompt_version, threshold_version), 2) AS share_pct
FROM ai_entry_observation
WHERE user_name = @owner AND scope = 'EXECUTION' AND observed_at >= @since
GROUP BY prompt_version, threshold_version, reason_code
ORDER BY prompt_version, threshold_version, event_count DESC;

-- Deduplicated tracked skips. Leave unfinished/expired horizons out of return and win-rate denominators.
WITH tracked AS (
    SELECT o.prompt_version, o.threshold_version, o.reason_code,
           c.outcome_5m, c.outcome_15m, c.outcome_30m
    FROM ai_entry_counterfactual c
    JOIN ai_entry_observation o ON o.id = c.observation_id
    WHERE o.user_name = @owner AND o.scope = 'EXECUTION' AND o.observed_at >= @since
), horizons AS (
    SELECT prompt_version, threshold_version, reason_code, 5 AS minutes, outcome_5m AS outcome FROM tracked
    UNION ALL
    SELECT prompt_version, threshold_version, reason_code, 15, outcome_15m FROM tracked
    UNION ALL
    SELECT prompt_version, threshold_version, reason_code, 30, outcome_30m FROM tracked
), horizon_returns AS (
    SELECT prompt_version, threshold_version, reason_code, minutes,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(outcome, '$.returnPct')) AS DECIMAL(20,8)) AS return_pct
    FROM horizons
)
SELECT prompt_version, threshold_version, reason_code, minutes, COUNT(*) AS tracked,
       COUNT(return_pct) AS measured, ROUND(AVG(return_pct), 4) AS mean_return_pct,
       ROUND(100.0 * SUM(return_pct > 0) / NULLIF(COUNT(return_pct), 0), 2) AS win_rate_pct
FROM horizon_returns
GROUP BY prompt_version, threshold_version, reason_code, minutes
ORDER BY prompt_version, threshold_version, reason_code, minutes;
