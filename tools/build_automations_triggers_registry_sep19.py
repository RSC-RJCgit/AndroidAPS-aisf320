import re
from datetime import datetime
from pathlib import Path

from docx import Document
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt
from docx.enum.section import WD_SECTION

ROOT = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320")
BASE_DOC = Path(r"C:\winword\aaa\AutoISF Automations List mydoc Sep 18 26 current code registry DRAFT for review.docx")
SCRIPT_VERSION = 11

# Codes for keys whose note is NOT a literal string in their own block (computed codes, String.format,
# graph announcements, sibling-branch notes), taken from the full CarePortal code list
# (C:\winword\ccc\CarePortal Note Code mydoc complete 20260917_1345.docx, byte-identical to "Latest")
# by matching the key name -- or, for the TodOffset/Sensor/Exercise families, the family named in that
# list's own description. Shown with a trailing dagger. Keys with no entry in that list (e.g.
# LibreOver12Backfill, ConnectPod, Pod1, the *MetricsLog keys) stay blank; OldPod2 is dead code, and
# pTTOff (which mentions it) belongs to RecentPodOff, so it is deliberately not credited to OldPod2.
REFERENCE_FILL = {
    "HighDaytimeBrake": ["HiBrkDay", "HiBrkDayMid", "HiBrkDayCut"],
    "Test3": ["Test3"],
    "PpWeightRevertUnder8_5": ["PPrv", "ACrv", "PArv"],
    "PeakInsulinTimeDownTT": ["IP<val>"],
    "PeakInsulinTimeUpTT": ["IP<val>"],
    "LocationSmsThisPhoneTT": ["LocPhOn", "LocPhOff"],
    "WizardPctDownTT": ["W<val>%"],
    "WizardPctUpTT": ["W<val>%"],
    "PreSoakSensor24hrs": ["PreSoak24hrs"],
    "SensorS1hr": ["S1hr"],
    "SensorS2hr": ["S2hr"],
    "ExerciseLimitAcce": ["ST601k"],
    "EveningIobCeiling": ["EvCap", "EvCapR"],
    "NightIobCeiling": ["NtCap", "NtCapR"],
    "AlarmHypo1": ["H4"],
    "AlarmHypo2": ["A4", "H4"],
}
for _k in ("0002", "0204", "0406", "0609", "0912", "1218", "1822", "2200"):
    REFERENCE_FILL[f"TodOffset{_k}DownTT"] = ["T<sign><val>"]
    REFERENCE_FILL[f"TodOffset{_k}UpTT"] = ["T<sign><val>"]
# v1: initial 4-col triggers/reversion draft (mechanical if-condition extraction, "•" bullets,
#     placeholder text for empty reversion, original guessed column widths).
# v2: numbered lists ("1. 2. 3."), reversion cells left genuinely blank instead of a placeholder,
#     named-val resolution + independent multi-path trigger detection (fixed real extraction bugs
#     found in MoreMJ/HighDaytimeBrake/ProfileBatchRevertC), user's triggers2.docx column widths.
# v3: added 5th "Abbrev Note" column (addCarePortalNote() code(s) per automation), user's
#     triggers3.docx column widths and column-2 ("Coded key") font size bump to 9pt.
# v4: output filename itself now carries the version (was silently overwriting the same file
#     each run, against this project's own never-overwrite convention). Dead/vestigial keys
#     (referenced via readyToRun() somewhere but never markRun() anywhere -- e.g. OldPod2, a
#     permanently-inert guard term in two OTHER automations' recently-active checks) are now
#     detected and flagged instead of the note/trigger extractor sweeping in dozens of
#     unrelated automations' content while hunting for a block that doesn't exist. Tightened
#     the early-return-style (no brace body) fallback bound generally, for the same reason.
#     OR-alternative conditions (e.g. "(A && B) || C || (D && E)") are now broken out as their
#     own labelled PATH sub-groups with their own numbered AND-clauses, instead of one run-on
#     line joining every clause with the literal word "OR".
# v5: fixed a real structural bug -- "next key in key_positions" was assumed to mean "next
#     occurrence in the file", which is false whenever two keys are first co-mentioned together
#     (e.g. High6PP and HighOldPod inside recentPpBoostFire's shared OR-chain) but their real
#     blocks sit in a different relative order elsewhere; the resulting bound could land BEFORE
#     the key's own occurrence, silently emptying its notes/reversion. Now bounds against the
#     nearest true occurrence greater than the key's own, found across all 180, not just its
#     list-neighbour. Also: compactSettingNote(...) (37 calls, e.g. the whole PpWeight/Smb/Acce
#     Down/UpTT family) is now recognised as its own note-code family instead of being invisible
#     to the Abbrev Note column; startTempTargetIfNeeded/switchProfileIfNeeded durations that
#     aren't a literal number (a named var/expression) are now surfaced as "computed duration"
#     reversion signals instead of silently skipped; a single-condition PATH now renders on one
#     combined line instead of a header line plus a separate numbered item; the "no other
#     conditions" fallback message shortened to "no others"; column widths/filename per user's
#     own edited copy, with the filename's date+time now self-stamped from the actual save time
#     rather than hand-typed (so it can't drift from when the file was really written).
# v6: dropped the "Reversion triggers" column (95% blank -- confirmed only 19
#     startTempTargetIfNeeded/switchProfileIfNeeded calls exist across all 180 automations, so
#     there was rarely anything real to show; gather_reversion() is left in the script, just
#     unused, in case it's wanted back). Added a "Status" column: flags DISABLED when the
#     automation's own trigger resolves to a literal "false" (a hard-coded kill switch, e.g.
#     Bolus2's `val bolus2Enabled = false`) -- reuses the existing val-resolution machinery, no
#     new heuristic needed. Dead/vestigial keys (no markRun() anywhere) now also get a Status
#     flag ("DEAD/VESTIGIAL?") instead of only a long message in the Triggers column. User's
#     newest column widths.
# v7: (1) column widths are now applied to EVERY row's cells, not just the header -- body cells had
#     been keeping python-docx's default equal split (2016 twips each), which is why column "#"
#     kept rendering as wide as the text columns however the gridCol widths were set. (2) Status
#     column removed; DISABLED / DEAD/VESTIGIAL? now sit on a second line inside the "Coded key"
#     cell, as in the user's hand edit. (3) Reversion is back, as "REVERSION: ..." lines inside the
#     Triggers column, styled like PATH lines. (4) The throttle-only message is restored to
#     "Throttle-only guard (readyToRun N min); no others" -- v5/v6 had wrongly replaced the whole
#     sentence, not just its trailing phrase. (5) Primary-occurrence selection now recognises a
#     readyToRun() inside a MULTI-LINE if-condition (BolusGiven-style) and prefers the one nearest
#     its own markRun(), instead of requiring "if (" on the same line -- which had landed BolusGiven,
#     BolusGivenMild, BolusGivenBg3 etc. on the wrong occurrence (wrong triggers, empty notes).
#     (6) Function-style automations (bmildBasicCriteriaMet()/bg3BasicCriteriaMet()) are now read:
#     `if (!(A && B && readyToRun)) return false` is unwrapped to A/B, later `if (x) return`
#     guards show as NOT (x), and the closing `return <expr>` becomes its own PATH. Value-style
#     `val k = if (c) a else b` is no longer mistaken for a trigger PATH. (7) Early-return blocks
#     (`if (!readyToRun) return@run`) are now bounded by the enclosing run{}/function's own closing
#     brace instead of a fixed 700-char cap, which had cut StuckHighRescue's SHRto/SHTgt notes off.
# v8: helper-to-notes cross-reference. Every `fun` in the plugin is scanned for the note codes it
#     writes (following helpers that call helpers); an automation block that CALLS such a helper
#     is credited with those codes, marked with a trailing * in the Abbrev Note column (e.g.
#     BolusGivenMild -> applyBMildOutcomeFactors). Previously invisible to a per-block text scan.
#     Orchestrator functions (invoke/onStart/... > 8000 chars) are not followed. Notes are also
#     gathered from the brace block enclosing each markRun("KEY") -- the fire block -- for keys
#     whose trigger is picked from a separate criteria helper (BolusGivenMild/Bg3, UamBst ...).
# v9: remaining blank Abbrev Note cells filled from the full CarePortal code list (REFERENCE_FILL,
#     marked with a trailing dagger); keys with no entry in that list stay blank.
# v10: new "Actions" column (what the automation DOES when it fires): the state-changing calls found in the
#     automation's own block -- and in the brace block around its markRun() for keys whose trigger sits in a
#     criteria helper -- in source order, de-duplicated: acce weight, iobTH, other preferences, SMB delivery ratio,
#     temp targets, profile switches, automation states, graph announcements, SMS/notification text, and named
#     helper calls (applyBMildOutcomeFactors ...). Branch conditions are NOT shown (that is the Triggers column);
#     an automation with several branches lists every branch's actions together. Rebuilt from the plugin as of
#     19 Sep 2026 (HighDaytimeBrake/HighEveNightBrake: all-deltas-above-0 + raw UKF + HP>=6.5 gates, shared 30-min
#     lockout, TT-only (2 min) + pp weight action, mid band 7.5-9.0).
# v11: rebuilt from the plugin as of 20 Sep 2026 -- adds FastRiseToggleTT (List 2 5.226) and LowReboundGuardToggleTT (5.228);
#     HiBrk day/night blocks now sit behind the highDaytimeBrakeEnabled / highEveNightBrakeEnabled switches (both true).
OUTPUT = Path(
    rf"C:\winword\aaa\AutoISF Automations List mydoc {datetime.now():%b %d %y %H%M} "
    rf"code registry triggers v{SCRIPT_VERSION}.docx"
)
SOURCE_PATH = ROOT / "plugins" / "aps" / "src" / "main" / "kotlin" / "app" / "aaps" / "plugins" / "aps" / "openAPSAutoISF" / "OpenAPSAutoISFPlugin.kt"


