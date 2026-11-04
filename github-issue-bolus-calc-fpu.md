TITLE: Bolus calculator: choose between eCarbs and split Warsaw FPU doses for fat and protein

---

This proposes an option in the bolus calculator (and in each QuickWizard button) to choose how fat and protein are covered:

1. **Keep the current eCarbs approach.** Fat/protein, or a slow part of the meal, is entered as extended carbs spread over a few hours, and the loop covers them as they appear in COB. Nothing else changes.
2. **Use split FPU boluses (Warsaw method).** Fat and protein are converted to fat-protein units and given as a series of small scheduled boluses over the following hours.

Screenshots of the calculator and QuickWizard screens will follow.

**Why:** eCarbs make the loop treat fat and protein as carbs it has to chase. With a slow, late rise (pizza, large protein meals), that often means a high BG hours later, then SMBs stacking to catch up. The Warsaw method is an established way to dose fat and protein up front, on a schedule that matches when they act. Some users will prefer one, some the other, so this keeps both.

**Option 2, how it works in my build:**

- **Fat-protein units:** FPU = (9 × fat g + 4 × protein g) / 100. Insulin is worked out from the carb equivalent (protein × 0.4, fat × 0.9) and the profile's IC ratio.
- **Duration from FPU (Warsaw table):** up to 1 FPU over 3 h, up to 2 over 4 h, up to 3 over 5 h, above 3 over 8 h.
- **Split into doses** every 30 to 60 minutes (user choice, 30 by default), with the same total whatever the spacing.
- **Cap on the longest tier:** above 3 FPU, a setting can shorten the 8 h series. Doses past the cap are not given, so the total is reduced rather than made up later.
- **Before each dose:** each scheduled dose checks current IOB and BG, and is reduced or held if BG is falling or IOB has risen since the meal. A held dose can be rechecked every 10 minutes.
- **Optional auto-fill:** fat and protein can be filled in from the carbs (with a "fast carbs" box that halves it). Unticking auto-fill clears the suggested grams but keeps any the user typed.
- **Shown before confirming:** the calculator shows the FPU, the number of doses, the per-dose and total insulin, and any cap. The saved calculation keeps the same note.

**Option 1:** when split FPU is off, fat and protein add no insulin, and eCarbs work exactly as now.

**Notes:**

- One checkbox, "Use split boluses for FPU", switches between the two. It is set per calculator run, and saved per QuickWizard button.
- Doses go through the normal bolus queue and constraints (max bolus, max IOB).
- Running and tested on a virtual pump and my own loop phone, and 2 users live. It significantly decreases SMBs required later, but seems to be an earlier fix of insulin required.
