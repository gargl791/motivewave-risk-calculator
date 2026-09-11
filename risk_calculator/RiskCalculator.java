package risk_calculator;

import com.motivewave.platform.sdk.common.*;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.ColorDescriptor;
import com.motivewave.platform.sdk.common.desc.DoubleDescriptor;
import com.motivewave.platform.sdk.common.desc.FontDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.InstrumentDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.common.menu.MenuDescriptor;
import com.motivewave.platform.sdk.common.menu.MenuItem;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.draw.ResizePoint;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Risk Calculator (Executable)
 * ----------------------------
 * Visual risk-planning overlay (drag Entry/SL lines, see live position size)
 * PLUS a manual-entry execution strategy: press BUY MARKET / SELL MARKET to
 * submit a market order sized off your fixed dollar risk, then automatically
 * attach a protective stop (at the exact SL price you drew) and a limit
 * take-profit at your configured R:R.
 *
 * IMPORTANT ARCHITECTURE NOTE (read before modifying):
 * The MotiveWave SDK does not give chart click-handlers (onClick) access to
 * an OrderContext - only strategy lifecycle callbacks such as onBarUpdate
 * (OrderContext), onActivate, onSignal, onOrderFilled etc. receive one. To
 * let a literal on-chart button submit an order, this class uses a small
 * hand-off: onClick() (UI thread, no OrderContext) sets a `pendingAction`
 * flag; onBarUpdate(OrderContext ctx) (fires on every tick because
 * requiresBarUpdates=true) notices the flag and performs the actual
 * submission. The delay between click and submission is therefore bounded
 * by "time until the next tick" - typically low-single-digit milliseconds
 * on a liquid future - not a fixed polling interval. This is a deliberate
 * design decision, not an SDK feature; see the accompanying write-up for
 * the fully "native" alternative (manualEntry Control Box + onEnterNow).
 */
@StudyHeader(
        namespace = "com.alpha_vector",
        id = "RISK_CALCULATOR_EXEC",
        rb = "risk_calculator.nls.strings",
        name = "STUDY_NAME",
        desc = "STUDY_DESC",
        menu = "MENU_GENERAL",
        menu2 = "MENU_ALPHA_VECTOR",
        overlay = true,
        studyOverlay = true,
        requiresBarUpdates = true,     // required so onBarUpdate(OrderContext) fires on every tick
        strategy = true,               // makes this a Strategy (adds Activate/Deactivate control box)
        multipleInstrument = true,     // required for the Cross-Trade Instrument picker to return data at runtime
        autoEntry = false,             // we never enter automatically - discretionary tool only
        manualEntry = true,            // this is a manual/discretionary strategy
        supportsLongShort = false,     // direction comes from which chart button is pressed, not a toggle
        supportsEnterOnActivate = false,
        supportsCloseOnDeactivate = true,
        supportsEntryPrice = true,
        supportsPosition = true,
        supportsCurrentPL = true,
        supportsRealizedPL = true,
        supportsUnrealizedPL = true,
        supportsStopPL = true,
        supportsTargetPL = true
)
public class RiskCalculator extends Study {

    // ==================== Settings keys ====================
    private static final String HOVER_AREA_WIDTH = "hoverAreaWidth";
    private static final String PATH_ENTRY = "pathEntry";
    private static final String PATH_SL = "pathSL";
    private static final String PATH_TP = "pathTP";
    private static final String PATH_GHOST = "pathGhost";
    private static final String COLOR_BG = "colorBG";
    private static final String COLOR_LOCK_BG = "colorLockBG";
    private static final String COLOR_LOCK_LOCKED_BG = "colorLockLockedBG";
    private static final String COLOR_LOCK_ICON = "colorLockIcon";
    private static final String COLOR_LOCK_OUTLINE = "colorLockOutline";
    private static final String COLOR_BUY = "colorBuy";
    private static final String COLOR_SELL = "colorSell";
    private static final String COLOR_PANEL_BG = "colorPanelBg";
    private static final String COLOR_PANEL_TEXT = "colorPanelText";
    private static final String MAX_RR = "maxRR";
    private static final String FONT = "font";
    private static final String LOCK_BUTTON_POS = "lockButtonPos";
    private static final String EXTEND_PREVIEW = "extendPreview";
    private static final String EXTEND_LEVEL = "extendLevel";
    private static final String ENABLE_POS_SIZE = "enablePosSize";
    private static final String FIXED_RISK_AMOUNT = "fixedRiskAmount";
    private static final String COLOR_WARNING = "colorWarning";

    // Execution settings
    private static final String MAX_CONTRACTS = "maxContracts";
    private static final String TRADE_RR = "tradeRR";
    private static final String SLIPPAGE_TICKS = "slippageTicks";
    private static final String AUTO_FLATTEN = "autoFlatten";
    private static final String ENABLE_EXEC_PANEL = "enableExecPanel";
    private static final String CROSS_INSTRUMENT = "crossInstrument";
    private static final String EXEC_PANEL_POS = "execPanelPos";

    // ==================== UI constants ====================
    private static final int DEFAULT_HOVER_WIDTH = 50;
    private static final String POS_TOP = "top";
    private static final String POS_BOTTOM = "bottom";
    private static final int LOCK_BUTTON_HEIGHT = 20;
    private static final int LOCK_BUTTON_MARGIN = 5;
    private static final String CORNER_TOP_LEFT = "topLeft";
    private static final String CORNER_TOP_RIGHT = "topRight";
    private static final String CORNER_BOTTOM_LEFT = "bottomLeft";
    private static final String CORNER_BOTTOM_RIGHT = "bottomRight";
    private static final int LABEL_OFFSET_Y = 4;
    private static final int PANEL_WIDTH = 152;
    private static final int PANEL_PADDING = 6;
    private static final int PANEL_LINE_HEIGHT = 13;
    private static final int PANEL_BUTTON_HEIGHT = 20;
    private static final int PANEL_BUTTON_GAP = 3;
    private static final float PANEL_FONT_SIZE = 10.5f;

    private static final BasicStroke BORDER_STROKE = new BasicStroke(0.5f);
    private static final BasicStroke LOCK_ICON_STROKE = new BasicStroke(1.2f);

    // ==================== Visual/planning state (unchanged from original) ====================
    private Double entryPrice;
    private Double stopLossPrice;
    private boolean lockedToMarket;
    private double hoverPrice;

    // ==================== Execution state ====================
    /** Set by onClick() (UI thread). Consumed by onBarUpdate(OrderContext) (order thread). */
    private volatile Enums.OrderAction pendingAction;
    /** True from the moment a click is accepted until the entry order is filled/rejected/cancelled. */
    private final AtomicBoolean orderInFlight = new AtomicBoolean(false);

    private Order entryOrder;
    private Order stopOrder;
    private Order targetOrder;
    /** SL price captured at the instant the order was submitted - the bracket always honours this,
     *  even if the user drags the SL line around afterwards. */
    private volatile Double armedStopLossPrice;
    /** Real average fill price, captured once the entry order actually fills (Order.getAvgFillPrice()) -
     *  freezes the entry line and all RR preview lines to the real fill instead of letting them keep
     *  drifting with lockedToMarket while a trade is live. */
    private volatile Double armedEntryPrice;
    /** Instrument resolveTradeInstrument() returned at entry-submission time, cached so the
     *  eventual fill can be checked against what we expected to be routed to. See
     *  handleEntryFilled() / verifyFillInstrument(). */
    private volatile Instrument expectedTradeInstrument;
    private volatile int bracketQuantity;
    /** Set once reconcileWithAccount() detects a real account position with no bracket tracked
     *  (see write-up item 5 - typically a platform/strategy restart mid-trade). Throttles the
     *  warning to fire once on detection rather than every tick, and clears automatically once
     *  the account position returns to zero. */
    private volatile boolean unprotectedPositionWarned;
    private volatile String statusMessage = "Flat";
    private volatile boolean statusIsError;

    // Cached geometry so Study.onClick() can hit-test without reaching into the Figure
    private Rectangle buyButtonBounds;
    private Rectangle sellButtonBounds;

    private static class CachedSettings {
        final int hoverWidth;
        final Color bgColor;
        final Color lockBgColor;
        final Color lockLockedBgColor;
        final Color lockOutlineColor;
        final Color lockIconColor;
        final Color warningColor;
        final Color buyColor;
        final Color sellColor;
        final Color panelBgColor;
        final Color panelTextColor;
        final String lockButtonPos;
        final boolean extendPreview;
        final boolean extendLevel;
        final boolean enablePosSize;
        final boolean enableExecPanel;
        final String execPanelPos;
        final Instrument crossInstrument;
        final int fixedRiskAmount;
        final int maxContracts;
        final double tradeRR;
        final int slippageTicks;
        final boolean autoFlatten;
        final FontInfo fontInfo;

        final PathInfo entryPath;
        final PathInfo slPath;
        final PathInfo tpPath;
        final PathInfo ghostPath;

        CachedSettings(Settings settings) {
            this.hoverWidth = settings.getInteger(HOVER_AREA_WIDTH, DEFAULT_HOVER_WIDTH);
            this.bgColor = settings.getColor(COLOR_BG, new Color(128, 128, 128, 30));
            this.lockBgColor = settings.getColor(COLOR_LOCK_BG, new Color(128, 128, 128, 30));
            this.lockLockedBgColor = settings.getColor(COLOR_LOCK_LOCKED_BG, Color.DARK_GRAY);
            this.lockOutlineColor = settings.getColor(COLOR_LOCK_OUTLINE, Color.GRAY);
            this.lockIconColor = settings.getColor(COLOR_LOCK_ICON, Color.WHITE);
            this.warningColor = settings.getColor(COLOR_WARNING, Color.YELLOW);
            this.buyColor = settings.getColor(COLOR_BUY, new Color(0, 140, 70));
            this.sellColor = settings.getColor(COLOR_SELL, new Color(170, 40, 40));
            this.panelBgColor = settings.getColor(COLOR_PANEL_BG, new Color(20, 20, 20, 200));
            this.panelTextColor = settings.getColor(COLOR_PANEL_TEXT, Color.WHITE);
            this.lockButtonPos = settings.getString(LOCK_BUTTON_POS, POS_BOTTOM);
            this.extendPreview = settings.getBoolean(EXTEND_PREVIEW, false);
            this.extendLevel = settings.getBoolean(EXTEND_LEVEL, false);
            this.enablePosSize = settings.getBoolean(ENABLE_POS_SIZE, true);
            this.enableExecPanel = settings.getBoolean(ENABLE_EXEC_PANEL, true);
            this.execPanelPos = settings.getString(EXEC_PANEL_POS, CORNER_TOP_LEFT);
            Instrument xInstr = null;
            try {
                xInstr = settings.getInstrument(CROSS_INSTRUMENT);
            } catch (Exception e) {
                // Not selected, or not supported in this context - treat as "not using cross-trade".
            }
            this.crossInstrument = xInstr;
            this.fixedRiskAmount = settings.getInteger(FIXED_RISK_AMOUNT, 1000);
            this.maxContracts = settings.getInteger(MAX_CONTRACTS, 4);
            this.tradeRR = settings.getDouble(TRADE_RR, 1.5);
            this.slippageTicks = settings.getInteger(SLIPPAGE_TICKS, 2);
            this.autoFlatten = settings.getBoolean(AUTO_FLATTEN, false);
            this.fontInfo = settings.getFont(FONT);

            this.entryPath = settings.getPath(PATH_ENTRY);
            this.slPath = settings.getPath(PATH_SL);
            this.tpPath = settings.getPath(PATH_TP);
            this.ghostPath = settings.getPath(PATH_GHOST);
        }
    }