def parse_true_keys_in_source_order(lines):
    pattern = re.compile(r'readyToRun\("([^"]+)"')
    seen = set()
    ordered = []
    for line in lines:
        for match in pattern.finditer(line):
            key = match.group(1)
            if key not in seen:
                seen.add(key)
                ordered.append(key)
    return ordered


def split_top_level(condition: str, op: str):
    """Split a boolean expression on a top-level && or || only (not inside parens)."""
    parts = []
    depth = 0
    current = []
    i = 0
    n = len(condition)
    op_len = len(op)
    while i < n:
        ch = condition[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if depth == 0 and condition[i:i + op_len] == op:
            parts.append("".join(current).strip())
            current = []
            i += op_len
            continue
        current.append(ch)
        i += 1
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return [p for p in parts if p]


def extract_if_condition(text: str, start_idx: int):
    """Given text and the index of an 'if' keyword's opening '(' character, return
    the full parenthesised condition (paren-balance aware) and the index just after it."""
    assert text[start_idx] == "("
    depth = 0
    i = start_idx
    n = len(text)
    # v10: `//` line comments are stripped from the RETURNED condition (positions are unaffected) so comment
    # words sitting between conditions never reach the Triggers column.
    while i < n:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return re.sub(r'(?<![:"\'])//[^\n]*', '', text[start_idx + 1:i]), i + 1
        i += 1
    return re.sub(r'(?<![:"\'])//[^\n]*', '', text[start_idx + 1:]), n


def find_enclosing_if(text: str, occurrence_idx: int):
    """Walk backward from occurrence_idx to find the nearest 'if (' whose condition
    contains occurrence_idx (i.e. the if-statement this readyToRun/markRun call sits inside)."""
    search_start = max(0, occurrence_idx - 1500)
    window = text[search_start:occurrence_idx + 1]
    if_positions = [m.start() for m in re.finditer(r'\bif\s*\(', window)]
    # Nearest first, but keep going outward: a nearer `if (` whose parentheses have already closed
    # before the occurrence is just an earlier, unrelated statement -- the real enclosing one
    # (e.g. a multi-line `if (a\n && b\n && readyToRun(...)) {`) can be further back.
    for pos in reversed(if_positions):
        paren_idx = window.index("(", pos)
        abs_paren_idx = search_start + paren_idx
        condition, end_idx = extract_if_condition(text, abs_paren_idx)
        if abs_paren_idx <= occurrence_idx <= end_idx:
            return condition, end_idx
    return None


VAL_DEF_RE = re.compile(r'\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)')
IF_STMT_RE = re.compile(r'\b(?:else\s+if|if)\s*\(')
BOOLEAN_LOOKING_RE = re.compile(
    r'&&|\|\||==|!=|<=|>=|(?<![<>=!])[<>](?!=)|readyToRun\(|checkAutomationState\(|'
    r'isTimeBetween\(|recentLibreOver12\(|\btrue\b|\bfalse\b'
)


def clean_expr(text: str) -> str:
    return re.sub(r'//.*$', '', text, flags=re.M).strip()   # v10: MULTILINE -- v9 left every non-final // comment in the bullets


def find_val_defs_in_range(text: str, start: int, end: int) -> dict:
    """Boolean-looking `val name = expr` definitions in [start, end), keyed by name.
    expr is collected across continuation lines that start with && or ||."""
    region = text[start:end]
    lines = region.splitlines()
    defs = {}
    for i, line in enumerate(lines):
        m = VAL_DEF_RE.search(line)
        if not m:
            continue
        name, expr = m.group(1), clean_expr(m.group(2))
        if expr.endswith("{") or not expr:
            continue
        j = i + 1
        while j < len(lines):
            nxt = lines[j].strip()
            # Continuation lines can lead with the operator ("&& foo", the common style here)
            # OR trail it on the PREVIOUS line ("foo ||", seen e.g. in
            # ah1b4RecentBolusOrCarbs's two-line definition) -- both must keep consuming.
            trailing_op = bool(re.search(r'(&&|\|\|)\s*$', expr))
            if nxt.startswith("&&") or nxt.startswith("||") or trailing_op:
                expr += " " + clean_expr(nxt)
                j += 1
            else:
                break
        if BOOLEAN_LOOKING_RE.search(expr):
            defs[name] = expr
    return defs


def resolve_expr(expr: str, val_defs: dict, depth: int = 0) -> str:
    """Substitute a bare identifier with its own `val` definition, recursively (capped)."""
    stripped = expr.strip()
    bare = re.fullmatch(r'(!?)\s*([A-Za-z_][A-Za-z0-9_]*)', stripped)
    if bare and depth < 3:
        name = bare.group(2)
        if name in val_defs:
            inner = resolve_expr(val_defs[name], val_defs, depth + 1)
            return f"NOT ({inner})" if bare.group(1) else inner
    return stripped


def bullets_from_condition(cond: str, val_defs: dict, depth: int = 0) -> list:
    bullets = []
    for clause in split_top_level(cond, "&&"):
        resolved_clause = resolve_expr(clause, val_defs)
        # A bare identifier can resolve to a whole multi-&&/|| expression -- re-split that
        # expansion into its own bullets too, instead of leaving it as one long line.
        if resolved_clause != clause.strip() and depth < 3 and re.search(r'&&|\|\|', resolved_clause):
            bullets.extend(bullets_from_condition(resolved_clause, val_defs, depth + 1))
            continue
        or_parts = split_top_level(resolved_clause, "||")
        if len(or_parts) > 1:
            bullets.append(" OR ".join(resolve_expr(p, val_defs).strip() for p in or_parts))
        else:
            bullets.append(resolved_clause.strip())
    cleaned, seen = [], set()
    for b in bullets:
        b_norm = re.sub(r'\s+', ' ', b).strip().rstrip(",")
        if not b_norm or b_norm in seen or re.fullmatch(r'!?readyToRun\([^)]*\)', b_norm):
            continue
        seen.add(b_norm)
        cleaned.append(b_norm)
    return cleaned


def expand_condition_to_paths(cond: str, val_defs: dict) -> list:
    """A condition that's fundamentally OR'd together at the top level -- e.g.
    "(A && B) || C || (D && E)" -- is really several independent alternative ways to satisfy
    the SAME trigger, not one run-on AND/OR line. Each OR-alternative becomes its own
    (label, bullets) entry (label "OR-alt N"), with its own AND-clauses split out normally.
    A condition with no top-level OR returns a single (None, bullets) entry as before."""
    resolved = resolve_expr(cond.strip(), val_defs)
    or_parts = split_top_level(resolved, "||")
    if len(or_parts) <= 1:
        bullets = bullets_from_condition(cond, val_defs)
        return [(None, bullets)] if bullets else []
    result = []
    for i, part in enumerate(or_parts, 1):
        bullets = bullets_from_condition(part, val_defs)
        if bullets:
            result.append((f"OR-alt {i}", bullets))
    return result


def find_peer_if_conditions(text: str, region_start: int, region_end: int) -> list:
    """if/else-if statements at brace-depth 0 relative to region_start only -- i.e. genuine
    sibling alternatives to the primary trigger (like MoreMJ's existingConditionsMet vs
    noRecentHighTrigger), not branches nested one level deeper inside an already-passed gate
    (like StuckHighRescue's hp>6.5 vs hp<=target, both inside the SAME outer `eligible` if)."""
    results = []
    depth = 0
    i = region_start
    while i < region_end:
        ch = text[i]
        if ch == "{":
            depth += 1
            i += 1
            continue
        if ch == "}":
            depth -= 1
            if depth < 0:
                # Exited the block whose body we were scanning -- stop, don't let a later,
                # unrelated block's own depth-0 ifs get mistaken for peers of this one.
                break
            i += 1
            continue
        if depth == 0:
            m = IF_STMT_RE.match(text, i)
            # A statement-level if starts its line (optionally after a closing brace). An
            # if-EXPRESSION used as a value -- `val stackK = if (x) 1.1 else 1.0` -- is not a
            # trigger path, so skip anything with other code before it on the same line.
            if m and text[text.rfind(chr(10), 0, i) + 1:i].strip() not in ("", "}"):
                m = None
            if m:
                paren_idx = text.index("(", m.end() - 1)
                cond, end_idx = extract_if_condition(text, paren_idx)
                results.append((m.start(), cond))
                i = end_idx
                continue
        i += 1
    return results


def enclosing_scope_end(text: str, pos: int, cap: int) -> int:
    """Index of the first UNMATCHED '}' after pos (end of the run { } / function that contains it),
    or `cap` if none is found first."""
    depth = 0
    i = pos
    n = min(len(text), cap)
    while i < n:
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            if depth == 0:
                return i
            depth -= 1
        i += 1
    return n


def find_own_block_end(text: str, occurrence_idx: int, upper_bound: int) -> int:
    """Where THIS automation's own enclosing block actually closes, so note extraction doesn't bleed
    into a later, unrelated automation (e.g. MoreMJ picking up High6PP's "P120"). A braced
    `if (...) { ... }` body ends at its matching '}'. An early-return guard (`if (!readyToRun(..))
    return@run`) or no enclosing if at all ends at the first unmatched '}' -- the end of the run {}
    or helper function that contains it (a fixed-size window had cut StuckHighRescue's own SHRto/
    SHTgt notes off, ~2000 chars after its guard). Always capped by `upper_bound`."""
    result = find_enclosing_if(text, occurrence_idx)
    if not result:
        return min(upper_bound, enclosing_scope_end(text, occurrence_idx, upper_bound))
    _, end_idx = result
    after = text[end_idx:end_idx + 20]
    stripped_after = after.lstrip()
    if not stripped_after.startswith("{"):
        return min(upper_bound, enclosing_scope_end(text, end_idx, upper_bound))
    brace_idx = end_idx + (len(after) - len(stripped_after))
    depth = 0
    i = brace_idx
    n = min(len(text), upper_bound)
    while i < n:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return upper_bound


def collect_expression(text: str, start: int) -> str:
    """A boolean expression starting at `start`, continued across lines while parentheses are open
    or the next code line leads with (or the current one trails) && / ||. Comment-only lines are
    skipped and trailing // comments dropped (inline /* */ comments are kept)."""
    lines = text[start:start + 4000].split(chr(10))
    expr = ""
    depth = 0
    for i, raw in enumerate(lines):
        code = re.sub(r'\s//.*$', '', raw).strip()
        if i > 0 and (not code or code.startswith("//")):
            continue
        if i > 0 and depth == 0 and not (code.startswith("&&") or code.startswith("||")
                                         or re.search(r'(&&|\|\|)\s*$', expr)):
            break
        expr += (" " if expr else "") + code
        depth += code.count("(") - code.count(")")
    return expr.strip()


def strip_negation_wrapper(cond: str):
    """`!( inner )` spanning the WHOLE condition -> inner, else None."""
    c = cond.strip()
    if not c.startswith("!("):
        return None
    depth = 0
    for i in range(1, len(c)):
        if c[i] == "(":
            depth += 1
        elif c[i] == ")":
            depth -= 1
            if depth == 0:
                return c[2:i].strip() if i == len(c) - 1 else None
    return None


def gather_trigger_bullets(text: str, occurrence_idx: int, block_end: int):
    """Returns a list of (path_label_or_None, bullets) tuples. A single-entry list with
    label None means "no distinct alternate paths -- just show the bullets flat"; more than
    one entry means genuinely independent trigger paths (e.g. MoreMJ's hypoalarm path vs its
    noRecentHighTrigger OR-path) that should be shown as separate labeled groups."""
    # Narrow lookback (was 3000 chars): a wider window risks picking up an unrelated
    # generically-named val (e.g. "ok", "result") from a DIFFERENT function/automation earlier
    # in this 10000+ line file and wrongly substituting it in here. Most automations define
    # their own gating vals within a page of their readyToRun() call.
    val_defs = find_val_defs_in_range(text, max(0, occurrence_idx - 900), block_end)
    result = find_enclosing_if(text, occurrence_idx)
    primary_paths = []
    body_start = occurrence_idx
    if result:
        condition, end_idx = result
        is_return_guard = text[end_idx:end_idx + 30].lstrip().startswith("return")
        unwrapped = strip_negation_wrapper(condition) if is_return_guard else None
        primary_paths = expand_condition_to_paths(unwrapped if unwrapped else condition, val_defs)
        # Advance past the '{' that opens this if's own body -- find_peer_if_conditions'
        # brace-depth-0 tracking must start INSIDE that body, not before it, or the body's own
        # opening brace gets miscounted as depth 1 and every real peer if/else-if inside it is
        # missed (while a later, unrelated automation's if can wrongly appear to be depth 0).
        # Only do this for a real `{ ... }` body -- an early-return style
        # `if (!readyToRun(...)) return@run` has no brace at all, and searching forward for
        # one would grab some unrelated LATER brace instead, corrupting the depth-0 anchor.
        after = text[end_idx:end_idx + 20]
        stripped_after = after.lstrip()
        if stripped_after.startswith("{"):
            brace_idx = end_idx + (len(after) - len(stripped_after))
            body_start = brace_idx + 1
        else:
            body_start = end_idx

    paths = list(primary_paths)

    # Any if/else-if that is a PEER of the primary condition (same brace depth, not nested
    # inside it) is a genuinely separate, independently-triggerable path -- e.g. MoreMJ's
    # `if (existingConditionsMet) {...} else if (noRecentHighTrigger) {...}`. A nested branch
    # deciding between ACTIONS under an already-passed gate (e.g. StuckHighRescue's
    # `if (eligible...) { if (hp > 6.5) ... else if (...) ... }`) is correctly excluded since
    # it sits one brace level deeper than its own gate, not at depth 0.
    guard_bullets = []
    for pos, cond in find_peer_if_conditions(text, body_start, block_end):
        # `if (x) return false` / `return@run` is a GUARD: to reach the action, x must be false.
        # Shown as "NOT (x)" alongside the primary conditions, not as an alternative trigger path.
        m_if = re.compile(r'\b(?:else\s+if|if)\s*\(').match(text, pos)
        _, after_idx = extract_if_condition(text, text.index("(", m_if.end() - 1))
        if text[after_idx:after_idx + 30].lstrip().startswith("return"):
            guard_bullets.append(f"NOT ({cond.strip()})")
            continue
        bare = re.fullmatch(r'!?\s*([A-Za-z_][A-Za-z0-9_]*)', cond.strip())
        if bare and bare.group(1) not in val_defs:
            # A bare, UNRESOLVED single identifier (e.g. "ok" holding a function's return
            # value, not a real condition made of sub-criteria) isn't an informative trigger --
            # skip rather than surface a meaningless single-word "condition".
            continue
        peer_paths = expand_condition_to_paths(cond, val_defs)
        base_label = bare.group(1) if bare else None
        for sub_label, bullets in peer_paths:
            if base_label and sub_label:
                combined = f"{base_label} {sub_label}"
            else:
                combined = base_label or sub_label
            paths.append((combined, bullets))

    if guard_bullets:
        if paths and paths[0][0] is None:
            paths[0] = (None, paths[0][1] + guard_bullets)
        else:
            paths.insert(0, (None, guard_bullets))

    # Function-style automation (bmildBasicCriteriaMet()/bg3BasicCriteriaMet() ...): after the
    # early-return guards the real criteria are one big `return <expr>`. Skip trivial returns
    # (`return false`, `return ok`) -- only a genuinely compound expression counts.
    if result and text[result[1]:result[1] + 30].lstrip().startswith("return"):
        m_ret = re.search(r'\n\s*return\s+(?!true\b|false\b|@)', text[body_start:block_end])
        if m_ret:
            expr = collect_expression(text, body_start + m_ret.end())
            if re.search(r'&&|\|\|', expr):
                for sub_label, bullets in expand_condition_to_paths(expr, val_defs):
                    paths.append((sub_label, bullets) if sub_label else (None, bullets))

    seen_sets, deduped = set(), []
    for label, bl in paths:
        key = tuple(bl)
        if key in seen_sets:
            continue
        seen_sets.add(key)
        deduped.append((label, bl))
    return deduped


def gather_reversion(text: str, occurrence_idx: int, next_key_idx):
    end_bound = next_key_idx if next_key_idx is not None else min(len(text), occurrence_idx + 8000)
    body = text[occurrence_idx:end_bound]
    notes = []
    for fn, phrase in (("startTempTargetIfNeeded", "Temp target auto-expires after"),
                       ("switchProfileIfNeeded", "Profile switch auto-reverts after")):
        for m in re.finditer(rf'{fn}\(([^()]*(?:\([^()]*\)[^()]*)*)\)', body):
            args = [a.strip() for a in m.group(1).split(",")]
            if len(args) < 2 or not args[1]:
                continue
            dur = args[1]
            lit = re.fullmatch(r'\d+', dur)
            if lit:
                minutes = int(dur)
                if minutes > 0:
                    notes.append(f"{phrase} {minutes} min")
            elif dur not in ("0", "durationInMinutes: Int", "targetMgdl: Double"):
                # Non-literal duration (a named var/expression) -- still a real reversion
                # signal, just can't be resolved to a fixed number mechanically.
                notes.append(f"{phrase} a computed duration ({dur}) -- check source for its value")
    if re.search(r'\bmarkRun\(', body) and re.search(r'Active["\']?\s*,\s*false\)|Active\s*=\s*false', body):
        notes.append("Clears its own \"Active\" state flag as part of the same action")
    # de-dup preserving order
    seen = set()
    cleaned = []
    for n in notes:
        if n not in seen:
            seen.add(n)
            cleaned.append(n)
    return cleaned


def detect_disabled(paths) -> bool:
    """A top-level "val xEnabled = false" (or similarly hard-coded-off) gate resolves to a
    literal "false" bullet once val-resolution runs (see gather_trigger_bullets) -- e.g.
    Bolus2's `val bolus2Enabled = false; if (bolus2Enabled && ...)`. Real, still-registered
    automations that simply never fire because of a hard-coded kill switch, not a bug."""
    for _, bullets in paths:
        for b in bullets:
            if b.strip().lower() in ("false", "not (true)"):
                return True
    return False


def direct_note_codes(body: str) -> list:
    codes = []
    for m in re.finditer(r'addCarePortalNote\(\s*"((?:[^"\\]|\\.)*)', body):
        prefix = m.group(1).split("${")[0].split("$")[0].strip()
        if prefix and prefix not in codes:
            codes.append(prefix)
    for m in re.finditer(r'compactSettingNote\(\s*"([^"]+)"', body):
        code = f"{m.group(1)}<val>"
        if code not in codes:
            codes.append(code)
    return codes


NOTE_HELPER_EXCLUDE = {"addCarePortalNote", "compactSettingNote", "sendSms", "markRun", "readyToRun"}


def build_helper_note_map(text: str) -> dict:
    """{function name -> note codes it writes, directly or via other helpers it calls}. Lets an
    automation whose block calls e.g. applyBMildOutcomeFactors() be credited with that helper's
    notes -- a per-block text scan alone can never see them."""
    funcs = {}
    for m in re.finditer(r'\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(', text):
        name = m.group(1)
        try:
            _, after_params = extract_if_condition(text, m.end() - 1)
        except Exception:
            continue
        head = text[after_params:after_params + 250]
        b = re.search(r'[{=]', head)
        if not b:
            continue
        if head[b.start()] == "{":
            depth, i, n = 0, after_params + b.start(), len(text)
            while i < n:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            body = text[after_params + b.start():i + 1]
        else:
            body = text[after_params + b.start():after_params + b.start() + 800].split(chr(10) + chr(10))[0]
        funcs[name] = body
    # Orchestrator functions (invoke(), onStart(), addPreferenceScreen() ...: tens of thousands of chars,
    # every automation inside them) are not helpers -- following a call into one would credit an
    # automation with every note in the file.
    funcs = {n: b for n, b in funcs.items() if len(b) <= 8000}
    direct = {n: direct_note_codes(b) for n, b in funcs.items() if n not in NOTE_HELPER_EXCLUDE}
    calls = {n: set(re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\(', b)) & (set(funcs) - NOTE_HELPER_EXCLUDE - {n})
             for n, b in funcs.items() if n not in NOTE_HELPER_EXCLUDE}
    result = {}
    for n in direct:
        seen, stack, codes = {n}, [n], []
        depth_guard = 0
        while stack and depth_guard < 400:
            depth_guard += 1
            cur = stack.pop()
            for c in direct.get(cur, []):
                if c not in codes:
                    codes.append(c)
            for callee in calls.get(cur, ()):
                if callee not in seen:
                    seen.add(callee)
                    stack.append(callee)
        if codes:
            result[n] = codes
    return result


def gather_abbrev_notes(text: str, occurrence_idx: int, next_key_idx, helper_notes=None):
    """Note codes written in this automation's own block: literal addCarePortalNote("CODE")
    (interpolated ones reduced to their static prefix), plus the compactSettingNote("PREFIX", ..)
    family as PREFIX<val>. With `helper_notes`, codes written by helper functions the block CALLS
    are added too, each marked with a trailing * (e.g. BMild -> applyBMildOutcomeFactors)."""
    end_bound = next_key_idx if next_key_idx is not None else min(len(text), occurrence_idx + 8000)
    body = text[occurrence_idx:end_bound]
    codes = direct_note_codes(body)
    if helper_notes:
        for callee in dict.fromkeys(re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\(', body)):
            for c in helper_notes.get(callee, ()):
                star = c + "*"
                if c not in codes and star not in codes:
                    codes.append(star)
    return codes


def notes_from_markrun_blocks(text: str, key: str, helper_notes) -> list:
    """Notes written in the brace block that encloses each markRun("KEY") call -- the fire block.
    The trigger may be picked from a criteria helper (bmildBasicCriteriaMet, bg3BasicCriteriaMet)
    while the note is written where the automation actually fires, next to its markRun. Blocks
    larger than 6000 chars are skipped (too broad -- they would credit unrelated automations)."""
    codes = []
    for m in re.finditer(r'markRun\("' + re.escape(key) + r'"\)', text):
        depth, i = 0, m.start()
        lo = max(0, m.start() - 6000)
        while i > lo:
            i -= 1
            if text[i] == "}":
                depth += 1
            elif text[i] == "{":
                if depth == 0:
                    break
                depth -= 1
        else:
            continue
        depth, j, n = 0, i, min(len(text), i + 6000)
        while j < n:
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        else:
            continue
        for c in gather_abbrev_notes(text, i, j + 1, helper_notes):
            if c not in codes:
                codes.append(c)
    return codes


# ---- v10: Actions column ---------------------------------------------------------------------------------------
ACTION_VERBS = ("set", "apply", "switch", "start", "cancel", "escalate", "revert", "launch", "install", "stage",
                "keep", "restore", "handle", "enable", "disable", "reset", "create", "remember", "try")
ACTION_NEVER = {"setIfSmaller", "setState", "setStateValues", "setBgAccelIsfWeight", "setSmbDeliveryRatio",
                "startTempTargetIfNeeded", "startTempTargetIfNeededAt", "setAutomationState", "readyToRun",
                "markRun", "handleDirectMjUserAction", "handleDirectSteroidUserAction"}
BOOKKEEPING_PREF = re.compile(r"(Ts|At|HandledAt|Timestamp|SinceTs|CreatedAt|Latched|Seen|Handled|Until)$")


def pretty_key(name: str) -> str:
    name = re.sub(r"^ApsAutoIsf", "", name)
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", name).strip()


def read_call_args(text: str, open_idx: int):
    """Balanced-paren argument text of the call whose '(' is at open_idx, and the index after ')'."""
    depth, i, n = 0, open_idx, len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i + 1
        i += 1
    return text[open_idx + 1:], n


def split_top_args(args: str):
    out, depth, cur, in_str = [], 0, "", False
    i = 0
    while i < len(args):
        ch = args[i]
        if in_str:
            cur += ch
            if ch == "\\" and i + 1 < len(args):
                cur += args[i + 1]
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            cur += ch
        elif ch in "([{":
            depth += 1
            cur += ch
        elif ch in ")]}":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
        i += 1
    if cur.strip():
        out.append(cur.strip())
    return out


def pretty_val(v: str) -> str:
    v = v.strip()
    m = re.fullmatch(r"preferences\.get\(\s*\w+\.(\w+)\s*\)", v)
    if m:
        return f"its {pretty_key(m.group(1))} value"
    c = re.search(r"/\*\s*([^*]+?)\s*\*/", v)
    base = re.sub(r"/\*.*?\*/", "", v).strip()
    base = re.sub(r"preferences\.get\(\s*\w+\.(\w+)\s*\)", lambda mm: pretty_key(mm.group(1)), base)
    base = re.sub(r"\$\{[^}]*\}?", "..", base)
    base = re.sub(r"\$\w+", "..", base)
    base = base.strip('"')
    base = re.sub(r"\s+", " ", base)
    if len(base) > 60:
        base = base[:57] + "..."
    return f"{base} ({c.group(1)})" if c and base else base


def sms_text(arg: str) -> str:
    a = arg.strip()
    m = re.match(r'"([^"$\\]*)(\$?)', a)   # literal prefix up to the first interpolation
    s = (m.group(1) + (".." if m.group(2) else "")) if m else a
    s = re.sub(r"\s+", " ", s).strip()
    return s if len(s) <= 70 else s[:67] + "..."


def tt_mmol(arg0: str) -> str:
    c = re.search(r"/\*\s*([\d.]+)\s*mmol\s*\*/", arg0)
    if c:
        return f"{c.group(1)} mmol"
    m = re.match(r"\s*(\d+(?:\.\d+)?)", arg0)
    if m:
        return f"{float(m.group(1)) / 18.0182:.1f} mmol"
    return arg0.strip()


def describe_action(name: str, args: str, fun_names):
    a = split_top_args(args)
    if name == "setBgAccelIsfWeight" and a:
        return f"acce weight -> {pretty_val(a[0])}"
    if name == "setSmbDeliveryRatio" and a:
        return f"SMB delivery ratio -> {pretty_val(a[0])}"
    if name in ("startTempTargetIfNeeded", "startTempTargetIfNeededAt") and len(a) >= 2:
        return f"start TT {tt_mmol(a[0])} for {pretty_val(a[1])} min"
    if name == "cancelCurrentTempTarget":
        return "cancel the current TT"
    if name == "setAutomationState" and len(a) >= 2:
        return f"automation state {pretty_val(a[0])} = {pretty_val(a[1])}"
    if name == "switchToLowAtSharedTier":
        return "profile -> Low role (shared tier)"
    if name == "switchToStandardAtSharedTier":
        return "profile -> Standard role (shared tier)"
    if name == "switchProfileIfNeeded" and a:
        m = re.search(r"\w+Key\.(\w+)", a[0])
        return f"profile -> {pretty_key(m.group(1)) if m else pretty_val(a[0])}"
    if name == "applyCurrentProfileAt100":
        return "profile back to 100%"
    if name == "startProfilePercentFor" and len(a) >= 2:
        return f"profile {pretty_val(a[0])}% for {pretty_val(a[1])} min"
    if name == "applyBMildOutcomeFactors":
        return "BMild outcome: SMB delivery ratio = mild base + increment, pp weight high (optional short 5.0 TT)"
    if name in ("sendSms", "sendSmsToNumbers") and a:
        return f'SMS "{sms_text(a[-1] if name == "sendSmsToNumbers" else a[0])}"'
    if name == "addNotification":
        tm = re.search(r'text\s*=\s*("(?:[^"\\]|\\.)*)', args)
        return f'notification "{sms_text(tm.group(1) + chr(34)) if tm else "..."}"'
    if name == "addGraphAnnouncement" and a:
        return f'graph announcement "{sms_text(a[0])}"'
    if name == "handleDirectMjUserAction":
        return "MJ button action (handleDirectMjUserAction)"
    if name == "handleDirectSteroidUserAction":
        return "Steroid button action (handleDirectSteroidUserAction)"
    if name in fun_names and name.startswith(ACTION_VERBS) and name not in ACTION_NEVER:
        short = ", ".join(pretty_val(x) for x in a[:3])
        return f"calls {name}({short})" if short else f"calls {name}()"
    return None


def actions_in_region(region: str, fun_names):
    """Ordered, de-duplicated action descriptions found in `region` (source text)."""
    region = re.sub(r"(?<![:\"'])//[^\n]*", "", region)   # ignore words that only appear inside line comments
    found = []  # (position, text)
    bookkeeping = 0
    names = ["setBgAccelIsfWeight", "setSmbDeliveryRatio", "startTempTargetIfNeeded", "startTempTargetIfNeededAt",
             "cancelCurrentTempTarget", "setAutomationState", "switchToLowAtSharedTier", "switchToStandardAtSharedTier",
             "switchProfileIfNeeded", "applyCurrentProfileAt100", "startProfilePercentFor", "applyBMildOutcomeFactors",
             "sendSms", "sendSmsToNumbers", "addGraphAnnouncement", "addNotification", "handleDirectMjUserAction",
             "handleDirectSteroidUserAction"]
    generic = sorted(n for n in fun_names if n.startswith(ACTION_VERBS) and n not in ACTION_NEVER and n not in names)
    for m in re.finditer(r"\b(" + "|".join(re.escape(n) for n in names + generic) + r")\s*\(", region):
        prev = region[max(0, m.start() - 5):m.start()]
        if "fun " in region[max(0, m.start() - 6):m.start()]:
            continue
        args, _ = read_call_args(region, m.end() - 1)
        d = describe_action(m.group(1), args, fun_names)
        if d:
            found.append((m.start(), d))
    for m in re.finditer(r"preferences\.put\(", region):
        args, _ = read_call_args(region, m.end() - 1)
        a = split_top_args(args)
        if len(a) < 2:
            continue
        km = re.fullmatch(r"(\w+Key)\.(\w+)", a[0])
        if not km:
            continue
        if BOOKKEEPING_PREF.search(km.group(2)):
            bookkeeping += 1
            continue
        kname = km.group(2)
        val = pretty_val(a[1])
        if kname == "ApsAutoIsfIobThPercent":
            d = f"iobTH -> {val}%"
        elif kname == "ApsAutoIsfPpWeight":
            d = f"pp weight -> {val}"
        else:
            d = f"{pretty_key(kname)} -> {val}"
        found.append((m.start(), d))
    found.sort()
    out = []
    for _, d in found:
        if d not in out:
            out.append(d)
    if bookkeeping:
        out.append(f"(+{bookkeeping} latch/timestamp bookkeeping writes)")
    return out


def markrun_block_spans(text: str, key: str):
    spans = []
    for m in re.finditer(r'markRun\("' + re.escape(key) + r'"\)', text):
        depth, i = 0, m.start()
        lo = max(0, m.start() - 6000)
        while i > lo:
            i -= 1
            if text[i] == "}":
                depth += 1
            elif text[i] == "{":
                if depth == 0:
                    break
                depth -= 1
        else:
            continue
        depth, j, n = 0, i, min(len(text), i + 6000)
        while j < n:
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        else:
            continue
        spans.append((i, j + 1))
    return spans


def gather_actions(text: str, occ: int, own_end: int, key: str, fun_names):
    out = list(actions_in_region(text[occ:own_end], fun_names))
    for i, j in markrun_block_spans(text, key):
        for d in actions_in_region(text[i:j], fun_names):
            if d not in out and not d.startswith("(+"):
                out.append(d)
    return out


def set_cell_width(cell, width):
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(width))
    tc_w.set(qn("w:type"), "dxa")


def set_cell_bullets(cell, lines, numbered=False, blank_if_empty=False):
    """`lines` items ending with ':' and starting with 'PATH' are treated as un-numbered
    path-group headers (numbering restarts after each one); every other item is a regular
    row, numbered "1. "/"2. "/... when `numbered` is True, bulleted ('\u2022 ') otherwise.
    `blank_if_empty` leaves the cell genuinely empty instead of writing a placeholder."""
    cell.text = ""
    if not lines:
        if blank_if_empty:
            return
        lines = ["--"]
    first = True
    counter = 1
    for line in lines:
        p = cell.paragraphs[0] if first else cell.add_paragraph()
        first = False
        # A line starting with "PATH" is always shown unnumbered/bold on its own line -- either
        # a header ("PATH 2 (...):") followed by its own numbered items, or, when a path has
        # only one condition, the whole thing pre-combined onto ONE line
        # ("PATH 1: condition text") by the caller, with no separate numbered item at all.
        is_header = line.startswith("PATH") or line.startswith("REVERSION")
        if is_header:
            p.text = line
            counter = 1
        elif numbered:
            p.text = f"{counter}. {line}"
            counter += 1
        else:
            p.text = f"\u2022 {line}"
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(1.5)
        for run in p.runs:
            run.font.size = Pt(7.2)
            run.bold = is_header


def configure_table(table, widths, font_size=7.4):
    table.autofit = False
    table.style = "Normal Table"
    tbl_pr = table._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(sum(widths)))
    tbl_w.set(qn("w:type"), "dxa")
    borders = tbl_pr.find(qn("w:tblBorders"))
    if borders is None:
        borders = OxmlElement("w:tblBorders")
        tbl_pr.append(borders)
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        border = OxmlElement(f"w:{edge}")
        border.set(qn("w:val"), "single")
        border.set(qn("w:sz"), "4")
        border.set(qn("w:space"), "0")
        border.set(qn("w:color"), "A6A6A6")
        borders.append(border)
    grid = table._tbl.tblGrid
    for child in list(grid):
        grid.remove(child)
    for width in widths:
        col = OxmlElement("w:gridCol")
        col.set(qn("w:w"), str(width))
        grid.append(col)
    header_pr = table.rows[0]._tr.get_or_add_trPr()
    header = OxmlElement("w:tblHeader")
    header.set(qn("w:val"), "true")
    header_pr.append(header)
    # Width must be set on EVERY row's cells, not just the header: with a fixed table layout Word
    # honours each cell's own tcW over gridCol, and body cells otherwise keep python-docx's default
    # equal split (10080/ncols) -- which is why the "#" column kept rendering as wide as the others.
    for row in table.rows:
        for col_index, cell in enumerate(row.cells):
            set_cell_width(cell, widths[col_index])
    for col_index, cell in enumerate(table.rows[0].cells):
        cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        for paragraph in cell.paragraphs:
            for run in paragraph.runs:
                run.font.size = Pt(font_size)
                run.bold = True


def add_page_numbers(doc):
    section = doc.sections[0]
    footer = section.footer
    p = footer.paragraphs[0] if footer.paragraphs else footer.add_paragraph()
    p.alignment = 1  # center
    run = p.add_run()
    fld_begin = OxmlElement("w:fldChar")
    fld_begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = "PAGE"
    fld_end = OxmlElement("w:fldChar")
    fld_end.set(qn("w:fldCharType"), "end")
    run._r.append(fld_begin)
    run._r.append(instr)
    run._r.append(fld_end)


def build():
    lines = SOURCE_PATH.read_text(encoding="utf-8").splitlines()
    text = SOURCE_PATH.read_text(encoding="utf-8")
    keys = parse_true_keys_in_source_order(lines)
    # A key that's never markRun()'d anywhere has no standalone action block of its own -- it's
    # only ever referenced as a readyToRun() guard term inside a DIFFERENT automation's "was X
    # recently active" check, which (since it's never marked) is permanently false/inert. Real
    # example found this session: OldPod2 -- referenced by recentPpBoostFire and podBoostRecent,
    # markRun("OldPod2") nowhere in source. Without this check, the extractor has no real block
    # to bound itself against and sweeps in dozens of unrelated automations' content instead.
    helper_notes = build_helper_note_map(text)
    fun_names = set(re.findall(r'\bfun\s+(?:[A-Za-z_][\w.<>]*\.)?([A-Za-z_]\w*)\s*\(', text))
    markrun_keys = set(re.findall(r'markRun\("([^"]+)"\)', text))

    # Locate each key's primary readyToRun/markRun occurrence as a character index into `text`.
    # A key can appear in an unrelated HELPER function first (e.g. HighDaytimeBrake's own
    # rearm-check helper at line ~2490, well before its real automation block at ~7945) -- so,
    # same heuristic as the base doc's extract_code_summary(), prefer the first occurrence whose
    # own line contains "if (" (or starts with "if") over a bare/earlier one.
    line_starts = [0]
    for ln in lines:
        line_starts.append(line_starts[-1] + len(ln) + 1)

    def line_index_for(pos):
        lo, hi = 0, len(line_starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if line_starts[mid] <= pos:
                lo = mid
            else:
                hi = mid - 1
        return lo

    key_positions = []
    for key in keys:
        ready_pat = re.compile(rf'readyToRun\("{re.escape(key)}"')
        mark_pat = re.compile(rf'markRun\("{re.escape(key)}"\)')
        candidates = [m.start() for m in ready_pat.finditer(text)]
        mark_positions = [m.start() for m in mark_pat.finditer(text)]
        occ = None
        # Real block = a readyToRun() that sits inside an if-condition (multi-line aware -- e.g.
        # BolusGiven's `if (profile_percentage == 100 && ...\n && readyToRun("BolusGiven", 5)) {`
        # has no "if (" on its own line) AND is followed shortly by this key's own markRun().
        # Nearest-to-markRun wins; candidates in helper vals/returns fail the if-test.
        scored = []
        for c in candidates:
            if find_enclosing_if(text, c) is None:
                continue
            ahead = [mp - c for mp in mark_positions if mp - c >= 0]
            scored.append((min(ahead) if ahead else 10**9, c))
        if scored:
            occ = min(scored)[1]
        if occ is None and candidates:
            occ = candidates[0]
        if occ is None:
            m = mark_pat.search(text)
            occ = m.start() if m else None
        key_positions.append((key, occ))

    # IMPORTANT: "next key in key_positions" is NOT the same as "next occurrence in the file".
    # keys is ordered by each key's FIRST raw readyToRun() text match, but the smart-selected
    # TRUE occurrence used everywhere below can land anywhere -- e.g. High6PP and HighOldPod are
    # both first mentioned together inside recentPpBoostFire's shared OR-chain (so they're
    # adjacent in `keys`), but High6PP's own real block sits at ~498107 while HighOldPod's real
    # block sits at ~412143 -- EARLIER in the file. Using "next key's occurrence" as an upper
    # bound then gives a bound SMALLER than High6PP's own occurrence, silently producing an
    # empty text[occ:bound] slice and wiping out its real notes/reversion. Bound against the
    # nearest TRUE occurrence that is actually greater than this key's own, found across ALL
    # keys, not just its neighbour in this list.
    sorted_positions = sorted(p for _, p in key_positions if p is not None)

    def next_true_occurrence_after(pos):
        lo, hi = 0, len(sorted_positions)
        while lo < hi:
            mid = (lo + hi) // 2
            if sorted_positions[mid] <= pos:
                lo = mid + 1
            else:
                hi = mid
        return sorted_positions[lo] if lo < len(sorted_positions) else None

    doc = Document(BASE_DOC)
    body = doc.element.body
    for child in list(body):
        if child.tag != qn("w:sectPr"):
            body.remove(child)

    title = doc.add_paragraph(f"AutoISF Coded Automations - Triggers Registry (DRAFT, v{SCRIPT_VERSION})", style="Heading 1")
    for run in title.runs:
        run.font.underline = True
    doc.add_paragraph(
        f"Generator script version {SCRIPT_VERSION} -- see build_automations_triggers_registry_sep19.py's "
        "own SCRIPT_VERSION comment for exactly what changed in each version."
    )
    doc.add_paragraph(
        "Generated 2026-09-19 from the Sep 18 26 current code registry (180 automations, source order). "
        "Triggers are extracted mechanically from each automation's own guarding if-condition(s) in "
        "OpenAPSAutoISFPlugin.kt, numbered one per top-level condition; named vals (e.g. \"eligible\", "
        "\"highEnough\") are resolved back to their own boolean definition rather than shown as a bare "
        "name, and a genuinely independent alternate trigger path (e.g. MoreMJ's hypoalarm path vs its "
        "separate noRecentHighTrigger OR-path) is listed as its own labelled PATH group -- within one "
        "PATH, every numbered item is AND'd together; separate PATH groups are OR'd against each other. "
        "\"Status\" flags DISABLED when the automation's own trigger resolves to a literal \"false\" "
        "(a hard-coded kill switch left in source, e.g. Bolus2's `val bolus2Enabled = false`) -- a real, "
        "still-registered automation that simply never fires, not an extraction failure. This is a "
        "The Actions column lists what the automation DOES, mechanically, in source order: the state-changing calls in its own block (and in the block around its markRun()) -- acce weight, iobTH, other settings, SMB delivery ratio, temp targets, profile switches, automation states, graph announcements, SMS text and named helper calls. Branch conditions are not shown there (see Triggers), so an automation with several branches lists every branch's actions together; a blank Actions cell means the extractor found no such call in the block (e.g. a note-only or helper-driven automation -- see its note codes). In the Abbrev Note column a trailing * marks a code written by a helper function the block calls "
        "(the helper may write it only on some paths), not by the block itself. A trailing † marks a code "
        "not visible in the block at all (computed/String.format code, graph announcement, sibling branch) that was "
        "matched from the full CarePortal code list by key name or family instead. "
        "This is a mechanical, best-effort extraction -- verify against source before relying on any single row."
    )

    table = doc.add_table(rows=1, cols=5)
    headers = ["#", "Coded key", "Triggers (enumerated)", "Actions", "Abbrev Note"]
    for cell, value in zip(table.rows[0].cells, headers):
        cell.text = value

    def set_key_cell(cell, key, flag=None):
        """Key at 9pt; an optional flag (DISABLED / DEAD/VESTIGIAL?) goes on a second line of the
        SAME paragraph, same size, exactly as in the user's hand-edited copy."""
        cell.text = ""
        para = cell.paragraphs[0]
        run = para.add_run(key)
        run.font.size = Pt(9.0)
        if flag:
            run.add_break()
            flag_run = para.add_run(flag)
            flag_run.font.size = Pt(9.0)

    for idx, (key, occ) in enumerate(key_positions, 1):
        row = table.add_row().cells
        row[0].text = str(idx)
        if occ is None:
            set_key_cell(row[1], key)
            set_cell_bullets(row[2], ["Occurrence not found by extractor -- inspect source"], numbered=True)
            row[3].text = ""
            row[4].text = ""
            continue
        if key not in markrun_keys:
            set_key_cell(row[1], key, "DEAD/VESTIGIAL?")
            set_cell_bullets(
                row[2],
                [f"No markRun(\"{key}\") anywhere in source -- this key has no standalone action "
                 f"block. It's referenced only as a readyToRun() guard term inside a DIFFERENT "
                 f"automation's own recently-active check, which (since it's never marked) is "
                 f"permanently inert. Likely a dead/vestigial registry entry -- verify before "
                 f"treating this as a real, independently-firing automation."],
                numbered=True,
            )
            row[3].text = ""
            row[4].text = ""
            continue
        next_occ = next_true_occurrence_after(occ)
        block_end = next_occ if next_occ is not None else min(len(text), occ + 8000)
        paths = gather_trigger_bullets(text, occ, block_end)
        # Tighter bound for note/reversion extraction -- stop at THIS automation's own closing
        # brace rather than running all the way to the next tracked key, which can be far enough
        # away to bleed into an unrelated automation's own addCarePortalNote() call (e.g. MoreMJ's
        # block otherwise picking up High6PP's "P120" note).
        own_end = find_own_block_end(text, occ, block_end)
        abbrev_notes = gather_abbrev_notes(text, occ, own_end, helper_notes)
        for c in notes_from_markrun_blocks(text, key, helper_notes):
            if c not in abbrev_notes and c.rstrip('*') not in [a.rstrip('*') for a in abbrev_notes]:
                abbrev_notes.append(c)
        reversion = gather_reversion(text, occ, own_end)
        if not abbrev_notes and key in REFERENCE_FILL:
            abbrev_notes = [c + "†" for c in REFERENCE_FILL[key]]
        set_key_cell(row[1], key, "DISABLED" if detect_disabled(paths) else None)
        if not paths:
            throttle = re.match(r'readyToRun\("[^"]+",\s*([^)]+?)\s*\)', text[occ:occ + 120])
            mins = f" {throttle.group(1)} min" if throttle and re.fullmatch(r"\d+", throttle.group(1)) else ""
            triggers = [f"Throttle-only guard (readyToRun{mins}); no others"]
        elif len(paths) == 1:
            triggers = paths[0][1]
        else:
            triggers = []
            for i, (label, bullets) in enumerate(paths, 1):
                if label and label.startswith("OR-alt"):
                    suffix = f" ({label})"
                elif label:
                    suffix = f" (independent -- via '{label}')"
                else:
                    suffix = ""
                if len(bullets) == 1:
                    # Single-condition path: whole thing on one line, no separate numbered item.
                    triggers.append(f"PATH {i}{suffix}: {bullets[0]}")
                else:
                    triggers.append(f"PATH {i}{suffix}:")
                    triggers.extend(bullets)
        # Reversion signals found in this same block go in as their own REVERSION lines, like PATHs.
        triggers.extend(f"REVERSION: {r}" for r in reversion)
        set_cell_bullets(row[2], triggers, numbered=True)
        actions = gather_actions(text, occ, own_end, key, fun_names)
        set_cell_bullets(row[3], actions, numbered=True, blank_if_empty=True)
        row[4].text = ", ".join(abbrev_notes)
        for para in row[4].paragraphs:
            for run in para.runs:
                run.font.size = Pt(7.2)

    configure_table(table, [470, 1500, 3500, 3400, 1190])
    add_page_numbers(doc)
    doc.save(OUTPUT)
    return len(keys)


count = build()
print(OUTPUT)
print(f"rows={count}")
