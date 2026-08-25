"""End-to-end of the Pyodide entrypoint: raw NS JSON -> report, all in Python."""

from ingestion.tests import fixtures as fx
from report.browser import (
    abstracted_findings,
    build_report,
    gate_narrative,
    settings_from_raw,
)

RAW = {
    "base_url": "https://example.test",
    "start_ms": 1_699_990_000_000,
    "end_ms": 1_700_010_000_000,
    "entries": [fx.ENTRY, fx.ENTRY_MBG],
    "treatments": [fx.TREATMENT_SMB, fx.TREATMENT_CARB, fx.TREATMENT_TT],
    "devicestatus": [fx.DEVICESTATUS, fx.DEVICESTATUS_NO_OREF],
    "profiles": [fx.PROFILE_MMOL],
}


def test_build_report_end_to_end():
    result = build_report(RAW)
    assert result["report_md"].startswith("# oref digital twin")
    assert "diagnostics" in result and "variant" in result
    assert "cgm" in result["coverage"]
    # the no-oref devicestatus doc was dropped upstream
    assert isinstance(result["diagnostics"]["findings"], list)


def test_abstracted_findings_excludes_raw_data_and_token():
    result = build_report(RAW)
    payload = abstracted_findings(result)
    # only findings/stats leave the browser
    assert set(payload).issubset({"counts", "glycemia", "findings", "variant", "counterfactuals"})
    # no raw NS data or connection info anywhere in the payload
    flat = str(payload)
    assert "example.test" not in flat
    assert "entries" not in payload and "base_url" not in payload


def test_gate_narrative_wires_through():
    result = build_report(RAW)
    source = abstracted_findings(result)
    # a narrative that invents a number must be rejected
    bad = gate_narrative("Your time in range was 999%.", source)
    assert bad["passed"] is False
    assert any(v["kind"] == "ungrounded_number" for v in bad["violations"])


def test_parse_max_iob_varieties():
    from report.browser import _parse_max_iob
    # AAPS console/reason with colon; dot and comma decimals
    assert _parse_max_iob("COB: 0; maxIOB: 8.0; SMB 0.3U") == 8.0
    assert _parse_max_iob("maxIOB: 1,0 (masked)") == 1.0            # European comma decimal
    assert _parse_max_iob("... maxIOB 11.2 ...") == 11.2            # bare space
    # Trio/oref JSON spelling
    assert _parse_max_iob('{"max_iob":6,"enableSMB":true}') == 6.0
    assert _parse_max_iob('"max_iob": 4.5') == 4.5
    assert _parse_max_iob("MAXIOB=7") == 7.0                        # case-insensitive, equals
    # no match
    assert _parse_max_iob("COB: 0; IOB 1.2; temp 0.5") is None
    assert _parse_max_iob(None) is None


def test_infer_settings_finds_max_iob_in_raw_openaps():
    from ingestion.models import DeviceStatusCycle
    from report.browser import infer_settings

    class _Pull:
        devicestatus = [DeviceStatusCycle(ts_ms=1, reason="temp 0.4",
                                          raw_openaps={"suggested": {"max_iob": 6, "reason": "temp 0.4"}})]
        treatments = []
    settings, _ = infer_settings(_Pull())
    assert settings["max_iob"] == 6.0


def test_infer_settings_reads_max_iob_from_reason():
    from ingestion.pull import pull_from_raw
    from report.browser import infer_settings
    pull = pull_from_raw("x", RAW["start_ms"], RAW["end_ms"], RAW["entries"],
                         RAW["treatments"], RAW["devicestatus"], RAW["profiles"])
    settings, _notes = infer_settings(pull)
    assert settings["max_iob"] == 11.2          # from "maxIOB 11.2" in the reason
    assert settings["enable_smb"] is True        # an SMB was delivered


