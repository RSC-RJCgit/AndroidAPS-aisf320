/*
 * Browser entry for the oref oracle. Bundled (esbuild) into web/oref-bundle.js, which
 * exposes globalThis.orefDetermine — the SAME real oref0 determine-basal used server-side,
 * now callable from the Pyodide app. Same request/result shape as oracle/determine.js.
 */
import determine_basal from 'oref0/lib/determine-basal/determine-basal';
import tempBasalFunctions from 'oref0/lib/basal-set-temp';

function runOne(req) {
  const currentTime = req.currentTime != null ? new Date(req.currentTime) : new Date();
  return determine_basal(
    req.glucose_status, req.currenttemp, req.iob_data, req.profile,
    req.autosens_data || { ratio: 1.0 }, req.meal_data || {},
    tempBasalFunctions, req.microBolusAllowed === true, req.reservoir_data, currentTime
  );
}

// requests: array of determine-basal input objects. Returns [{ok, rt} | {ok:false, error}].
globalThis.orefDetermine = function orefDetermine(requests) {
  return (requests || []).map((req, i) => {
    try {
      return { ok: true, rt: runOne(req) };
    } catch (e) {
      return { ok: false, index: i, error: String(e && e.message ? e.message : e) };
    }
  });
};