    private transient CachedSettings cachedSettings;

    private ResizePoint entryResize;
    private ResizePoint slResize;
    private Rectangle lastBounds;
    private boolean figuresInitialized;
    private RiskLine ghostLine;
    private boolean showEntryGhost;
    private boolean showSLGhost;

    @Override
    public void initialize(Defaults defaults) {
        entryResize = new RiskResizePoint(this::getEntryPrice);
        slResize = new RiskResizePoint(this::getStopLossPrice);

        var sd = createSD();
        var tab = sd.addTab(get("TAB_GENERAL"));

        var inputs = tab.addGroup(get("LBL_INPUTS"));
        inputs.addRow(new IntegerDescriptor(MAX_RR, get("LBL_MAX_RR"), 5, 1, 20, 1));

        var riskGrp = tab.addGroup(get("LBL_RISK_CALCULATION"));
        riskGrp.addRow(new BooleanDescriptor(ENABLE_POS_SIZE, get("LBL_ENABLE_POS_SIZE"), true));
        riskGrp.addRow(new IntegerDescriptor(FIXED_RISK_AMOUNT, get("LBL_FIXED_RISK_AMOUNT"), 1000, 1, 1000000, 1));

        var execGrp = tab.addGroup(get("LBL_EXECUTION"));
        execGrp.addRow(new BooleanDescriptor(ENABLE_EXEC_PANEL, get("LBL_ENABLE_EXEC_PANEL"), true));
        var panelPosOptions = new ArrayList<NVP>();
        panelPosOptions.add(new NVP(get("LBL_CORNER_TOP_LEFT"), CORNER_TOP_LEFT));
        panelPosOptions.add(new NVP(get("LBL_CORNER_TOP_RIGHT"), CORNER_TOP_RIGHT));
        panelPosOptions.add(new NVP(get("LBL_CORNER_BOTTOM_LEFT"), CORNER_BOTTOM_LEFT));
        panelPosOptions.add(new NVP(get("LBL_CORNER_BOTTOM_RIGHT"), CORNER_BOTTOM_RIGHT));
        execGrp.addRow(new com.motivewave.platform.sdk.common.desc.DiscreteDescriptor(
                EXEC_PANEL_POS, get("LBL_EXEC_PANEL_POS"), CORNER_TOP_LEFT, panelPosOptions));
        execGrp.addRow(new IntegerDescriptor(MAX_CONTRACTS, get("LBL_MAX_CONTRACTS"), 4, 1, 500, 1));
        execGrp.addRow(new DoubleDescriptor(TRADE_RR, get("LBL_TRADE_RR"), 1.5, 0.1, 20.0, 0.1));
        execGrp.addRow(new IntegerDescriptor(SLIPPAGE_TICKS, get("LBL_SLIPPAGE_TICKS"), 2, 0, 50, 1));
        execGrp.addRow(new BooleanDescriptor(AUTO_FLATTEN, get("LBL_AUTO_FLATTEN"), false));
        execGrp.addRow(new InstrumentDescriptor(CROSS_INSTRUMENT, get("LBL_CROSS_INSTRUMENT")));

        var tabDisplay = sd.addTab(get("TAB_DISPLAY"));

        var appearance = tabDisplay.addGroup(get("LBL_GENERAL"));
        appearance.addRow(new FontDescriptor(FONT, get("LBL_FONT"), new Font("SansSerif", Font.PLAIN, 12)));
        appearance.addRow(new IntegerDescriptor(HOVER_AREA_WIDTH, get("LBL_HOVER_AREA_WIDTH"), DEFAULT_HOVER_WIDTH, 1, 1000, 1));
        appearance.addRow(new ColorDescriptor(COLOR_BG, get("LBL_COLOR_BG"), new Color(128, 128, 128, 0)));

        var riskAxisGroup = tabDisplay.addGroup(get("LBL_RISK_AXIS"));
        riskAxisGroup.addRow(new PathDescriptor(PATH_ENTRY, get("LBL_COLOR_ENTRY"), defaults.getLineColor(), 1.0f, null, true, true, true));
        riskAxisGroup.addRow(new PathDescriptor(PATH_SL, get("LBL_COLOR_SL"), defaults.getLineColor(), 1.0f, null, true, true, true));
        riskAxisGroup.addRow(new PathDescriptor(PATH_TP, get("LBL_COLOR_TP"), defaults.getLineColor(), 1.0f, null, true, true, true));
        riskAxisGroup.addRow(new PathDescriptor(PATH_GHOST, get("LBL_COLOR_GHOST"), Color.GRAY, 1.0f, null, true, true, true));
        riskAxisGroup.addRow(new BooleanDescriptor(EXTEND_PREVIEW, get("LBL_EXTEND_PREVIEW"), false));
        riskAxisGroup.addRow(new BooleanDescriptor(EXTEND_LEVEL, get("LBL_EXTEND_LEVEL"), false));

        var colors = tabDisplay.addGroup(get("LBL_BUTTONS"));
        var buttonOptions = new ArrayList<NVP>();
        buttonOptions.add(new NVP(get("LBL_TOP"), POS_TOP));
        buttonOptions.add(new NVP(get("LBL_BOTTOM"), POS_BOTTOM));
        colors.addRow(new com.motivewave.platform.sdk.common.desc.DiscreteDescriptor(LOCK_BUTTON_POS, get("LBL_LOCK_BUTTON_POS"), POS_BOTTOM, buttonOptions));
        colors.addRow(new ColorDescriptor(COLOR_LOCK_ICON, get("LBL_COLOR_LOCK_ICON"), defaults.getTextColor()),
                new ColorDescriptor(COLOR_LOCK_OUTLINE, get("LBL_COLOR_LOCK_OUTLINE"), defaults.getLineColor()));
        colors.addRow(new ColorDescriptor(COLOR_LOCK_BG, get("LBL_COLOR_LOCK_BG"), defaults.getBackgroundColor()),
                new ColorDescriptor(COLOR_LOCK_LOCKED_BG, get("LBL_COLOR_LOCK_LOCKED_BG"), Color.DARK_GRAY));
        colors.addRow(new ColorDescriptor(COLOR_WARNING, get("LBL_COLOR_WARNING"), Color.YELLOW));
        colors.addRow(new ColorDescriptor(COLOR_BUY, get("LBL_COLOR_BUY"), new Color(0, 140, 70)),
                new ColorDescriptor(COLOR_SELL, get("LBL_COLOR_SELL"), new Color(170, 40, 40)));
        colors.addRow(new ColorDescriptor(COLOR_PANEL_BG, get("LBL_COLOR_PANEL_BG"), new Color(20, 20, 20, 200)),
                new ColorDescriptor(COLOR_PANEL_TEXT, get("LBL_COLOR_PANEL_TEXT"), Color.WHITE));

        createRD();
        sd.addQuickSettings(FIXED_RISK_AMOUNT, MAX_CONTRACTS, TRADE_RR, SLIPPAGE_TICKS);
    }

    @Override
    public void clearState() {
        super.clearState();
        clearFigures();
        figuresInitialized = false;
        entryPrice = null;
        stopLossPrice = null;
        lockedToMarket = true;
        hoverPrice = 0;
        lastBounds = null;
        ghostLine = null;
        cachedSettings = null;
    }

    @Override
    protected void precalculate(DataContext ctx) {
        if (cachedSettings == null) {
            cachedSettings = new CachedSettings(getSettings());
        }
    }

    @Override
    protected void calculateValues(DataContext ctx) {
        initializeFiguresIfNeeded();
        if (lockedToMarket) {
            entryPrice = getLatestPrice(ctx);
        }
        updateResizePoints(ctx);
        notifyRedraw();
    }

    // ==================== Visual bar update (DataContext) - unchanged behavior ====================
    @Override
    public void onBarUpdate(DataContext ctx) {
        if (lockedToMarket) {
            entryPrice = getLatestPrice(ctx);
            refreshFigures();
        }
    }

    // ==================== Order bar update (OrderContext) - THIS is where clicks get executed ====================
    @Override
    public void onBarUpdate(OrderContext ctx) {
        reconcileWithAccount(ctx);

        Enums.OrderAction action = pendingAction;
        if (action == null) return;
        pendingAction = null; // single-shot consume: a stale flag never fires twice
        executeEntry(ctx, action);
    }

    /**
     * Order fill/reject/cancel callbacks only fire for orders THIS strategy instance submitted.
     * ctx.getPosition() reflects the same strategy-scoped bookkeeping, not the real account
     * position - so a manual flatten (or a stop/target fill the platform doesn't attribute back
     * to us for some reason) can leave our internal state believing a bracket is still open when
     * the account is actually flat. That's dangerous: a resting stop or target order we placed
     * would still be live and could later fill against a flat book, opening an unintended new
     * position. Checked on every tick (this method already fires every tick because
     * requiresBarUpdates=true) so the self-heal happens promptly.
     *
     * Also handles the MIRROR-IMAGE case (write-up item 5): we think we're FLAT (fresh
     * activation, platform/strategy restart mid-trade, or bracket state was otherwise reset) but
     * the broker shows an open position with no stop/target tracked by this strategy instance at
     * all. In-memory fields (stopOrder, targetOrder, bracketQuantity, ...) don't survive a
     * restart, but a real broker-side position - and possibly real resting orders this instance
     * no longer has Java references to - can. This method cannot safely reconstruct a bracket for
     * a position it doesn't know the entry price, direction, or intended stop distance for -
     * guessing would be worse than not guessing - so this is a loud, throttled WARNING only, not
     * an automatic action. executeEntry()'s own pre-trade accountPosition != 0 check already
     * independently refuses to open a new position on top of this, so blocking isn't the gap here
     * - visibility is.
     */
    private void reconcileWithAccount(OrderContext ctx) {
        boolean weThinkWeHaveABracket = (stopOrder != null) || (targetOrder != null) || bracketQuantity != 0;

        // Check the position for the instrument we actually expect to be holding (falls back to
        // auto-detected/manual Cross Trade instrument, then chart instrument) - see
        // readAccountPosition() for why the no-arg/chart-keyed overload alone isn't sufficient.
        Instrument tradeInstr = expectedTradeInstrument != null
                ? expectedTradeInstrument
                : resolveTradeInstrument(ctx.getDataContext(), ctx.getInstrument());

        float accountPosition;
        try {
            accountPosition = readAccountPosition(ctx, tradeInstr);
        } catch (Exception e) {
            return; // if the account position can't be read, don't act on stale/guessed data
        }

        if (weThinkWeHaveABracket) {
            if (accountPosition == 0) {
                warning("Account is flat but strategy still held bracket state - reconciling and cancelling any resting protective orders");
                try {
                    if (stopOrder != null && stopOrder.isActive()) ctx.cancelOrders(stopOrder);
                    if (targetOrder != null && targetOrder.isActive()) ctx.cancelOrders(targetOrder);
                } catch (Exception e) {
                    error("Failed to cancel orphaned protective orders during reconciliation: " + e.getMessage());
                }
                resetBracket();
                orderInFlight.set(false);
                statusMessage = "Flat (reconciled)";
                statusIsError = false;
                notifyRedraw();
            }
            return;
        }

        // weThinkWeHaveABracket == false from here down: the mirror-image case above.
        if (accountPosition != 0) {
            if (!unprotectedPositionWarned) {
                error("UNPROTECTED POSITION DETECTED: account shows " + accountPosition + " on "
                        + (tradeInstr != null ? tradeInstr.getSymbol() : "unknown instrument")
                        + " with no bracket tracked by this strategy instance (likely a platform/"
                        + "strategy restart mid-trade, a manually-opened position, or another "
                        + "strategy). This tool will NOT guess a stop-loss for a position it did "
                        + "not open - protect or flatten this position manually.");
                statusMessage = "\u26A0 UNPROTECTED POSITION (" + accountPosition + ") - no bracket tracked";
                statusIsError = true;
                unprotectedPositionWarned = true; // don't re-log every tick until this resolves
                notifyRedraw();
            }
        } else if (unprotectedPositionWarned) {
            // Operator resolved it (flattened manually, or it's now a normally-tracked bracket).
            unprotectedPositionWarned = false;
            statusMessage = "Active - flat";
            statusIsError = false;
            notifyRedraw();
        }
    }