def _fake_oref_runner(requests):
    # SMB scales with max_iob so a max_iob delta produces a decision change
    return [{"ok": True, "rt": {"rate": 0.0, "duration": 30,
                                "units": round(r["profile"].get("max_iob", 0) * 0.1, 3)}}
            for r in requests]


def test_counterfactuals_run_when_oref_runner_supplied():
    result = build_report(RAW, oref_runner=_fake_oref_runner)
    cfs = result["counterfactuals"]
    assert cfs, "expected counterfactuals with a runner supplied"
    # the max_iob lever should change the decision on the evaluated cycle(s)
    maxiob_cf = next(c for c in cfs if "max_iob" in c["label"])
    assert maxiob_cf["n_evaluated"] >= 1
    assert maxiob_cf["n_changed"] >= 1
    assert "not the resulting blood glucose" in maxiob_cf["caveat"]
    # and it renders into the report
    assert "Settings experiments" in result["report_md"]


def test_no_counterfactuals_without_runner():
    assert build_report(RAW)["counterfactuals"] == []


def test_max_iob_override_is_used():
    # override wins over any inferred value, so the baseline lever reflects it
    result = build_report(RAW, oref_runner=_fake_oref_runner, max_iob_override=5.0)
    cfs = result["counterfactuals"]
    assert cfs and any("5.0" in c["label"] for c in cfs)   # baseline 5.0 -> 4.0, not the 11.2 reason


def test_settings_from_raw_aaps_keys():
    # AAPS prefs export style: string values, AAPS pref keys
    raw = {"openapsma_max_iob": "6.0", "enableSMB_always": "true", "maxSMBBasalMinutes": "45"}
    out = settings_from_raw(raw)
    assert out["settings"]["max_iob"] == 6.0
    assert out["settings"]["enable_smb"] is True
    assert out["settings"]["max_smb_minutes"] == 45


def test_settings_from_raw_boost_and_smb_keys():
    # Boost prefs export: boost_max_iob + AAPS SMB minute caps
    raw = {"boost_max_iob": "11.2", "smbmaxminutes": "60", "uamsmbmaxminutes": "30",
           "enableSMB_always": "true"}
    out = settings_from_raw(raw)
    assert out["settings"]["max_iob"] == 11.2
    assert out["settings"]["max_smb_minutes"] == 60
    assert out["settings"]["max_uam_minutes"] == 30


def test_unmapped_iob_keys_surfaced():
    out = settings_from_raw({"some_weird_iob_setting": "9"})
    assert "some_weird_iob_setting" in out["unmapped_iob_keys"]
    assert "max_iob" not in out["settings"]


def test_settings_from_raw_trio_json():
    # Trio / oref preferences style: native JSON types, oref keys
    raw = {"max_iob": 5, "enableSMB_always": True, "maxSMBBasalMinutes": 30, "not_a_key": 1}
    out = settings_from_raw(raw)
    assert out["settings"]["max_iob"] == 5.0
    assert out["settings"]["enable_smb"] is True
    assert any(i["kind"] == "unknown_key" for i in out["issues"])   # unknown key reported


def test_uploaded_settings_drive_counterfactuals():
    parsed = settings_from_raw({"max_iob": 4, "enableSMB_always": True})
    result = build_report(RAW, oref_runner=_fake_oref_runner, settings=parsed["settings"])
    cfs = result["counterfactuals"]
    assert cfs and any("4.0" in c["label"] for c in cfs)   # baseline came from the uploaded file


def test_override_unblocks_when_inference_fails():
    # devicestatus with no maxIOB anywhere -> inference fails, override rescues it
    raw = dict(RAW)
    raw["devicestatus"] = [{
        "_id": "d9", "created_at": "2023-11-14T22:14:00.000Z", "device": "openaps://phone",
        "openaps": {"iob": [{"iob": 1.0}], "enacted": {"bg": 128, "reason": "temp 0.4"}},
    }]
    result = build_report(raw, oref_runner=_fake_oref_runner, max_iob_override=6.0)
    assert result["counterfactuals"], "override should unblock counterfactuals"
