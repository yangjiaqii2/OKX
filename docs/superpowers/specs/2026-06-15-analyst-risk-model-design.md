# Analyst Risk Model Design

## Goal

Upgrade the OKX contract risk model from static threshold checks into a layered analyst-style control model. The model does not predict direction. It validates whether a generated trade plan can be executed, what leverage cap should apply, and how much account equity can be risked on that trade.

## Model Layers

1. Hard circuit breakers
   - emergency stop
   - websocket disconnected
   - stale market data
   - missing stop loss
   - blocked external/news risk

2. Market quality gates
   - minimum 24h liquidity
   - maximum absolute funding rate
   - valid and bounded stop distance
   - minimum risk/reward ratio
   - minimum signal score

3. Dynamic leverage cap
   - starts from configured max leverage
   - reduced by volatility, funding crowding, weak signal score, wide stop distance, and high news risk
   - can pass with warnings at MEDIUM/HIGH risk instead of always rejecting
   - rejects when requested leverage remains above the dynamic cap

4. Position risk budget
   - max loss is derived from account equity and risk level
   - LOW uses the configured single-trade loss budget
   - MEDIUM cuts the budget in half
   - HIGH cuts the budget to one quarter
   - margin cap is still enforced separately

## Execution Rule

Pending order confirmation must build a risk request from the pending order and the user-entered margin amount. It must not use safe defaults for live order checks.

## Output

The result should explain:

- pass or reject
- risk level
- reject code and reason when blocked
- warning list when degraded
- adjusted leverage cap
- max loss amount

## Verification

Tests should cover:

- volatile/crowded setup receives a lower dynamic leverage cap
- leverage above the dynamic cap is rejected
- HIGH risk still passes only when leverage is already reduced
- confirmation builds risk checks from the pending order instead of safe defaults