    /** Order objects returned across separate callback invocations are not guaranteed to be the
     *  same Java object instance, so identity comparison (==) is unreliable. Compare by the
     *  broker-assigned order ID instead. */
    private boolean sameOrder(Order a, Order b) {
        if (a == null || b == null) return false;
        String idA = a.getOrderId();
        String idB = b.getOrderId();
        return idA != null && idA.equals(idB);
    }

    @Override
    public void onHover(Point2D loc, int flags, DrawContext ctx) {
        lastBounds = ctx.getBounds();

        showEntryGhost = false;
        showSLGhost = false;

        if (isInHoverArea(loc.getX(), ctx)) {
            var instr = ctx.getDataContext().getInstrument();
            hoverPrice = instr.round(ctx.translate2Value(loc.getY()));

            int mouseY = (int) loc.getY();
            if (entryPrice != null && Math.abs(ctx.translateValue(entryPrice) - mouseY) < 10) {
                showEntryGhost = true;
            } else if (stopLossPrice != null && Math.abs(ctx.translateValue(stopLossPrice) - mouseY) < 10) {
                showSLGhost = true;
            }

            if (ghostLine == null) {
                ghostLine = new RiskLine(() -> hoverPrice, this::getGhostLabel, PATH_GHOST);
                addFigure(ghostLine);
            }
        } else {
            removeGhostLine();
        }
        refreshFigures();
    }

    @Override
    public void onHoverLost(Point2D loc, int flags, DrawContext ctx) {
        showEntryGhost = false;
        showSLGhost = false;
        removeGhostLine();
        refreshFigures();
    }

    @Override
    public boolean onClick(Point p, int flags) {
        boolean wasNearEntry = showEntryGhost;
        boolean wasNearSL = showSLGhost;
        showEntryGhost = false;
        showSLGhost = false;

        // Execution panel buttons take priority over the hover-area planning UI.
        if (buyButtonBounds != null && buyButtonBounds.contains(p)) {
            requestEntry(Enums.OrderAction.BUY);
            return true;
        }
        if (sellButtonBounds != null && sellButtonBounds.contains(p)) {
            requestEntry(Enums.OrderAction.SELL);
            return true;
        }

        if (lastBounds == null) return false;

        int hoverAreaLeft = getHoverAreaLeft(lastBounds);
        if (!isInHoverAreaX(p.x, hoverAreaLeft, lastBounds)) return false;

        if (isInLockButtonArea(p.y, lastBounds)) {
            toggleLockToMarket();
            return true;
        }

        handlePricePlacement(wasNearEntry, wasNearSL);
        refreshFigures();
        return true;
    }

    @Override
    public void onResize(ResizePoint rp, DrawContext ctx) {
        if (rp == null) return;

        var instr = ctx.getDataContext().getInstrument();
        double price = instr.round(rp.getValue());

        if (rp == entryResize) {
            entryPrice = price;
        } else if (rp == slResize) {
            stopLossPrice = price;
        }
        notifyRedraw();
    }

    @Override
    public void onEndResize(ResizePoint rp, DrawContext ctx) {
        notifyRedraw();
    }

    @Override
    public MenuDescriptor onMenu(String plotName, Point loc, DrawContext ctx) {
        var items = new ArrayList<MenuItem>();
        items.add(new MenuItem(get("LBL_CLEAR_DRAWINGS"), () -> {
            clearState();
            cachedSettings = new CachedSettings(getSettings());
            initializeFiguresIfNeeded();
        }));
        return new MenuDescriptor(items, true);
    }

    @Override
    public void onSettingsUpdated(DataContext ctx) {
        super.onSettingsUpdated(ctx);
        clearState();
        cachedSettings = new CachedSettings(getSettings());
        initializeFiguresIfNeeded();
    }

    // ==================== Strategy lifecycle ====================

    @Override
    public void onActivate(OrderContext ctx) {
        super.onActivate(ctx);
        statusMessage = "Active - flat";
        statusIsError = false;
        // Check immediately for a pre-existing, untracked position (e.g. a platform/strategy
        // restart mid-trade) rather than waiting for the next bar update - matters most on slower
        // timeframes where the first tick could be a while away. See reconcileWithAccount().
        reconcileWithAccount(ctx);
        notifyRedraw();
    }

    @Override
    public void onDeactivate(OrderContext ctx) {
        super.onDeactivate(ctx); // default behavior flattens position if user enabled that option
        resetBracket();
        orderInFlight.set(false);
        statusMessage = "Inactive";
        notifyRedraw();
    }

    @Override
    public void onReset(OrderContext ctx) {
        super.onReset(ctx);
        resetBracket();
        orderInFlight.set(false);
        statusMessage = "Reset";
        notifyRedraw();
    }

    @Override
    public void onPositionClosed(OrderContext ctx) {
        super.onPositionClosed(ctx);
        resetBracket();
        orderInFlight.set(false);
        statusMessage = "Flat";
        statusIsError = false;
        notifyRedraw();
    }

    @Override
    public void onOrderFilled(OrderContext ctx, Order order) {
        super.onOrderFilled(ctx, order);
        try {
            if (sameOrder(order, entryOrder)) {
                handleEntryFilled(ctx, order);
            } else if (sameOrder(order, stopOrder)) {
                handleProtectiveFill(ctx, order, true);
            } else if (sameOrder(order, targetOrder)) {
                handleProtectiveFill(ctx, order, false);
            }
        } catch (Exception e) {
            error("onOrderFilled handling failed: " + e.getMessage());
            statusMessage = "ERROR: " + e.getMessage();
            statusIsError = true;
        }
        notifyRedraw();
    }

    /**
     * Handles a fill on either leg of the protective bracket (stop or target) - full OR partial.
     * Order.isFilled() is what actually distinguishes the two; Order.getFilledAsFloat() alone
     * can't, since it's a cumulative count that also fires on every partial tick along the way,
     * not just on the final one. Treating ANY fill event as "the whole thing filled" (the
     * previous behaviour) cancelled the sibling at its ORIGINAL full size even though only part
     * of the position had actually closed on this leg, leaving the rest of the position naked
     * and marking the strategy flat while a real position remained open at the broker. See
     * write-up item 8 - the highest-priority fix, ahead of even the OCO limitation (item 3),
     * since that one is a rare timing window and this one triggers on any partial fill of an
     * exit order.
     *
     * On a genuine full fill: cancel the sibling and reset the bracket, exactly as before.
     *
     * On a partial fill: the sibling is NOT cancelled and bracket state is NOT reset - part of
     * the position is still open and still needs protecting. Instead the sibling is resized down
     * to the quantity that's actually still open (bracketQuantity minus what this leg has closed
     * so far), so it can never try to protect - or exit - more than the real remaining position.
     * This uses the same cancel-then-resubmit pattern as the initial bracket submission, with the
     * same brief unprotected window between the two calls documented elsewhere (item 3) as an
     * inherent SDK limitation.
     */
    private void handleProtectiveFill(OrderContext ctx, Order filledOrder, boolean filledWasStop) {
        Order sibling = filledWasStop ? targetOrder : stopOrder;
        String legName = filledWasStop ? "Stop loss" : "Target";
        String siblingName = filledWasStop ? "target" : "stop";

        boolean fullyFilled;
        try {
            fullyFilled = filledOrder.isFilled();
        } catch (Exception e) {
            // Can't confirm partial vs full - assume NOT fully filled. Wrongly cancelling live
            // protection is far worse than leaving a stale sibling order active for one more tick.
            fullyFilled = false;
        }

        float closedSoFar;
        try {
            closedSoFar = filledOrder.getFilledAsFloat();
        } catch (Exception e) {
            closedSoFar = bracketQuantity; // unknown - assume worst case (fully closed) below
        }
        float remaining = bracketQuantity - closedSoFar;

        // Anything under one whole contract remaining is effectively fully closed (contracts are
        // integer-quantized) - treat it as a full close rather than attempting a zero-quantity
        // resize order below.
        if (fullyFilled || remaining < 1f) {
            info(legName + " filled - cancelling " + siblingName);
            try {
                if (sibling != null && sibling.isActive()) ctx.cancelOrders(sibling);
            } catch (Exception e) {
                error("Failed to cancel " + siblingName + " after " + legName.toLowerCase() + " fill: " + e.getMessage());
            }
            statusMessage = filledWasStop ? "Stopped out" : "Target hit";
            statusIsError = false;
            resetBracket();
            return;
        }

        // Partial fill: leave both orders working, but resize the sibling to match what's
        // actually still open. Same no-broker-side-OCO limitation applies here as at the initial
        // bracket submission (see the doc comment there, write-up item 3) - the freshly-recreated
        // sibling below is just as independent of the leg that's still filling as the original
        // pair was.
        int originalBracketQty = bracketQuantity;
        warning(legName + " PARTIALLY filled (" + closedSoFar + "/" + originalBracketQty
                + ") - resizing " + siblingName + " to remaining " + remaining);

        try {
            if (sibling != null && sibling.isActive()) {
                Enums.OrderAction exitAction = sibling.getAction();
                int remainingQty = (int) remaining;
                if (filledWasStop) {
                    Float limitPx = sibling.getLimitPrice();
                    ctx.cancelOrders(sibling);
                    if (limitPx == null) {
                        // Shouldn't happen per the SDK docs (the target is always a limit order),
                        // but the sibling is already cancelled at this point - treat a missing
                        // price the same as any other resize failure below rather than silently
                        // leaving the position with no replacement order at all.
                        throw new IllegalStateException("target order has no limit price to resize from");
                    }
                    targetOrder = ctx.createLimitOrder(exitAction, Enums.TIF.DAY, remainingQty, limitPx);
                    ctx.submitOrders(targetOrder);
                } else {
                    Float stopPx = sibling.getStopPrice();
                    ctx.cancelOrders(sibling);
                    if (stopPx == null) {
                        throw new IllegalStateException("stop order has no stop price to resize from");
                    }
                    stopOrder = ctx.createStopOrder(exitAction, Enums.TIF.DAY, remainingQty, stopPx);
                    ctx.submitOrders(stopOrder);
                }
            }
        } catch (Exception e) {
            error("Failed to resize " + siblingName + " after partial " + legName.toLowerCase()
                    + " fill - flattening for safety: " + e.getMessage());
            statusMessage = "Partial-fill resize FAILED - flattening";
            statusIsError = true;
            try {
                flattenInstrument(ctx, resolveFlattenInstrument(ctx, filledOrder));
            } catch (Exception e2) {
                error("Failed to flatten after partial-fill resize failure: " + e2.getMessage());
                statusMessage = "FAILED TO FLATTEN - close manually now";
            }
            resetBracket();
            return;
        }

        // Reflect what's still actually open, so a subsequent partial fill (or the per-tick
        // reconciliation check) computes the right remaining amount.
        bracketQuantity = (int) remaining;
        statusMessage = legName + " partially filled (" + (int) closedSoFar + "/" + originalBracketQty + ") - "
                + siblingName + " resized to " + bracketQuantity;
        statusIsError = false;
    }

