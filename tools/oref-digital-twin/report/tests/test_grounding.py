from report.grounding import check_narrative

SOURCE = {
    "glycemia": {"mean_mgdl": 120.0, "tir_70_180": 55.0, "tbr_lt54": 3.0},
    "counts": {"critical": 1, "warning": 1, "info": 0},
    "findings": [
        {"key": "tbr_lt54_over_limit", "severity": "critical",
         "title": "Severe hypoglycaemia exposure above the 1% limit",
         "detail": "3.0% of readings are below 54 mg/dL, above the 1% limit."},
        {"key": "smb_high_iob_overnight", "severity": "warning",
         "title": "Overnight SMBs at high IOB", "detail": "4 of 10 overnight SMBs at high IOB."},
    ],
}


def test_grounded_narrative_passes():
    txt = ("Your mean glucose was 120 mg/dL with time in range of 55%. Critically, severe "
           "hypoglycaemia below 54 was 3.0%, above the 1% limit. Overnight SMBs at high IOB "
           "showed 4 of 10 followed by lows — an association, this does not prove causation.")
    res = check_narrative(txt, SOURCE)
    assert res.passed, res.to_dict()


def test_fabricated_number_is_caught():
    txt = ("Severe hypoglycaemia below 54 was 3.0%, above the 1% limit. Your time in range "
           "was 85%.")  # 85 is not in the source (real TIR is 55)
    res = check_narrative(txt, SOURCE)
    assert not res.passed
    assert any(v.kind == "ungrounded_number" and "85" in v.detail for v in res.violations)


def test_prescription_is_blocked():
    txt = ("Severe hypoglycaemia below 54 was 3.0%, above the 1% limit. You should increase "
           "your max IOB to 8 to fix this.")
    res = check_narrative(txt, SOURCE)
    assert not res.passed
    assert any(v.kind == "prescription" for v in res.violations)


def test_omitted_critical_is_caught():
    txt = "Things look broadly fine and your time in range was 55%."  # no mention of hypo
    res = check_narrative(txt, SOURCE)
    assert not res.passed
    assert any(v.kind == "omitted_critical" for v in res.violations)


def test_missing_association_caveat_for_smb_pattern():
    txt = ("Severe hypoglycaemia below 54 was 3.0%, above the 1% limit. Overnight SMBs at "
           "high IOB were seen 4 of 10 times and caused the lows.")  # asserts causation, no caveat
    res = check_narrative(txt, SOURCE)
    assert not res.passed
    assert any(v.kind == "missing_assoc_caveat" for v in res.violations)


def test_counterfactual_requires_caveat():
    src = dict(SOURCE)
    src["counterfactuals"] = [{"label": "max_iob=3", "n_changed": 5}]
    txt = ("Severe hypoglycaemia below 54 was 3.0%, above the 1% limit. Lowering max IOB to 3 "
           "changed the decision on 5 cycles.")  # mentions counterfactual, no BG caveat
    res = check_narrative(txt, src)
    assert not res.passed
    assert any(v.kind == "missing_caveat" for v in res.violations)

    txt_ok = txt + " This is decision-level only and does not predict the resulting blood glucose."
    assert check_narrative(txt_ok, src).passed


def test_structural_small_integers_allowed():
    txt = ("Severe hypoglycaemia below 54 was 3.0%, above the 1% limit; this is the 1 thing to "
           "fix. Overnight SMBs — association only, does not prove causation — over 2 nights.")
    res = check_narrative(txt, SOURCE)
    assert res.passed, res.to_dict()


# --- hedged / indirect dosing advice ---------------------------------------------------
# The narrator is a small instruct model told not to prescribe. It complies by hedging, so
# the hedged forms are the ones that actually reach users. Each of these passed the gate
# before the patterns below were added.

HEDGED = [
    "Increase by 3 units before meals.",
    "Consider increasing by 2 units when you are high.",
    "Try 3 units more at breakfast.",
    "It would be sensible to run a little more insulin overnight.",
    "I'd suggest reducing basal overnight.",
    "Perhaps a little more basal overnight would help.",
    "You may want to add 2 units.",
    "It may be worth raising your target.",
]


def test_hedged_prescriptions_are_blocked():
    for txt in HEDGED:
        res = check_narrative(txt, SOURCE)
        assert any(v.kind == "prescription" for v in res.violations), f"not blocked: {txt!r}"


DESCRIPTIVE = [
    "Lowering max IOB to 3 changed the decision on 5 cycles.",
    "The loop delivered 1.2 units at 3am.",
    "Overnight SMBs totalling 4 units were seen.",
    "Consider that your max IOB of 6 was reached on 12 cycles.",
]


def test_descriptive_counterfactuals_are_not_prescriptions():
    """Describing what the controller did is not advice; the gate must not block it."""
    src = dict(SOURCE)
    src["findings"] = SOURCE["findings"] + [
        {"key": "ctx", "severity": "info", "title": "Context",
         "detail": "max IOB 6; 5 cycles; 1.2 U; 4 SMBs; 3 lows; 12 cycles"}]
    for txt in DESCRIPTIVE:
        res = check_narrative(txt, src)
        assert not any(v.kind == "prescription" for v in res.violations), f"false positive: {txt!r}"


# --- number grounding ------------------------------------------------------------------

def test_number_with_dose_unit_forfeits_structural_exemption():
    """'3' is ordinary prose; '3 units' is a dose and must be grounded."""
    src = {"findings": [], "glycemia": {}}
    assert not check_narrative("Give it 3 hours to settle.", src).violations
    res = check_narrative("That is 3 units of insulin.", src)
    assert any(v.kind == "ungrounded_number" for v in res.violations)


def test_half_unit_drift_is_not_grounded():
    """A source 6.4 must not ground a narrated 5.5 — that is a material dosing error."""
    src = {"findings": [], "glycemia": {"max_iob": 6.4}}
    res = check_narrative("Your max IOB is 5.5 U.", src)
    assert any(v.kind == "ungrounded_number" for v in res.violations)


def test_integer_rounding_still_allowed_for_large_values():
    """62.4% narrated as 62% is a legitimate rounding, not a fabrication."""
    src = {"findings": [], "glycemia": {"tir": 62.4}}
    assert check_narrative("Time in range was 62%.", src).passed


def test_large_source_value_does_not_ground_arbitrary_numbers():
    """A 1% tolerance on an epoch-ms timestamp used to ground anything within ~1.8e10."""
    src = {"findings": [], "counterfactuals": [{"examples": [{"ts_ms": 1754006400000}]}]}
    res = check_narrative("The change was worth 1750000000 U.", src)
    assert any(v.kind == "ungrounded_number" for v in res.violations)
