# Risk Calculator (Executable)

A discretionary, risk-based market-entry **strategy** for MotiveWave. Draw your stop loss
on the chart, watch the position size and dollar risk update live as price moves, then
press **BUY MARKET** or **SELL MARKET** to submit a correctly-sized market order that is
automatically protected with a stop loss (at the exact price you drew) and a limit target
at your configured Risk:Reward ratio.

This began as the visual-only "Risk Calculator" study (drag Entry/SL lines, see a live
position-size label) and has been extended into an executable strategy. All of the
original chart-interaction code (draggable Entry/SL, lock-to-market, hover ghost handles,
the R-multiple preview grid) is preserved unchanged; execution is layered on top.

## Settings

**Position Sizing**
- Fixed Risk Amount ($) — the one and only sizing basis, used for both the on-chart
  preview and every live order. (The original percent-of-equity sizing mode was removed
  deliberately — see "Design decisions" below.)

**Execution**
- Maximum Contracts — hard cap, never exceeded regardless of the risk math
- Risk:Reward Ratio — used to place the take-profit once a fill is confirmed
- Sizing Slippage Buffer (ticks) — widens the assumed stop distance during sizing so a
  worse-than-expected fill still keeps actual risk at or under your max
- Auto-trim Position if Fill Breaches Max Risk — off by default; see write-up
- Show Execution Panel / Buttons — toggles the on-chart BUY/SELL panel

**Display** — unchanged from the original (colors, fonts, hover width, R-multiple grid,
lock button position, panel colors).

## Using it

1. Add the strategy to a chart of the futures instrument you want to trade (ES, MES, NQ,
   MNQ, CL, GC, etc. — the sizing math reads tick size / point value from the instrument,
   nothing is hard-coded).
2. Press **Activate** on the Strategy Control Box. The BUY/SELL buttons are disabled
   (greyed out) until the strategy is active.
3. Drag the **SL** line to where your stop should sit.
4. Either drag the **E** line to a fixed entry, or click the lock icon to have entry follow
   the live market price (this is the "discretionary market entry" mode the tool is built
   for — the panel keeps recalculating contracts as price moves toward or away from your
   stop).
5. When the panel shows a non-zero contract count, press **BUY MARKET** or **SELL MARKET**.
6. The panel's status line reports submission, fill, and bracket status. If anything goes
   wrong (rejected protective order, missing SL, etc.) the tool flattens the position
   rather than leaving it unprotected — see "Safety behavior" below.

To clear all drawings and reset execution state, right-click the price axis and choose
"Clear RR Calculations / Reset".

## Build / install

1. Install the MotiveWave SDK (Configure → your MotiveWave install should ship a
   `mwave_sdk.jar`, or download it from the SDK Programming Guide page on
   docs.motivewave.com).
2. Compile against that jar and your JDK (11+ recommended; the source uses `var` and
   switch expressions, so JDK 14+ is required if you keep those):
   ```
   javac -cp mwave_sdk.jar -d build risk_calculator/RiskCalculator.java
   ```
3. Package the compiled classes together with `risk_calculator/nls/strings.properties`
   (keep the `risk_calculator/nls/` path) into a jar:
   ```
   cd build
   cp -r ../risk_calculator/nls risk_calculator/nls
   jar cf RiskCalculatorExecutable.jar risk_calculator
   ```
4. Copy the jar into your MotiveWave **Extensions** folder (Configure → Preferences →
   General should show the path; on Windows this is typically
   `%USERPROFILE%\MotiveWave Extensions\`, on macOS/Linux `~/MotiveWave Extensions/`).
5. Restart MotiveWave (or use the "Reload Extensions" option if your version has one).
6. Add the study to a chart via Study → AlphaVector → Risk Calculator (Executable). It
   will appear with an Activate/Deactivate control box because `strategy=true`.
7. **Connect it to your account**: MotiveWave strategies trade through whatever account is
   selected for the chart/order-routing at the time you press Activate — there is nothing
   Risk-Calculator-specific to configure here. Confirm your Rithmic/Lucid Trading account
   is selected and connected (Trade menu / account selector) before activating.

## Test safely before live trading

Do all of the testing checklist below on a **simulated/paper account** first. MotiveWave
strategies submit through whatever account is active — there is no separate "paper mode"
switch inside this code, so the account you have selected when you press Activate is
where real orders will go.

## Design decisions worth knowing about

- **Cross-Trade Instrument setting.** MotiveWave's SDK has no way to detect whether the
  chart's right-click "Cross Trade" feature is currently active — that's a confirmed platform
  limitation, not something this code can work around. If you chart ES with Cross Trade set to
  MES, orders submitted by this strategy still fill as MES (Cross Trade reroutes execution
  underneath the SDK), but this strategy has no way of knowing that happened, so it would size
  positions using ES's point value ($50/point) rather than the $5/point that actually filled -
  a real 10x sizing error. The **Cross-Trade Instrument** setting (Execution tab) is a manual
  override: set it to MES and the panel switches all dollar-risk math (contracts, actual risk,
  SL points, TP) to use MES's real point value, and the panel visibly shows "ES → MES" so it's
  hard to forget it's active. Order submission itself is untouched - it already fills correctly
  per your own testing. **You must keep this setting in sync with the chart's Cross Trade
  toggle by hand** - there is no way to make that automatic given the SDK's limitations here.
  Leave it blank when not using Cross Trade.

- **Removed percent-of-equity sizing.** The original study offered percent-of-balance or
  fixed-dollar sizing. For a tool that submits real orders, having two different sizing
  algorithms — one for the on-chart label, a possibly different one for the live order —
  is a real way to misjudge risk. Only Fixed Risk Amount remains, and it drives both the
  preview and the order.
- **Slippage handling is sizing-side, not fill-side.** Because this uses genuine market
  orders (not limit entries), the only lever available *before* the fill is quantity. The
  slippage buffer widens the assumed stop distance during sizing so a worse-than-expected
  fill still keeps you at or under budget. After the fill, if the realized risk still
  exceeds your max (an unusually large slippage event beyond the buffer), the tool logs a
  warning and, only if you've opted into "Auto-trim", reduces the position at market to
  bring risk back in line. Auto-trim is off by default because a second market order fired
  immediately after an aggressive slippage event is itself exposed to further slippage —
  it's a genuine trade-off, not a free safety net.
- **On-chart buttons vs. the SDK.** See the comment block at the top of `RiskCalculator.java`
  — the MotiveWave SDK does not give a chart click-handler direct access to order
  management (`OrderContext`); only strategy lifecycle callbacks get one. The click sets a
  flag that is consumed on the very next `onBarUpdate(OrderContext)` tick, which in
  practice submits within milliseconds on a liquid contract. If you'd rather use the fully
  "native" MotiveWave mechanism instead of this hand-off, that's the Strategy Control Box's
  manual-entry Long/Short buttons + `onEnterNow(OrderContext)` — trading a slightly less
  custom UI for zero hand-off latency.