    @Override
    public void onOrderRejected(OrderContext ctx, Order order) {
        super.onOrderRejected(ctx, order);
        if (sameOrder(order, entryOrder)) {
            error("Entry order rejected");
            statusMessage = "Entry REJECTED";
            statusIsError = true;
            entryOrder = null;
            orderInFlight.set(false);
        } else if (sameOrder(order, stopOrder) || sameOrder(order, targetOrder)) {
            // A protective order failing to be placed is the single most dangerous failure
            // mode for this tool: it would leave a filled position naked. Flatten immediately -
            // targeting the instrument the rejected order was actually for (see
            // flattenInstrument()), not just whatever closeAtMarket() implicitly resolves to.
            error("Protective order rejected - flattening for safety");
            statusMessage = "Protective order REJECTED - flattening";
            statusIsError = true;
            try {
                flattenInstrument(ctx, resolveFlattenInstrument(ctx, order));
            } catch (Exception e) {
                error("Failed to flatten after protective-order rejection: " + e.getMessage());
                statusMessage = "FAILED TO FLATTEN - close manually now";
            }
            resetBracket();
        }
        notifyRedraw();
    }

    @Override
    public void onOrderCancelled(OrderContext ctx, Order order) {
        super.onOrderCancelled(ctx, order);
        if (sameOrder(order, entryOrder)) {
            entryOrder = null;
            orderInFlight.set(false);
            if (statusMessage == null || !statusIsError) {
                statusMessage = "Entry cancelled";
            }
        }
        notifyRedraw();
    }

    // ==================== Execution: request -> validate -> submit ====================

    /** Called from onClick() (UI thread). Only ever sets a flag - never touches OrderContext. */
    private void requestEntry(Enums.OrderAction action) {
        if (getState() != Enums.StrategyState.ACTIVE) {
            statusMessage = "Activate the strategy first";
            statusIsError = true;
            notifyRedraw();
            return;
        }
        if (!orderInFlight.compareAndSet(false, true)) {
            // A click is already being processed, or a position is already being managed.
            info("Ignoring click - order already in flight");
            return;
        }
        if (stopLossPrice == null) {
            statusMessage = "Set a stop loss first";
            statusIsError = true;
            orderInFlight.set(false);
            notifyRedraw();
            return;
        }
        pendingAction = action;
        statusMessage = "Submitting " + (action == Enums.OrderAction.BUY ? "BUY" : "SELL") + "...";
        statusIsError = false;
        notifyRedraw();
    }

    /** Called from onBarUpdate(OrderContext) - runs with full order-management access. */
    private void executeEntry(OrderContext ctx, Enums.OrderAction action) {
        try {
            Instrument instr = ctx.getInstrument();
            DataContext dataCtx = ctx.getDataContext();
            Instrument tradeInstr = resolveTradeInstrument(dataCtx, instr);

            String routingConflict = checkRoutingConflict(dataCtx, instr);
            if (routingConflict != null) {
                fail("Routing conflict - " + routingConflict + " - resolve before trading");
                return;
            }

            // Check the real account position, not ctx.getPosition() - the latter only reflects
            // orders THIS strategy instance has submitted, so it would miss a position opened
            // manually via the Trade panel or by another strategy on the same account. Checked
            // against the actually-traded instrument (tradeInstr), which may differ from the
            // chart instrument under Cross Trade - the chart instrument's position is the wrong
            // thing to check in that case.
            float accountPosition;
            try {
                accountPosition = readAccountPosition(ctx, tradeInstr);
            } catch (Exception e) {
                fail("Could not read account position - aborting for safety");
                return;
            }
            if (accountPosition != 0) {
                fail("Position already open on this account - flatten before entering a new trade");
                return;
            }
            Double sl = stopLossPrice;
            if (sl == null) {
                fail("No stop loss set");
                return;
            }

            double marketPx = resolveEntryPrice(instr, action);
            if (marketPx <= 0) {
                fail("Could not resolve a valid market price");
                return;
            }

            boolean isLong = action == Enums.OrderAction.BUY;
            if (isLong && marketPx <= sl) {
                fail("Stop is on the wrong side of the market for a LONG (SL must be below entry)");
                return;
            }
            if (!isLong && marketPx >= sl) {
                fail("Stop is on the wrong side of the market for a SHORT (SL must be above entry)");
                return;
            }

            int fixedRiskAmount = getSettings().getInteger(FIXED_RISK_AMOUNT, 1000);
            int maxContracts = getSettings().getInteger(MAX_CONTRACTS, 4);
            int slippageTicks = getSettings().getInteger(SLIPPAGE_TICKS, 2);

            int quantity = computeQuantity(dataCtx, instr, marketPx, sl, slippageTicks, fixedRiskAmount, maxContracts);
            if (quantity <= 0) {
                fail("Calculated quantity is 0 (risk per contract exceeds fixed risk amount)");
                return;
            }

            armedStopLossPrice = sl;
            expectedTradeInstrument = tradeInstr;
            // DELIBERATELY submitted without an explicit instrument (the 2-arg overload, not
            // createMarketOrder(tradeInstr, action, quantity) as flattenInstrument() uses). The
            // original review's own testing showed this implicit call already fills correctly on
            // the Cross-Trade instrument (MES) despite the SDK docs saying it defaults to the
            // primary/chart instrument - i.e. MotiveWave's Cross Trade feature appears to
            // transparently intercept and reroute the platform's own default-instrument order
            // flow. Passing tradeInstr explicitly here could bypass that interception in a way
            // that's unverifiable from the API surface alone, and risks breaking a path that
            // currently works. verifyFillInstrument() below (in handleEntryFilled) exists
            // specifically as the after-the-fact safety net for this residual uncertainty.
            // TODO: confirm with MotiveWave support whether explicit instrument targeting on
            // entry conflicts with platform-level Cross Trade routing; switch to the 3-arg
            // overload once that's settled either way.
            entryOrder = ctx.createMarketOrder(action, quantity);
            ctx.submitOrders(entryOrder); // synchronous per SDK docs; runs on the order-mgmt thread, not the UI thread
            statusMessage = "Submitted " + (isLong ? "BUY" : "SELL") + " " + quantity + " @ market";
            statusIsError = false;
            notifyRedraw();
        } catch (Exception e) {
            fail("Order submission failed: " + e.getMessage());
        }
    }

    private void fail(String message) {
        error(message);
        statusMessage = message;
        statusIsError = true;
        orderInFlight.set(false);
        pendingAction = null;
        notifyRedraw();
    }

    /**
     * Resolves the price used for BOTH sizing and direction validation immediately before
     * submission. Uses ask for buys / bid for sells (the side you'd actually pay), falling
     * back to last trade price if the quote is unavailable (e.g. thin book at open).
     */
    private double resolveEntryPrice(Instrument instr, Enums.OrderAction action) {
        double px = action == Enums.OrderAction.BUY ? instr.getAskPrice() : instr.getBidPrice();
        if (px <= 0) px = instr.getLastPrice();
        return px;
    }

    /**
     * Resolves the instrument actually being traded, auto-detected from MotiveWave's own
     * Cross Trade state via DataContext.getTradeInstrument() - "the trade instrument
     * associated with this context; may be different than the primary instrument if cross
     * trade is enabled." This is the platform's own record of what's really being routed,
     * so it takes priority over everything else.
     *
     * Falls back, in order, to: the manual Cross-Trade Instrument setting (kept as a backstop
     * for SDK builds/contexts where getTradeInstrument() throws or isn't wired up), then the
     * chart/primary instrument itself.
     *
     * Price LEVELS (bid/ask, SL/TP price rounding) still come from the chart instrument -
     * standard/micro CME pairs quote at the identical price level and tick size, only their
     * dollar-per-point value and fill routing differ, so only the money/routing side needs
     * the resolved instrument.
     */
    private Instrument resolveTradeInstrument(DataContext dataCtx, Instrument chartInstrument) {
        if (dataCtx != null) {
            try {
                Instrument auto = dataCtx.getTradeInstrument();
                if (auto != null) return auto;
            } catch (Exception e) {
                // Not available in this context/SDK build - fall through to the manual setting.
            }
        }
        Instrument manual = cachedSettings != null ? cachedSettings.crossInstrument : null;
        return manual != null ? manual : chartInstrument;
    }

    /**
     * Pre-trade routing sanity check (write-up item 9). verifyFillInstrument() (in
     * handleEntryFilled) is the post-fill safety net for "the fill landed somewhere we didn't
     * expect" - necessarily reactive, since the fill instrument doesn't exist until after
     * submission. This is the proactive companion: a conflict we CAN see coming, ahead of time.
     *
     * If a manual Cross-Trade Instrument is configured AND MotiveWave's own auto-detected
     * DataContext.getTradeInstrument() disagrees with it, that's two different sources of "what
     * instrument is this really going to trade" actively contradicting each other - not silent
     * uncertainty, an unresolved conflict the operator needs to fix (clear the manual override,
     * or fix the chart's actual Cross Trade state) before this strategy will submit an order.
     * Returns a human-readable description of the conflict, or null if there isn't one.
     */
    private String checkRoutingConflict(DataContext dataCtx, Instrument chartInstrument) {
        Instrument manual = cachedSettings != null ? cachedSettings.crossInstrument : null;
        if (manual == null || dataCtx == null) return null; // nothing manually configured to conflict with

        Instrument auto;
        try {
            auto = dataCtx.getTradeInstrument();
        } catch (Exception e) {
            return null; // auto-detection unavailable here - nothing to compare the manual setting against
        }
        // auto == null means Cross Trade isn't active per the platform right now - the manual
        // setting is simply unused in that case, not wrong, so that's not a conflict either.
        if (auto == null) return null;

        String autoSym = auto.getSymbol();
        String manualSym = manual.getSymbol();
        if (autoSym != null && !autoSym.equals(manualSym)) {
            return "manual Cross-Trade Instrument (" + manualSym + ") disagrees with MotiveWave's "
                    + "detected trade instrument (" + autoSym + ")";
        }
        return null;
    }

    /** Money-math alias for resolveTradeInstrument() - kept as a separate name at call sites
     *  that only care about the point/tick VALUE, not routing, for readability. */
    private Instrument resolveMoneyInstrument(DataContext dataCtx, Instrument chartInstrument) {
        return resolveTradeInstrument(dataCtx, chartInstrument);
    }

    /**
     * Reads the account's real position for the instrument that's actually being traded, not
     * necessarily the chart instrument. ctx.getAccountPosition() (no-arg) is documented as
     * returning the position "for the default position" - i.e. chart-instrument-keyed - which
     * is the wrong thing to check once Cross Trade is routing fills to a different instrument.
     * Prefers the instrument-specific overload; falls back to the no-arg overload if that isn't
     * available (older SDK build) or if we couldn't resolve a trade instrument at all.
     */
    private float readAccountPosition(OrderContext ctx, Instrument tradeInstrument) throws Exception {
        if (tradeInstrument != null) {
            try {
                return ctx.getAccountPosition(tradeInstrument);
            } catch (Exception e) {
                // Fall through to the default-position overload below.
            }
        }
        return ctx.getAccountPosition();
    }

