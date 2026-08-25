from report.template import render_report

DIAG = {
    "counts": {"critical": 1, "warning": 1, "info": 0},
    "glycemia": {"n_readings": 288, "days": 1.0, "mean_mgdl": 120.0, "mean_mmol": 6.7,
                 "gmi_pct": 6.2, "cv_pct": 30.0, "tir_70_180": 55.0, "tbr_lt70": 5.0,
                 "tbr_lt54": 3.0, "tar_gt180": 40.0, "tar_gt250": 6.0, "ting_63_140": 40.0},
    "findings": [
        {"key": "tbr_lt54_over_limit", "severity": "critical",
         "title": "Severe hypoglycaemia exposure above the 1% limit", "detail": "3.0% below 54."},
        {"key": "cv_high", "severity": "warning", "title": "Variability above 36%", "detail": "CV 30%."},
    ],
}
VARIANT = {"variant": "aaps_smb", "confidence": 0.9, "advisability": "full", "notes": []}


def test_render_contains_key_sections_and_numbers():
    md = render_report(DIAG, VARIANT)
    assert "# oref digital twin — report" in md
    assert "Severe hypoglycaemia exposure above the 1% limit" in md
    assert "55.0%" in md and "120" in md         # metrics rendered
    assert "aaps_smb" in md
    assert "not medical advice" in md            # disclaimer present


def test_critical_before_warning():
    md = render_report(DIAG, VARIANT)
    assert md.index("### Critical") < md.index("### Worth attention")


def test_counterfactual_block_includes_caveat():
    cfs = [{"label": "max_iob=3", "n_evaluated": 20, "n_changed": 14,
            "total_delta_u": -2.1, "mean_delta_u": -0.1,
            "caveat": "Decision-level only: not the resulting blood glucose."}]
    md = render_report(DIAG, VARIANT, counterfactuals=cfs)
    assert "Settings experiments" in md and "max_iob=3" in md
    assert "not the resulting blood glucose" in md


def test_render_is_deterministic():
    assert render_report(DIAG, VARIANT) == render_report(DIAG, VARIANT)


def test_handles_no_cgm():
    md = render_report({"counts": {}, "glycemia": {"n_readings": 0}, "findings": []})
    assert "No CGM data" in md
