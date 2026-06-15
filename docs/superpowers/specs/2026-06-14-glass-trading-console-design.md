# Glass Trading Console Design

## Design Read

Reading this as a high-density OKX live-trading admin console for a single operator, with a dark glass trading-terminal language, leaning toward MUI theme customization plus focused React components.

## Goals

- Preserve the existing React Admin routes, backend API calls, copy, and trading workflows.
- Replace the flat dark-card look with a modern frosted-glass interface that still reads quickly under pressure.
- Keep the UI operational rather than decorative: risk state, account state, pending orders, positions, and AI plan actions stay visible and scan-friendly.
- Avoid adding a new design system or heavy animation dependency.

## Visual System

- Theme: single dark theme with near-black navy base, emerald as the primary action/accent, amber/red reserved for trading risk.
- Material: layered translucent panels using `backdrop-filter`, subtle inner highlights, and soft colored borders.
- Typography: use the existing system stack, tighten hierarchy, and reserve mono styling for prices, ratios, timestamps, and state values.
- Shape: one radius scale, mostly 14 to 18px for panels and 10 to 12px for controls.
- Motion: restrained hover lift and button press feedback only.

## Architecture

- Keep `react-admin` and `@mui/material`.
- Move repeatable glass styling into small helpers/components instead of duplicating raw `sx`.
- Modernize global MUI component overrides in `theme.ts`.
- Refresh the dashboard, status strip, metric cards, risk panel, login, and contract cards first because they define the product feel.
- Leave API/data-provider code untouched.

## Verification

- Run TypeScript and Vite build after edits.
- If npm scripts cannot resolve local binaries because dependency install timed out, run local package binaries directly.