    /**
     * Compares the instrument an order actually filled as (Order.getInstrument()) against the
     * instrument we expected at submission time (resolveTradeInstrument(), cached as
     * expectedTradeInstrument). A mismatch means our risk-per-contract math - and therefore the
     * submitted quantity - was computed against the wrong instrument's point/tick value, which
     * is a real-money sizing error, not a cosmetic one. Returns true if the fill matches (or if
     * either side is unknown, since we can't accuse a mismatch we can't confirm).
     */
    private boolean verifyFillInstrument(Order order, Instrument expected) {
        if (order == null || expected == null) return true;
        Instrument filled;
        try {
            filled = order.getInstrument();
        } catch (Exception e) {
            return true; // can't confirm either way - don't false-alarm on this alone
        }
        if (filled == null) return true;
        String filledSym = filled.getSymbol();
        String expectedSym = expected.getSymbol();
        return filledSym != null && filledSym.equals(expectedSym);
    }

    /**
     * Determines the best available instrument to flatten for a given order event. Prefers the
     * order's own instrument (Order.getInstrument() - the most concrete "this is literally where
     * it happened" source, available on both fills and rejections), falls back to what we
     * expected at entry-submission time (expectedTradeInstrument), then to fresh auto-detection.
     */
    private Instrument resolveFlattenInstrument(OrderContext ctx, Order order) {
        if (order != null) {
            try {
                Instrument orderInstr = order.getInstrument();
                if (orderInstr != null) return orderInstr;
            } catch (Exception e) {
                // Fall through to the cached/auto-detected instrument below.
            }
        }
        if (expectedTradeInstrument != null) return expectedTradeInstrument;
        return resolveTradeInstrument(ctx.getDataContext(), ctx.getInstrument());
    }

    /**
     * Flattens the REAL account position for a SPECIFIC instrument, rather than relying on
     * ctx.closeAtMarket() - confirmed via the SDK javadoc to take no instrument parameter at
     * all ("Closes the position held by this strategy"), which is an ambiguous target once
     * Cross Trade is routing fills to an instrument other than the chart instrument, and this
     * codebase has no way to confirm from documentation alone which instrument it resolves to
     * in that case.
     *
     * Instead: read the actual broker-side position for the given instrument
     * (getAccountPositionAsFloat(Instrument) - confirmed to exist) and submit an explicit,
     * same-instrument offsetting market order (createMarketOrder(Instrument, action, qty) -
     * also confirmed to exist). This is correct regardless of what instrument any given order
     * was originally created against, since it queries the real position directly rather than
     * trusting our own bookkeeping.
     *
     * Falls back to closeAtMarket() only if no instrument could be resolved at all, or if the
     * instrument-targeted attempt itself throws - so a partial/unexpected SDK response still
     * gets a flatten attempt rather than none.
     */
    private void flattenInstrument(OrderContext ctx, Instrument instr) throws Exception {
        if (instr != null) {
            try {
                float pos = ctx.getAccountPositionAsFloat(instr);
                if (pos == 0) return; // nothing open on this instrument - nothing to flatten
                Enums.OrderAction closeAction = pos > 0 ? Enums.OrderAction.SELL : Enums.OrderAction.BUY;
                Order closeOrder = ctx.createMarketOrder(instr, closeAction, Math.abs(pos));
                ctx.submitOrders(closeOrder);
                return;
            } catch (Exception e) {
                error("Instrument-targeted flatten failed for " + instr.getSymbol()
                        + ", falling back to closeAtMarket(): " + e.getMessage());
                // fall through to closeAtMarket() below
            }
        }
        ctx.closeAtMarket(); // last resort - no instrument to target, or the targeted attempt failed
    }

    /**
     * Position sizing with a conservative slippage buffer.
     * Risk per contract = |entry - stop| converted to dollars via the instrument's point size/value.
     * To keep ACTUAL risk under the configured maximum even if the market order fills worse than
     * the price used here, the stop distance is WIDENED by (slippageTicks * tickSize) before
     * computing risk-per-contract. Widening the assumed distance can only reduce the resulting
     * quantity (never increase it), which is the safe direction for a risk cap.
     */
    private int computeQuantity(DataContext dataCtx, Instrument instr, double entryPx, double slPx,
                                 int slippageTicks, int fixedRiskAmount, int maxContracts) {
        double stopDistance = Math.abs(entryPx - slPx);
        if (stopDistance <= 0) return 0;

        Instrument moneyInstr = resolveMoneyInstrument(dataCtx, instr);
        double tickSize = moneyInstr.getTickSize();
        double pointSize = moneyInstr.getPointSize();
        double pointValue = moneyInstr.getPointValue();
        if (pointSize <= 0 || pointValue <= 0) return 0;

        double buffer = slippageTicks * tickSize;
        double sizingDistance = stopDistance + buffer;

        double riskPerContract = (sizingDistance / pointSize) * pointValue;
        if (riskPerContract <= 0) return 0;

        int qty = (int) Math.floor(fixedRiskAmount / riskPerContract);
        return Math.min(qty, maxContracts);
    }

    /**
     * Handles a fill (full or partial) on the entry order: computes the actual risk taken,
     * checks it against the configured maximum, optionally trims the position if the fill
     * breached the cap, then submits the protective stop and target.
     *
     * NOTE ON PARTIAL FILLS: this method may run more than once for the same entryOrder as
     * partial fills arrive. Each time, it cancels any existing bracket and resubmits sized to
     * the current cumulative filled quantity, per Order.getFilledAsFloat(). There is an
     * unavoidable brief window between the cancel and the resubmit where the position is
     * unprotected - see the write-up for why this is an inherent SDK limitation, not an
     * oversight.
     */
    private void handleEntryFilled(OrderContext ctx, Order order) {
        float filledQty = order.getFilledAsFloat();
        if (filledQty <= 0) return;

        if (armedEntryPrice == null) {
            try { armedEntryPrice = (double) order.getAvgFillPrice(); } catch (Exception ignored) { }
        }

        boolean isLong = order.isBuy();
        Double sl = armedStopLossPrice;
        if (sl == null) {
            error("Entry filled but armed stop-loss price is missing - flattening for safety");
            statusMessage = "Missing SL after fill - flattening";
            statusIsError = true;
            try {
                flattenInstrument(ctx, resolveFlattenInstrument(ctx, order));
            } catch (Exception e) {
                error("Failed to flatten after missing-SL fill: " + e.getMessage());
                statusMessage = "FAILED TO FLATTEN - close manually now";
            }
            resetBracket();
            return;
        }

        // Confirm the fill actually landed on the instrument our risk math assumed. A mismatch
        // means quantity was sized off the wrong instrument's point/tick value - a real sizing
        // error, not a display issue - so this fails closed the same way a missing SL does.
        if (!verifyFillInstrument(order, expectedTradeInstrument)) {
            String filledSym;
            try { filledSym = order.getInstrument() != null ? order.getInstrument().getSymbol() : "unknown"; }
            catch (Exception e) { filledSym = "unknown"; }
            error("Entry filled on " + filledSym + " but expected " + expectedTradeInstrument.getSymbol()
                    + " - sizing was computed against the wrong instrument. Flattening for safety.");
            statusMessage = "Instrument mismatch on fill (" + filledSym + ") - flattening";
            statusIsError = true;
            try {
                flattenInstrument(ctx, resolveFlattenInstrument(ctx, order));
            } catch (Exception e) {
                error("Failed to flatten after instrument-mismatch fill: " + e.getMessage());
                statusMessage = "FAILED TO FLATTEN - close manually now";
            }
            resetBracket();
            return;
        }

        Instrument instr = ctx.getInstrument();
        Instrument moneyInstr = resolveMoneyInstrument(ctx.getDataContext(), instr);
        float avgFill = order.getAvgFillPrice();
        double stopDistance = Math.abs(avgFill - sl);
        double riskPerContract = (stopDistance / moneyInstr.getPointSize()) * moneyInstr.getPointValue();
        double actualRisk = riskPerContract * filledQty;

        int maxRiskSetting = getSettings().getInteger(FIXED_RISK_AMOUNT, 1000);
        boolean autoFlatten = getSettings().getBoolean(AUTO_FLATTEN, false);

        if (riskPerContract > 0 && actualRisk > maxRiskSetting + 0.0001) {
            warning(String.format("Slippage breach: actual risk $%.2f exceeds configured max $%d",
                    actualRisk, maxRiskSetting));
            if (autoFlatten) {
                /*
                 * KNOWN LIMITATION - ACCEPTED, NOT FIXED (write-up item 6): this submits a
                 * reduce-quantity order and then immediately ASSUMES it filled by setting
                 * filledQty = safeQty below, with no confirmation loop. Unlike the entry order
                 * (tracked through onOrderFilled/handleEntryFilled, including partial fills) or
                 * the protective legs (tracked through handleProtectiveFill), this reduceOrder is
                 * fire-and-forget: it is never checked against Order.isFilled() or
                 * Order.getFilledAsFloat(), and a rejection or partial fill of the TRIM ITSELF
                 * goes undetected.
                 *
                 * Concretely: if this reduceOrder is rejected, partially fills, or is delayed,
                 * the stop/target submitted a few lines below are sized to safeQty - the
                 * ASSUMED post-trim quantity - while the REAL position at the broker may still be
                 * the full, untrimmed filledQty. That would leave the untrimmed excess completely
                 * unprotected, silently.
                 *
                 * Building this properly would mean giving the trim order the same order-tracking
                 * treatment as the entry order: caching its ID, confirming the fill (or rejection)
                 * in the order-callback methods before trusting a new quantity, and deciding what
                 * to do (retry? flatten everything?) if it doesn't fill as expected. That's a
                 * real feature, not a one-line fix, so it hasn't been built - this branch stays
                 * OFF BY DEFAULT (AUTO_FLATTEN defaults to false) until it is. Do not enable this
                 * setting in a live/prop account without being aware of this gap.
                 */
                int safeQty = (int) Math.floor(maxRiskSetting / riskPerContract);
                int excess = (int) filledQty - safeQty;
                if (excess > 0 && safeQty >= 0) {
                    try {
                        Enums.OrderAction reduceAction = isLong ? Enums.OrderAction.SELL : Enums.OrderAction.BUY;
                        Order reduceOrder = ctx.createMarketOrder(reduceAction, excess);
                        ctx.submitOrders(reduceOrder);
                        filledQty = safeQty; // ASSUMED, not confirmed - see comment above
                        statusMessage = "Trimmed " + excess + " contract(s) after slippage breach (unconfirmed)";
                    } catch (Exception e) {
                        error("Auto-trim failed: " + e.getMessage());
                    }
                }
            }
        }

        double rr = getSettings().getDouble(TRADE_RR, 1.5);
        double target = isLong ? avgFill + stopDistance * rr : avgFill - stopDistance * rr;
        target = instr.round(target);
        double roundedSl = instr.round(sl);

        // Cancel any existing bracket before resubmitting (handles the partial-fill case cleanly).
        try {
            if (stopOrder != null && stopOrder.isActive()) ctx.cancelOrders(stopOrder);
            if (targetOrder != null && targetOrder.isActive()) ctx.cancelOrders(targetOrder);
        } catch (Exception e) {
            error("Failed to cancel prior bracket before resubmitting: " + e.getMessage());
        }

        int qty = (int) filledQty;
        Enums.OrderAction exitAction = isLong ? Enums.OrderAction.SELL : Enums.OrderAction.BUY;
        try {
            /*
             * KNOWN LIMITATION - ACCEPTED, NOT FIXABLE (write-up item 3): stopOrder and
             * targetOrder below are two entirely INDEPENDENT orders. Confirmed directly against
             * the current OrderContext javadoc method list: there is no createOCO(), no
             * linkOrders(), no bracket/OCO concept anywhere on OrderContext. MotiveWave's SDK
             * gives custom strategies no broker-side mechanism to say "these two orders are
             * mutually exclusive."
             *
             * The ENTIRE OCO behaviour this tool provides is handleProtectiveFill() (see
             * onOrderFilled below) noticing one leg fill and cancelling the other in response.
             * That means there is a real, unavoidable window between "leg A fills at the broker"
             * and "our cancel-leg-B call finishes" during which BOTH orders are technically still
             * live. If a disconnect, platform restart, or sufficiently fast fill on the other leg
             * happens in exactly that window, both could fill - there is no broker-side safety
             * net for that, and none can be added from a custom strategy with this SDK.
             *
             * This is an inherent SDK limitation, not an oversight, and not something a
             * different implementation here could code around - it is documented rather than
             * "fixed" for that reason.
             */
            stopOrder = ctx.createStopOrder(exitAction, Enums.TIF.DAY, qty, (float) roundedSl);
            targetOrder = ctx.createLimitOrder(exitAction, Enums.TIF.DAY, qty, (float) target);
            ctx.submitOrders(stopOrder, targetOrder);
        } catch (Exception e) {
            error("Failed to submit protective bracket - flattening for safety: " + e.getMessage());
            statusMessage = "Bracket submit FAILED - flattening";
            statusIsError = true;
            try {
                flattenInstrument(ctx, resolveFlattenInstrument(ctx, order));
            } catch (Exception e2) {
                error("Failed to flatten after bracket-submit failure: " + e2.getMessage());
                statusMessage = "FAILED TO FLATTEN - close manually now";
            }
            resetBracket();
            return;
        }

        setStopPrice((float) roundedSl);
        setTargetPrice((float) target);
        bracketQuantity = qty;
        statusMessage = String.format("Filled %d @ %s | SL %s | TP %s", qty,
                instr.format(avgFill), instr.format(roundedSl), instr.format(target));
        statusIsError = false;
        orderInFlight.set(false);
        notifyRedraw();
    }

