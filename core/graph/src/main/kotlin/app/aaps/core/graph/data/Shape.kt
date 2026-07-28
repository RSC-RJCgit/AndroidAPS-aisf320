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
    GENERAL_WITH_DURATION,
    GENERAL_WITH_DURATION_OFFSET, // same fixed-top-of-graph style as GENERAL_WITH_DURATION, drawn lower to avoid overlapping CarePortal notes
    COB_FAIL_OVER,
    IOB_PREDICTION,
    BUCKETED_BG,
    HEART_RATE,
    STEPS,
    STEPS_STACKED_BOTTOM, // two stacked lines fixed near the bottom of the graph, above the SMB baseline triangles
    SMB_GRAPH2, // SMB dose label fixed near top of graph 2 (IOB graph), always visible
    ISF_INDICES, // multi-color "f= a= b= d= g= smb=" row fixed near the bottom of graph3, one color per field
    STEPS_EXTRA_ROW // "DR=/AW=/LS=" row, fixed one line-height above the steps row (Shape.STEPS_STACKED_BOTTOM)
}
