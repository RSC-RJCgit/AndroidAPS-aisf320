**Related: a bolus from the Insulin dialog goes through the calculator (insulin-only mode)**

A pump bolus typed in the Insulin dialog (not record-only) now opens the bolus calculator in an "insulin only" mode, so a hand-typed dose is shown next to what the calculator would have suggested:

- "Insulin to give" holds the typed dose, and that dose is what is given. The calculation above it shows the calculator's own suggestion (in the screenshot, 1.00 U typed, 0.30 U suggested).
- The other boxes (BG, TT, trend, IOB, COB, fat, protein, correction and the meal extras) are locked. Carbs start at 0.
- Entering carbs switches back to the full bolus calculator: the typed dose is dropped, the boxes unlock, the default options turn back on and fat/protein are filled in again, so OK then gives the meal calculation (panel 3).
- Max bolus and splitting a dose over max bolus still apply, to the typed dose.
- The saved calculation starts with "Direct 1.00 U, calculator suggested 0.30 U", so the difference is visible later in the treatment history.

[attach: insulin-only-issue-5483-screens.png]