    private void resetBracket() {
        entryOrder = null;
        stopOrder = null;
        targetOrder = null;
        armedStopLossPrice = null;
        armedEntryPrice = null;
        expectedTradeInstrument = null;
        bracketQuantity = 0;
        setStopPrice(null);
        setTargetPrice(null);
    }

    // ==================== Helper Methods (visual - unchanged from original) ====================

    private void initializeFiguresIfNeeded() {
        if (figuresInitialized) return;

        addFigure(new BackgroundFigure());

        int maxRR = getSettings().getInteger(MAX_RR, 5);
        for (int ratio = 1; ratio <= maxRR; ratio++) {
            final int r = ratio;
            addFigure(new RiskLine(() -> getTPPreviewPrice(r), r + ":1", PATH_TP));
        }

        addFigure(new RiskLine(this::getDisplayEntryPrice, "E", PATH_ENTRY));
        addFigure(new RiskLine(this::getDisplayStopLossPrice, this::getSLLineLabel, PATH_SL));

        addFigure(new LockButton());
        addFigure(new PositionSizeFigure());
        addFigure(new ExecutionPanelFigure());

        figuresInitialized = true;
    }

    private void updateResizePoints(DataContext ctx) {
        var series = ctx.getDataSeries();
        if (series == null || series.size() == 0) return;

        long lastTime = series.getStartTime(series.size() - 1);
        updateResizePoint(entryResize, entryPrice, lastTime, !lockedToMarket);
        // Hide the draggable SL handle once a bracket is live (armedStopLossPrice != null) - see
        // getDisplayStopLossPrice() for why. A visible-but-inert drag handle sitting at a stale
        // position would visually compete with the real SL line drawn at the armed price.
        updateResizePoint(slResize, stopLossPrice, lastTime, armedStopLossPrice == null);
    }

    private void updateResizePoint(ResizePoint rp, Double price, long time, boolean shouldShow) {
        if (rp == null) return;

        boolean hasPrice = price != null && shouldShow;
        boolean isShowing = getFigures().contains(rp);

        if (hasPrice) {
            rp.setLocation(time, price);
            if (!isShowing) addFigure(rp);
        } else if (isShowing) {
            removeFigure(rp);
        }
    }

    /** Preview R-multiple lines (purely visual reference grid - not tied to the trade R:R setting).
     *  Uses the display (frozen-once-filled) entry/SL so these lock in place once a trade is live,
     *  same as the entry and SL lines themselves. */
    private Double getTPPreviewPrice(int ratio) {
        Double entry = getDisplayEntryPrice();
        Double sl = getDisplayStopLossPrice();
        if (entry == null || sl == null) return null;
        double risk = Math.abs(entry - sl);
        if (risk == 0) return null;
        boolean isLong = entry > sl;
        return isLong ? entry + (risk * ratio) : entry - (risk * ratio);
    }

    private void refreshFigures() {
        DataContext ctx = getDataContext();
        if (ctx != null) {
            updateResizePoints(ctx);
        }
        notifyRedraw();
    }

    private double getLatestPrice(DataContext ctx) {
        var series = ctx.getDataSeries();
        int size = series.size();
        return size == 0 ? 0 : series.getClose(size - 1);
    }

    private String getGhostLabel() {
        if (lockedToMarket) return "SL";
        if (entryPrice == null) return "E";
        if (stopLossPrice == null) return "SL";
        return "E";
    }

    /**
     * The persistent "SL" line's price source. While a bracket is live, this returns
     * armedStopLossPrice (the price the real resting stop order was actually submitted at) -
     * NOT the draggable planning field (stopLossPrice) - so the line drawn on the chart can never
     * visually disagree with the real order. stopLossPrice itself is frozen while live (see
     * handlePricePlacement()/updateResizePoints()), but sourcing the display from the armed price
     * directly means this guarantee holds even if that freeze were ever bypassed.
     */
    private Double getDisplayStopLossPrice() {
        return armedStopLossPrice != null ? armedStopLossPrice : stopLossPrice;
    }

    /** Same idea as getDisplayStopLossPrice(), but for entry: once filled, returns the real
     *  average fill price instead of the live/lockedToMarket-following planning field, so the
     *  entry line and every RR-multiple preview line freeze at the real fill instead of drifting
     *  with the market while the trade is live. */
    private Double getDisplayEntryPrice() {
        return armedEntryPrice != null ? armedEntryPrice : entryPrice;
    }

    /** Distinguishes the live/non-draggable state from the planning state on the SL line itself,
     *  not just via the (easy to miss) absence of a drag handle. */
    private String getSLLineLabel() {
        return armedStopLossPrice != null ? "SL \uD83D\uDD12" : "SL";
    }

    private void removeGhostLine() {
        hoverPrice = 0;
        if (ghostLine != null) {
            removeFigure(ghostLine);
            ghostLine = null;
        }
    }

    private void toggleLockToMarket() {
        lockedToMarket = !lockedToMarket;
        if (lockedToMarket) {
            entryPrice = getLatestPrice(getDataContext());
        }
        refreshFigures();
    }

    private void handlePricePlacement(boolean wasNearEntry, boolean wasNearSL) {
        // Once a bracket is live (armedStopLossPrice != null), stopLossPrice is frozen - a click
        // here can't affect the real resting stop, so letting it silently overwrite the planning
        // field would just set up a "time bomb": the field would jump to a stale value the moment
        // the bracket resets. entryPrice remains editable since it carries no live-order safety
        // implication.
        boolean bracketLive = armedStopLossPrice != null;
        if (lockedToMarket) {
            if (!bracketLive) stopLossPrice = hoverPrice;
        } else if (entryPrice == null) {
            entryPrice = hoverPrice;
        } else if (stopLossPrice == null) {
            if (!bracketLive) stopLossPrice = hoverPrice;
        } else if (wasNearSL && !bracketLive) {
            // A precise grab-and-drag on the SL handle is handled entirely by onResize()/
            // onEndResize() and never reaches this method - but the handle sits right at the edge
            // of this same hover margin (see RiskResizePoint.layout()), so an imprecise grab can
            // fall through to a plain click instead of starting a drag. Previously, once both
            // entry and SL were already placed, ANY click here (including this near-miss) reset
            // entryPrice to the click and wiped stopLossPrice back to null - which is what made
            // the SL and every RR-projection line vanish on a near-miss drag. Now: a click that
            // landed near the existing SL line (same 10px proximity used for the ghost-line hover
            // indicator) just nudges the SL price instead, matching what was actually intended.
            stopLossPrice = hoverPrice;
        } else if (wasNearEntry) {
            // Same idea for a near-miss drag on the entry handle.
            entryPrice = hoverPrice;
        } else {
            // Click clearly away from both existing lines - start planning a new trade.
            entryPrice = hoverPrice;
            if (!bracketLive) stopLossPrice = null;
        }
    }

    // ==================== Geometry Helpers ====================

    private int getHoverAreaLeft(Rectangle bounds) {
        int width = cachedSettings != null ? cachedSettings.hoverWidth
                : getSettings().getInteger(HOVER_AREA_WIDTH, DEFAULT_HOVER_WIDTH);
        return bounds.x + bounds.width - width;
    }

    private boolean isInHoverArea(double x, DrawContext ctx) {
        Rectangle bounds = ctx.getBounds();
        int left = getHoverAreaLeft(bounds);
        return x >= left && x <= bounds.x + bounds.width;
    }

    private boolean isInHoverAreaX(int x, int hoverAreaLeft, Rectangle bounds) {
        return x >= hoverAreaLeft && x <= bounds.x + bounds.width;
    }

    private boolean isInLockButtonArea(int y, Rectangle bounds) {
        String pos = cachedSettings != null ? cachedSettings.lockButtonPos
                : getSettings().getString(LOCK_BUTTON_POS, POS_BOTTOM);
        int buttonTop;
        if (Util.compare(pos, POS_BOTTOM)) {
            buttonTop = bounds.y + bounds.height - LOCK_BUTTON_HEIGHT - LOCK_BUTTON_MARGIN;
        } else {
            buttonTop = bounds.y + LOCK_BUTTON_MARGIN;
        }
        int buttonBottom = buttonTop + LOCK_BUTTON_HEIGHT;
        return y >= buttonTop && y <= buttonBottom;
    }

    // ==================== Accessors for Suppliers ====================

    private Double getEntryPrice() {
        return entryPrice;
    }

    private Double getStopLossPrice() {
        return stopLossPrice;
    }

    // ==================== Inner Classes: visual (unchanged from original) ====================

    private class RiskResizePoint extends ResizePoint {
        private final Supplier<Double> priceSupplier;

        RiskResizePoint(Supplier<Double> priceSupplier) {
            super(Enums.ResizeType.VERTICAL, true);
            this.priceSupplier = priceSupplier;
            setFillColor(Color.WHITE);
            setOutlineColor(Color.GRAY);
        }

