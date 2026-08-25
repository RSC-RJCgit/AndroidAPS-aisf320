# UKF3426 AutoISF replay trace

This integration twins the controller, not the body. It records the exact values used at
the `DetermineBasalAutoISF.determine_basal()` boundary and its resulting recommendation.
It never writes to Nightscout, a pump, or a dosing result.

## Safety and privacy

- Trace recording is **off by default**.
- The capture runs after `determine_basal()` returns and cannot modify that result.
- A trace contains glucose, insulin/controller state, the active profile name and an
  AutoISF settings snapshot. Treat the exported log and JSONL as private health data.
- No Nightscout URL, token or password is deliberately included.
- The importer validates captured baseline records. The JVM-only Kotlin adapter then executes the
  unchanged production `DetermineBasalAutoISF` source and compares every returned `RT` field with
  the recorded result. Counterfactual AutoISF replay remains disabled unless every record matches.

## Collect a trace

1. Build/install this `UKF3426` branch using the normal Android Studio workflow.
2. In AAPS, open **Preferences > OpenAPS AutoISF**.
3. Enable **Record AutoISF digital-twin replay trace**.
4. Leave ordinary loop operation and settings unchanged while collecting the desired
   period. Each APS calculation produces one trace record.
5. Export the AAPS logs using the existing Maintenance/log export function.
6. Turn **Record AutoISF digital-twin replay trace** off again to limit log growth.

## Import an AAPS log or ZIP on Windows

From PowerShell:

```powershell
cd C:\Users\arjay\StudioProjects\AaAPS3422a320\tools\oref-digital-twin
.\import-autoisf-trace.ps1 -InputPath "C:\path\to\aaps-logs.zip"
```

The script writes `<input-name>-autoisf-replay.jsonl` beside the input and reports the
number of complete records, incomplete traces, errors and controller-source mismatches.
It accepts a plain AAPS log or an exported log ZIP. A source mismatch means the APK was
built from different AutoISF controller source than the current working tree, so the
current adapter must not replay it.

Direct Python equivalent:

```powershell
py -3 -m replay.autoisf_trace "C:\path\to\aaps-logs.zip" `
  --output "C:\path\to\autoisf-replay.jsonl"
```

Exit code `0` means at least one record was recovered with no validation errors. Exit
code `1` means records were recovered but at least one trace was invalid. Exit code `2`
means no complete record was found.

## Verify the Kotlin baseline on Windows

After importing the ZIP, run:

```powershell
cd C:\Users\arjay\StudioProjects\AaAPS3422a320\tools\oref-digital-twin
.\replay-autoisf-baseline.ps1 -InputPath `
  "C:\backup\AAPS\logs_Virtual\AndroidAPS_LOG_1787683910107.log-autoisf-replay.jsonl"
```

The wrapper starts the `:plugins:aps:runAutoIsfReplayAdapter` JVM verification task. The adapter is
compiled from the unit-test source set and is never packaged in the APK. For each JSONL row it:

1. recomputes and verifies the two pinned Kotlin source hashes;
2. restores the captured preference values and TDD state in isolated in-memory adapters;
3. deserializes the exact `determine_basal()` arguments;
4. runs the production `DetermineBasalAutoISF` class; and
5. compares every result key, list item, string and number with the captured `RT`.

Success is reported as `records=N matched=N differences=0 adapter_errors=0`. Any missing captured
preference, source mismatch, adapter exception or result difference returns a failure and leaves
counterfactual replay locked.

## Developer verification

These checks require Python 3 but no Gradle build:

```powershell
cd C:\Users\arjay\StudioProjects\AaAPS3422a320\tools\oref-digital-twin
py -3 -m unittest replay.tests.test_autoisf_source replay.tests.test_autoisf_trace -v
py -3 -m unittest replay.tests.test_autoisf_baseline -v
py -3 -c "from replay.autoisf_source import build_source_manifest; print(build_source_manifest().to_dict())"
```

The source manifest hashes both `DetermineBasalAutoISF.kt` and
`OpenAPSAutoISFPlugin.kt`. Any change to either file creates a different controller
identity for replay validation.
