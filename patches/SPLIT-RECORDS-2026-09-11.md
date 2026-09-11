# Split-bolus recording audit

The usual project includes the recording changes in `3d71c6edd1`.
The AISF-origin repository `AndroidAPS-aisf-boluscalc`, branch `bolus-calculator`,
includes them in `63f4c3df3e` (GitHub branch checked on 11 September).

- Carb-split and protein/fat follow-ups now save their own calculator results,
  with the IOB baseline, live IOB rise, calculated dose, BG gate values and pump result.
  Previously only bolus records existed, so Treatments had no corresponding calc row.
- Calculated-zero carb splits now save a zero bolus, calculator result and S0.00
  Careportal note. They still retry at the original interval; the note says that
  no insulin was delivered and reports the pending residual.
- Quick Wizard carb entries remain accessible when the initial insulin is zero.
- Follow-up user entries retain QuickWizard attribution.
- The Quick Wizard list long-press path no longer raises a delivery error for a
  normal insulin constraint. Invalid carbs block execution; the shared confirmation
  still explains insulin constraints.

The earlier duplicate-callback fix is present in AISF-origin commit `65feecab78`.
Without it, a second successful callback for the same insulin/carbs entry could
advance the supersession token and cancel that entry's pending split doses.
The specific reported warning/cancellation occurrence has not been identified in
a matching AISF-origin APK log. A fix being present in Git is not proof of which
APK ran during that occurrence.

All three bolus-calculator patches contain the recording changes. The AISF-origin
patch was converted from UTF-16 to UTF-8. Each patch was applied, reversed, and
checked again against its stated base; resulting source whitespace checks passed.
No Gradle or Android build was run. Runtime/UI validation remains necessary.