        @Override
        public void layout(DrawContext ctx) {
            Rectangle bounds = ctx.getBounds();
            int x = getHoverAreaLeft(bounds);
            long time = ctx.translate2Time(x);

            double price = getValue();
            if (price == 0) {
                Double suppliedPrice = priceSupplier.get();
                if (suppliedPrice != null) {
                    price = suppliedPrice;
                }
            }

            if (price != 0) {
                setLocation(time, price);
            }
            super.layout(ctx);
        }
    }

    private class BackgroundFigure extends Figure {
        private int hoverWidth;
        private int hoverAreaLeft;
        private Color bgColor;
        private boolean showEntryGhost;
        private boolean showSLGhost;
        private int entryGhostY;
        private int slGhostY;
        private Color ghostColor;

        @Override
        public void layout(DrawContext ctx) {
            if (cachedSettings == null) return;
            Rectangle bounds = ctx.getBounds();
            hoverWidth = cachedSettings.hoverWidth;
            hoverAreaLeft = getHoverAreaLeft(bounds);
            bgColor = cachedSettings.bgColor;

            PathInfo ghostPath = cachedSettings.ghostPath;
            showEntryGhost = !ctx.isSelected() && ghostPath != null &&
                    ghostPath.isEnabled() && RiskCalculator.this.showEntryGhost && entryPrice != null;
            showSLGhost = !ctx.isSelected() && ghostPath != null &&
                    ghostPath.isEnabled() && RiskCalculator.this.showSLGhost && stopLossPrice != null;

            if (showEntryGhost || showSLGhost) {
                ghostColor = ghostPath.getColor();
            }
            if (showEntryGhost) {
                entryGhostY = ctx.translateValue(entryPrice);
            }
            if (showSLGhost) {
                slGhostY = ctx.translateValue(stopLossPrice);
            }

            setBounds(new Rectangle(hoverAreaLeft, bounds.y, hoverWidth, bounds.height));
        }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            Rectangle bounds = ctx.getBounds();
            gc.setColor(bgColor);
            gc.fillRect(hoverAreaLeft, bounds.y, hoverWidth, bounds.height);

            if (showEntryGhost) {
                gc.setColor(ghostColor);
                gc.fillOval(hoverAreaLeft - 8, entryGhostY - 8, 16, 16);
            }
            if (showSLGhost) {
                gc.setColor(ghostColor);
                gc.fillOval(hoverAreaLeft - 8, slGhostY - 8, 16, 16);
            }
        }

        @Override
        public boolean isVisible(DrawContext ctx) {
            return true;
        }

