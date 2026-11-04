TITLE: Stats: Sensor tab: glucose over each CGM sensor's life (raw signal vs BG, first-quarter behaviour)

---

Noting the dramatic success of the cycle graphs and using that principle in another tab for sensor by sensor, this proposes a **Sensor** tab in Statistics that lays glucose over the life of each CGM sensor, aligned to its sensor change, the way the Cycle tab lays insulin over 28-day cycles. Screenshots will follow in comments.

**Why:** AAPS doses from BG all through a sensor's life, but nothing shows how a sensor behaves *over* that life. My own data [see screenshots ] suggests some sensors read differently by sensor age. FreeStyle Libre 2 Plus especially reads **higher in the first quarter** of its 15 days, then settles. The tab makes that visible for any sensor type, from data AAPS already stores. It may help decide calibration or compensation code.

**Views (same sensors, three ways):**

- **Lines:** day 0 to end of life, one line per sensor plus their average. The flat average lines split each graph into quarters, so first-quarter vs later bias stands out.
- **Ambulatory:** the same span as percentile bands and a median, AGP style, from all readings of the shown sensors in each bin.
- **Changeover:** the last days of one sensor and the first days of the next, pooled over several changes. It shows median and percentile bands, plus the average and time in range before and after the change, similar to Nocturne's "Site Change Impact" report.

**Raw vs BG toggle (Raw is the default):**

- **Raw:** the sensor's own signal as uploaded (e.g. Juggluco's `unfiltered` to Nightscout, stored in `GlucoseValue.noise`).
- **BG:** the glucose values AAPS actually uses for dosing.

Raw is shown only where a real raw signal exists. Readings without one are gaps, not BG.

**Compensation comparison:** toggling between Raw and BG shows whether anything between the sensor and AAPS corrects the sensor-age bias:

- the uploader's own algorithm (Juggluco, xDrip+ and others)
- AAPS smoothing (Average / Exponential)
- calibration, if fingersticks are entered

Any first-quarter offset in the raw signal would pass straight into the BG used for SMB/basal decisions. Screenshots will show raw against BG for recent FSL2+ sensors ; depending on changes in LibreSlope or calibration.

**Notes:**

- Display only: nothing feeds back into dosing.
- Sensor changes come from CarePortal sensor-change events. Duplicates from Nightscout sync, a few minutes apart, are merged.
- A sensor's length isn't stored in AAPS, so it's taken from the maker's figures (Libre 2/3 use their Plus versions' 15 days; others get 15 days).
- Works on AAPSClient too, since sensor changes and BG come over Nightscout.  
