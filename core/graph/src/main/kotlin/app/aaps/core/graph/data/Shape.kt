package app.aaps.core.graph.data

/**
 * choose a predefined shape to render for
 * each data point.
 * You can also render a custom drawing via [com.jjoe64.graphview.series.PointsGraphSeries.CustomShape]
 */
enum class Shape {

    BG,
    PREDICTION,
    TRIANGLE,
    RECTANGLE,
    BOLUS,
    CARBS,
    SMB,
    EXTENDEDBOLUS,
    PROFILE,
    MBG,
    BGCHECK,
    ANNOUNCEMENT,
    SETTINGS_EXPORT,
    RUNNING_MODE,
    EXERCISE,
    GENERAL,
    ACTIVITY_PEAK, // peak insulin activity label — same 45°-rotated style as GENERAL's drawLabel45Right, but below the point instead of above
    IOB_PEAK, // peak IOB label on the IOB graph — same dominant-peaks-only selection/rendering as ACTIVITY_PEAK (own color via IobPeakDataPoint.color(), see PrepareIobAutosensGraphDataWorker.kt)
    GENERAL_WITH_DURATION,
    GENERAL_WITH_DURATION_OFFSET, // same fixed-top-of-graph style as GENERAL_WITH_DURATION, drawn lower to avoid overlapping CarePortal notes
    COB_FAIL_OVER,
    IOB_PREDICTION,
    BUCKETED_BG,
    HEART_RATE,
    STEPS,
    STEPS_STACKED_BOTTOM, // two stacked lines fixed near the bottom of the graph, above the SMB baseline triangles
    SMB_GRAPH2, // SMB dose label fixed near top of graph 2 (IOB graph), always visible
    ISF_INDICES, // multi-color "f= a= b= d= g= smb=" row, fixed near the bottom of graph1, above the steps row/extra row/yellow-white line there (moved from graph3)
    STEPS_EXTRA_ROW, // "DR=/AW=/LS=" row, fixed one line-height above the steps row (Shape.STEPS_STACKED_BOTTOM)
    A1_DELTA_POINT, // AAPS (smoothed) 1-min delta label attached directly to the current smoothed BG graph point — same 45°-rotated style as GENERAL's drawLabel45Right, no circle, own color (green)
    UKF_DELTA_POINT, // UKF-smoothed 5-min delta label, attached to the current UKF-smoothed point (same curve as the blue dashed rawBgSmoothedSeries line) — same 45°-rotated style as L1/A1, own color (light blue, matches that line). Tracks the smoothed curve itself, unlike L1's raw two-point slope, so it shouldn't disagree with which way that line visually appears to be moving.
    HP_ROW_BOTTOM, // "hypoprediction= <value>" row, fixed near the bottom of the MAIN graph (same nearBottomPy as Shape.STEPS_STACKED_BOTTOM, but on the main graph's own viewport, near the basal columns), own larger font size
    NOTE_ARROWHEAD_GRAPH3, // Plain CarePortal-note arrowhead (unscaled triangle, half-length shaft vs Shape.SMB's own BGL-point arrowhead), fixed at graph4's top half (name kept, moved there from its original graph3-bottom spot); no vertical stacking of its own, but the arrowhead matching each GENERAL_WITH_DURATION stack's first (beginning) entry gets a time label (HHmm) at the top of its shaft — others stay unlabeled
    PP_ACC_DU_ROW, // "pp= acc= du=" row, graph5 only. Positioned via the point's own real Y (endY, like any normal data point) rather than a graphHeight pixel fraction -- graph5's basal bars occupy negative Y below the glucose floor (same structure as bg_graph; an earlier assumption that graph5 had no basal region was wrong -- graph5Data.addBasals() IS called there for tempBasal-capable pumps), so a pixel fraction lands in that zone instead of near actual BGL values. The caller feeds a FIXED display-unit value near 4.0 mmol (not the live current BG) so this sits just above the low end of the visible glucose range regardless of where BG currently is.
    SMB_STACK_TOTAL // One label per reconstructed SMB-stack start (same <=70s/10-min rule as ApsAutoIsfSmbStackStart in DetermineBasalAutoISF.kt), drawn at that stack's own start timestamp, anchored at the BASE of whichever panel this series is added to (same formula as SMB_GRAPH2's own bottom anchor -- currently graph3, replacing the old "pp= acc= du=" row moved to the main graph), small yellow bold text matching graph4's HHmm time labels (NOTE_ARROWHEAD_GRAPH3) -- shows total SMB units delivered in the 10 minutes following the stack's start ("1.42" etc, not a delta). Stacks UPWARD from that base, in 30-min groups when two labels land close together. See PrepareTreatmentsDataWorker.kt for the reconstruction.
}