        @Override
        public boolean contains(double x, double y, DrawContext ctx) {
            return isInHoverArea(x, ctx);
        }
    }

    private class LockButton extends Figure {
        private int lockButtonX;
        private int lockButtonY;
        private int lockButtonWidth;
        private Color lockBgColor;
        private Color lockOutlineColor;
        private Color lockIconColor;

        @Override
        public void layout(DrawContext ctx) {
            if (cachedSettings == null) return;
            Rectangle bounds = ctx.getBounds();
            int hoverAreaLeft = getHoverAreaLeft(bounds);
            lockButtonX = hoverAreaLeft + LOCK_BUTTON_MARGIN;

            String pos = cachedSettings.lockButtonPos;
            if (Util.compare(pos, POS_BOTTOM)) {
                lockButtonY = bounds.y + bounds.height - LOCK_BUTTON_HEIGHT - LOCK_BUTTON_MARGIN;
            } else {
                lockButtonY = bounds.y + LOCK_BUTTON_MARGIN;
            }

            lockButtonWidth = cachedSettings.hoverWidth - (LOCK_BUTTON_MARGIN * 2);
            lockBgColor = lockedToMarket ? cachedSettings.lockLockedBgColor : cachedSettings.lockBgColor;
            lockOutlineColor = cachedSettings.lockOutlineColor;
            lockIconColor = cachedSettings.lockIconColor;

            setBounds(new Rectangle(lockButtonX, lockButtonY, lockButtonWidth, LOCK_BUTTON_HEIGHT));
        }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            gc.setColor(lockBgColor);
            gc.setStroke(BORDER_STROKE);
            gc.fillRoundRect(lockButtonX, lockButtonY, lockButtonWidth, LOCK_BUTTON_HEIGHT, 4, 4);

            gc.setColor(lockOutlineColor);
            gc.drawRoundRect(lockButtonX, lockButtonY, lockButtonWidth, LOCK_BUTTON_HEIGHT, 4, 4);

            drawLockIcon(gc, lockButtonX + (lockButtonWidth / 2), lockButtonY + (LOCK_BUTTON_HEIGHT / 2), lockedToMarket, lockIconColor);
        }

        private void drawLockIcon(Graphics2D gc, int centerX, int centerY, boolean locked, Color color) {
            int w = 10;
            int h = 8;
            int arcW = 6;
            int arcH = 6;

            gc.setStroke(LOCK_ICON_STROKE);
            gc.setColor(color);

            gc.drawRect(centerX - w / 2, centerY - h / 2 + 2, w, h);

            if (locked) {
                gc.drawArc(centerX - arcW / 2, centerY - arcH / 2 - 2, arcW, arcH, 0, 180);
                gc.drawLine(centerX - arcW / 2, (centerY - arcH / 2 - 2) + arcH / 2, centerX - arcW / 2, centerY - h / 2 + 2);
                gc.drawLine(centerX + arcW / 2, (centerY - arcH / 2 - 2) + arcH / 2, centerX + arcW / 2, centerY - h / 2 + 2);
            } else {
                gc.drawArc(centerX - arcW / 2 - 2, centerY - arcH / 2 - 4, arcW, arcH, 0, 180);
                gc.drawLine(centerX - arcW / 2 - 2, (centerY - arcH / 2 - 4) + arcH / 2, centerX - arcW / 2 - 2, centerY - h / 2 + 2);
            }
        }

        @Override
        public boolean isVisible(DrawContext ctx) {
            return true;
        }

        @Override
        public boolean contains(double x, double y, DrawContext ctx) {
            return x >= lockButtonX && x <= lockButtonX + lockButtonWidth &&
                    y >= lockButtonY && y <= lockButtonY + LOCK_BUTTON_HEIGHT;
        }
    }

    private record PositionSizeResult(int positionSize, boolean warning) {}

    /** Planning-only display calc. Delegates to computeQuantity() - the SAME method the real
     *  order path (executeEntry) uses, including the max-contracts cap, the slippage buffer,
     *  and Cross-Trade-aware instrument resolution - so this box and the exec panel's "Qty"
     *  line can never disagree with each other or with what a submitted order would actually
     *  size to. (Previously this ran its own separate, simpler calculation that skipped both
     *  the cap and the slippage buffer - see write-up, item 7.) */
    private PositionSizeResult calculatePositionSize(DataContext ctx) {
        if (entryPrice == null || stopLossPrice == null || cachedSettings == null) {
            return new PositionSizeResult(0, false);
        }

        Instrument instr = ctx != null ? ctx.getInstrument() : null;
        if (instr == null) return new PositionSizeResult(0, false);

        int qty = computeQuantity(ctx, instr, entryPrice, stopLossPrice, cachedSettings.slippageTicks,
                cachedSettings.fixedRiskAmount, cachedSettings.maxContracts);

        // A planned trade (real stop distance set) that sizes to 0 means the risk-per-contract
        // exceeds the configured budget - the same condition executeEntry() would refuse to
        // submit. Warn here rather than silently showing 0.
        boolean warning = qty <= 0 && Math.abs(entryPrice - stopLossPrice) > 0;

        return new PositionSizeResult(qty, warning);
    }

    private class PositionSizeFigure extends Figure {
        private boolean showPosSize;
        private String posSizeText;
        private boolean posSizeWarning;
        private int posSizeX;
        private int posSizeY;
        private int posSizeWidth;
        private Color posSizeBgColor;
        private Color posSizeOutlineColor;
        private Color posSizeTextColor;
        private Color warningColor;
        private Font font;
        private int emojiSpace;
        private int textY;

        @Override
        public void layout(DrawContext ctx) {
            if (cachedSettings == null) {
                showPosSize = false;
                return;
            }
            showPosSize = cachedSettings.enablePosSize;
            if (!showPosSize) return;

            PositionSizeResult result = calculatePositionSize(ctx.getDataContext());
            posSizeText = "Position Size: " + result.positionSize();
            posSizeWarning = result.warning();

            Rectangle bounds = ctx.getBounds();
            int hoverAreaLeft = getHoverAreaLeft(bounds);

            String pos = cachedSettings.lockButtonPos;
            if (Util.compare(pos, POS_BOTTOM)) {
                posSizeY = bounds.y + bounds.height - LOCK_BUTTON_HEIGHT - LOCK_BUTTON_MARGIN;
            } else {
                posSizeY = bounds.y + LOCK_BUTTON_MARGIN;
            }

            font = cachedSettings.fontInfo != null ? cachedSettings.fontInfo.getFont() : new Font("SansSerif", Font.PLAIN, 12);

            java.awt.image.BufferedImage tempImg = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D tempGc = tempImg.createGraphics();
            tempGc.setFont(font);
            FontMetrics fm = tempGc.getFontMetrics();

            int textWidth = fm.stringWidth(posSizeText);
            int padding = 6;
            emojiSpace = posSizeWarning ? 20 : 0;
            posSizeWidth = textWidth + (padding * 2) + emojiSpace;
            posSizeX = hoverAreaLeft - posSizeWidth - LOCK_BUTTON_MARGIN;
            textY = posSizeY + (LOCK_BUTTON_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();

            tempGc.dispose();

            posSizeBgColor = cachedSettings.lockBgColor;
            posSizeOutlineColor = cachedSettings.lockOutlineColor;
            posSizeTextColor = cachedSettings.lockIconColor;
            warningColor = cachedSettings.warningColor;

            setBounds(new Rectangle(posSizeX, posSizeY, posSizeWidth, LOCK_BUTTON_HEIGHT));
        }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            if (!showPosSize) return;

            gc.setFont(font);

            gc.setColor(posSizeBgColor);
            gc.fillRoundRect(posSizeX, posSizeY, posSizeWidth, LOCK_BUTTON_HEIGHT, 4, 4);

            gc.setColor(posSizeOutlineColor);
            gc.setStroke(BORDER_STROKE);
            gc.drawRoundRect(posSizeX, posSizeY, posSizeWidth, LOCK_BUTTON_HEIGHT, 4, 4);

            if (posSizeWarning) {
                gc.setColor(warningColor);
                gc.drawString("\u26A0", posSizeX + 6, textY);
            }

            gc.setColor(posSizeTextColor);
            gc.drawString(posSizeText, posSizeX + 6 + emojiSpace, textY);
        }

        @Override
        public boolean isVisible(DrawContext ctx) {
            return cachedSettings != null && cachedSettings.enablePosSize;
        }
    }

    // ==================== NEW: Execution panel with BUY/SELL MARKET buttons ====================

    private class ExecutionPanelFigure extends Figure {
        private boolean visible;
        private int panelX, panelY, panelHeight;
        private String[] infoLines;
        private Rectangle buyRect;
        private Rectangle sellRect;
        private Color bgColor;
        private Color textColor;
        private Color buyColor;
        private Color sellColor;
        private Font font;
        private boolean canTrade;

        @Override
        public void layout(DrawContext ctx) {
            visible = cachedSettings != null && cachedSettings.enableExecPanel;
            if (!visible) {
                buyButtonBounds = null;
                sellButtonBounds = null;
                return;
            }

            Rectangle bounds = ctx.getBounds();

            DataContext dataCtx = getDataContext();
            Instrument instr = dataCtx != null ? dataCtx.getInstrument() : null;
            Instrument moneyInstr = resolveMoneyInstrument(dataCtx, instr);
            double fixedRisk = cachedSettings.fixedRiskAmount;
            double rr = cachedSettings.tradeRR;
            int maxContracts = cachedSettings.maxContracts;

            double stopDistance = (entryPrice != null && stopLossPrice != null)
                    ? Math.abs(entryPrice - stopLossPrice) : 0;
            int qty = 0;
            double actualRisk = 0;
            double targetPreview = 0;
            if (instr != null && stopDistance > 0) {
                qty = computeQuantity(dataCtx, instr, entryPrice, stopLossPrice, cachedSettings.slippageTicks,
                        (int) fixedRisk, maxContracts);
                double riskPerContract = (stopDistance / moneyInstr.getPointSize()) * moneyInstr.getPointValue();
                actualRisk = qty * riskPerContract;
                boolean isLong = entryPrice > stopLossPrice;
                targetPreview = isLong ? entryPrice + stopDistance * rr : entryPrice - stopDistance * rr;
            }

            canTrade = qty > 0 && getState() == Enums.StrategyState.ACTIVE && !orderInFlight.get();

            // Pre-trade routing conflict (write-up item 9) - block the buttons and surface why
            // BEFORE the user attempts to click, not just after (executeEntry() has its own hard
            // block on the same check as defense in depth).
            String routingConflict = checkRoutingConflict(dataCtx, instr);
            if (routingConflict != null) canTrade = false;

            java.util.List<String> lines = new ArrayList<>();
            String chartSymbol = instr != null ? instr.getSymbol() : "";
            // Reflects the REAL routing (auto-detected via getTradeInstrument(), falling back to
            // the manual setting), not just whether the manual override is populated - so this
            // stays accurate even if the user forgets to set/clear the manual field.
            boolean crossActive = moneyInstr != null && instr != null
                    && !moneyInstr.getSymbol().equals(instr.getSymbol());
            String symbolLine = crossActive ? (chartSymbol + " \u2192 " + moneyInstr.getSymbol()) : chartSymbol;
            lines.add(symbolLine);
            if (routingConflict != null) {
                lines.add("\u26A0 ROUTING CONFLICT");
            }
            lines.add("Risk $" + (int) fixedRisk + "  R:R " + Util.formatDouble(rr, 1));
            if (stopDistance > 0) {
                double slPoints = moneyInstr != null && moneyInstr.getPointSize() > 0 ? stopDistance / moneyInstr.getPointSize() : 0;
                lines.add("Qty " + qty + "  Actual $" + Util.formatDouble(actualRisk, 0));
                lines.add("SL " + (instr != null ? instr.format(stopLossPrice) : stopLossPrice)
                        + " (" + Util.formatDouble(slPoints, 1) + "p)");
                lines.add("TP " + (instr != null ? instr.format(targetPreview) : targetPreview));
            } else {
                lines.add("Set entry + SL to size a trade");
            }
            // Live R-multiple readout while a bracket is actually live - uses the frozen armed
            // entry/SL (not the live-drifting planning fields) so it reads correctly regardless
            // of what lockedToMarket is doing to entryPrice in the background.
            if (armedEntryPrice != null && armedStopLossPrice != null && instr != null) {
                double armedRisk = Math.abs(armedEntryPrice - armedStopLossPrice);
                if (armedRisk > 0) {
                    double currentPx = getLatestPrice(dataCtx);
                    boolean isLongLive = armedEntryPrice > armedStopLossPrice;
                    double rMultiple = isLongLive ? (currentPx - armedEntryPrice) / armedRisk
                                                   : (armedEntryPrice - currentPx) / armedRisk;
                    lines.add("R: " + (rMultiple >= 0 ? "+" : "") + Util.formatDouble(rMultiple, 2));
                }
            }
            lines.add(statusMessage);
            infoLines = lines.toArray(new String[0]);

            int textBlockHeight = infoLines.length * PANEL_LINE_HEIGHT;
            panelHeight = PANEL_PADDING * 2 + textBlockHeight + PANEL_BUTTON_HEIGHT * 2 + PANEL_BUTTON_GAP * 2;

            // Position the panel in the configured corner. Not true drag-and-drop (see settings
            // group tooltip / README) - a documented SDK mechanism exists for that
            // (relative-positioned ResizePoint of type ALL) but its exact method signature isn't
            // confirmed yet, so this is the reliable interim option.
            String corner = cachedSettings.execPanelPos;
            int margin = 10;
            if (CORNER_TOP_RIGHT.equals(corner)) {
                panelX = bounds.x + bounds.width - PANEL_WIDTH - margin;
                panelY = bounds.y + margin;
            } else if (CORNER_BOTTOM_LEFT.equals(corner)) {
                panelX = bounds.x + margin;
                panelY = bounds.y + bounds.height - panelHeight - margin;
            } else if (CORNER_BOTTOM_RIGHT.equals(corner)) {
                panelX = bounds.x + bounds.width - PANEL_WIDTH - margin;
                panelY = bounds.y + bounds.height - panelHeight - margin;
            } else {
                panelX = bounds.x + margin;
                panelY = bounds.y + margin;
            }

            int buttonWidth = PANEL_WIDTH - PANEL_PADDING * 2;
            int buyY = panelY + PANEL_PADDING + textBlockHeight + PANEL_BUTTON_GAP;
            buyRect = new Rectangle(panelX + PANEL_PADDING, buyY, buttonWidth, PANEL_BUTTON_HEIGHT);
            int sellY = buyY + PANEL_BUTTON_HEIGHT + PANEL_BUTTON_GAP;
            sellRect = new Rectangle(panelX + PANEL_PADDING, sellY, buttonWidth, PANEL_BUTTON_HEIGHT);

            bgColor = cachedSettings.panelBgColor;
            textColor = cachedSettings.panelTextColor;
            buyColor = cachedSettings.buyColor;
            sellColor = cachedSettings.sellColor;
            Font baseFont = cachedSettings.fontInfo != null ? cachedSettings.fontInfo.getFont() : new Font("SansSerif", Font.PLAIN, 12);
            font = baseFont.deriveFont(PANEL_FONT_SIZE);

            buyButtonBounds = buyRect;
            sellButtonBounds = sellRect;

            setBounds(new Rectangle(panelX, panelY, PANEL_WIDTH, panelHeight));
        }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            if (!visible) return;

            gc.setColor(bgColor);
            gc.fillRoundRect(panelX, panelY, PANEL_WIDTH, panelHeight, 6, 6);

            gc.setFont(font);
            gc.setColor(textColor);
            int y = panelY + PANEL_PADDING + gc.getFontMetrics().getAscent();
            for (String line : infoLines) {
                gc.drawString(line, panelX + PANEL_PADDING, y);
                y += PANEL_LINE_HEIGHT;
            }

            drawButton(gc, buyRect, "BUY MARKET", buyColor);
            drawButton(gc, sellRect, "SELL MARKET", sellColor);
        }

        private void drawButton(Graphics2D gc, Rectangle r, String label, Color color) {
            Color fill = canTrade ? color : new Color(90, 90, 90);
            gc.setColor(fill);
            gc.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
            gc.setColor(Color.WHITE);
            gc.setFont(font.deriveFont(Font.BOLD, PANEL_FONT_SIZE - 0.5f));
            FontMetrics fm = gc.getFontMetrics();
            int textX = r.x + (r.width - fm.stringWidth(label)) / 2;
            int textY = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
            gc.drawString(label, textX, textY);
        }

        @Override
        public boolean isVisible(DrawContext ctx) {
            // Must read cachedSettings directly, not the `visible` field - `visible` is only
            // ever set inside layout(), and if the framework calls isVisible() before layout()
            // (to decide whether layout() is even worth calling), a field-based check here would
            // permanently return false: layout() never runs, so the field never gets set.
            return cachedSettings != null && cachedSettings.enableExecPanel;
        }

        @Override
        public boolean contains(double x, double y, DrawContext ctx) {
            return (buyRect != null && buyRect.contains(x, y)) || (sellRect != null && sellRect.contains(x, y));
        }
    }

    private class RiskLine extends Figure {
        private final Supplier<Double> priceSupplier;
        private final Supplier<String> labelSupplier;
        private final String pathKey;

        private boolean visible;
        private int y;
        private Color color;
        private Stroke stroke;
        private Stroke selectedStroke;
        private int lineStartX;
        private int lineEndX;
        private String label;
        private int labelX;
        private int labelY;
        private Font font;

        RiskLine(Supplier<Double> priceSupplier, String label, String pathKey) {
            this(priceSupplier, () -> label, pathKey);
        }

        RiskLine(Supplier<Double> priceSupplier, Supplier<String> labelSupplier, String pathKey) {
            this.priceSupplier = priceSupplier;
            this.labelSupplier = labelSupplier;
            this.pathKey = pathKey;
        }

        @Override
        public void layout(DrawContext ctx) {
            if (cachedSettings == null) {
                visible = false;
                return;
            }

            Double price = priceSupplier.get();
            if (price == null || price == 0) {
                visible = false;
                return;
            }

            PathInfo path = getPathForKey(pathKey);
            if (path == null || !path.isEnabled()) {
                visible = false;
                return;
            }

            visible = true;
            y = ctx.translateValue(price);
            color = path.getColor();
            stroke = path.getStroke();
            selectedStroke = path.getSelectedStroke();

            Rectangle bounds = ctx.getBounds();
            boolean extend = PATH_GHOST.equals(pathKey) ? cachedSettings.extendPreview : cachedSettings.extendLevel;
            int hoverAreaLeft = getHoverAreaLeft(bounds);

            lineStartX = extend ? bounds.x : hoverAreaLeft;
            lineEndX = bounds.x + bounds.width;

            label = labelSupplier.get();
            if (label != null) {
                font = cachedSettings.fontInfo != null ? cachedSettings.fontInfo.getFont() : new Font("SansSerif", Font.PLAIN, 12);

                java.awt.image.BufferedImage tempImg = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D tempGc = tempImg.createGraphics();
                tempGc.setFont(font);
                FontMetrics fm = tempGc.getFontMetrics();

                int labelWidth = fm.stringWidth(label);
                int centerX = hoverAreaLeft + (cachedSettings.hoverWidth / 2);
                labelX = centerX - (labelWidth / 2);
                labelY = y - LABEL_OFFSET_Y;

                tempGc.dispose();
            }
        }

        private PathInfo getPathForKey(String key) {
            return switch (key) {
                case PATH_ENTRY -> cachedSettings.entryPath;
                case PATH_SL -> cachedSettings.slPath;
                case PATH_TP -> cachedSettings.tpPath;
                case PATH_GHOST -> cachedSettings.ghostPath;
                default -> null;
            };
        }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            if (!visible) return;

            gc.setColor(color);
            gc.setStroke(ctx.isSelected() ? selectedStroke : stroke);
            gc.drawLine(lineStartX, y, lineEndX, y);

            if (label != null) {
                gc.setFont(font);
                gc.drawString(label, labelX, labelY);
            }
        }

        @Override
        public boolean isVisible(DrawContext ctx) {
            return true;
        }
    }
}
