#!/usr/bin/env python3
"""Build a bolus-calculator patch against AndroidAPS-3426 (3.4.2.6+aisf3.2.1)."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

OURS = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320")
BASE = Path(r"C:\Users\arjay\StudioProjects\AndroidAPS-3426")
OUT = OURS / "patches" / "bolus-calculator-on-3426-aisf321.patch"

FULL_COPY = [
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/BolusWizard.kt",
    "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/DelayedBolusWorker.kt",
    "ui/src/main/kotlin/app/aaps/ui/dialogs/WizardDialog.kt",
    "ui/src/main/res/layout/dialog_wizard.xml",
    "plugins/main/src/main/res/layout/dialog_quick_wizard_max_bolus.xml",
    "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/ScheduledDoseSupersession.kt",
    "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/utils/NoteTimestampAllocator.kt",
]


def rel(p: str) -> Path:
    return Path(p.replace("/", os.sep))


def read(root: Path, p: str) -> str:
    return (root / rel(p)).read_text(encoding="utf-8")


def write(root: Path, p: str, text: str) -> None:
    dest = root / rel(p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8", newline="\n")


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)


def copy_ours(staging: Path, p: str) -> None:
    src = OURS / rel(p)
    dest = staging / rel(p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    raw = dest.read_text(encoding="utf-8")
    dest.write_text(raw.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def copy_base(staging: Path, p: str) -> None:
    src = BASE / rel(p)
    dest = staging / rel(p)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    raw = dest.read_text(encoding="utf-8")
    dest.write_text(raw.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def apply_surgical(staging: Path) -> None:
    # BooleanKey
    p = "core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    WizardCorrectionPercent("wizard_correction_percent", defaultValue = false),\n    WizardIncludeCob("wizard_include_cob", defaultValue = false),',
        '    WizardCorrectionPercent("wizard_correction_percent", defaultValue = false),\n'
        '    // Delayed bolus (50%-profile wizard mechanism). Constant renamed from WizardSplitBolusEnabled;\n'
        '    // the stored key string is kept so existing users\' setting survives the rename.\n'
        '    WizardDelayedBolusEnabled("wizard_split_bolus_enabled", defaultValue = false),\n'
        '    WizardIncludeCob("wizard_include_cob", defaultValue = false),',
        "BooleanKey",
    )
    write(staging, p, t)

    # LongKey
    p = "core/keys/src/main/kotlin/app/aaps/core/keys/LongKey.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    AppStart("app_start_time", 0, defaultedBySM = true),\n',
        '    AppStart("app_start_time", 0, defaultedBySM = true),\n'
        '    DelayedBolusBlockSmbUntil("delayed_bolus_block_smb_until", 0, defaultedBySM = true),\n',
        "LongKey",
    )
    write(staging, p, t)

    # CoreModule
    p = "core/objects/src/main/kotlin/app/aaps/core/objects/di/CoreModule.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.objects.wizard.QuickWizardEntry\n",
        "import app.aaps.core.objects.wizard.QuickWizardEntry\nimport app.aaps.core.objects.wizard.DelayedBolusWorker\n",
        "CoreModule import",
    )
    t = must_replace(
        t,
        "        @ContributesAndroidInjector fun quickWizardEntryInjector(): QuickWizardEntry\n",
        "        @ContributesAndroidInjector fun quickWizardEntryInjector(): QuickWizardEntry\n"
        "        @ContributesAndroidInjector fun delayedBolusWorkerInjector(): DelayedBolusWorker\n",
        "CoreModule injector",
    )
    write(staging, p, t)

    # BolusProgressData
    p = "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/BolusProgressData.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "        stopPressed = false\n        status = \"\"",
        "        stopPressed = false\n        followUpBolusCancelled = false\n        status = \"\"",
        "BolusProgressData set()",
    )
    t = must_replace(
        t,
        "    var stopPressed = false\n}",
        "    var stopPressed = false\n\n"
        "    /**\n"
        "     * Set when stop is pressed — prevents any scheduled follow-up doses from firing.\n"
        "     * Cleared when a new bolus starts via set(). See ScheduledDoseSupersession for the\n"
        "     * separate mechanism that detects a newer bolus/carbs entry.\n"
        "     */\n"
        "    var followUpBolusCancelled = false\n}",
        "BolusProgressData field",
    )
    write(staging, p, t)

    # QuickWizardEntry
    p = "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/QuickWizardEntry.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "            quickWizard = true,\n            positiveIOBOnly = uPositiveIOBOnly\n        ) //tbc, ok if only quickwizard, but if other sources elsewhere use Sources.QuickWizard\n    }",
        "            quickWizard = true,\n            positiveIOBOnly = uPositiveIOBOnly,\n"
        "            walkingSoon = useWalkingSoon() == YES\n"
        "        ) //tbc, ok if only quickwizard, but if other sources elsewhere use Sources.QuickWizard\n"
        "        wizard.manualSplitBolusEnabled = useSplitBolus() == YES\n"
        "        wizard.manualSplitBolusIntervalMins = splitBolusIntervalMins()\n"
        "        return wizard\n    }",
        "QuickWizardEntry doCalc",
    )
    t = must_replace(
        t,
        "        val percentage = if (usePercentage() == DEFAULT) preferences.get(IntKey.OverviewBolusPercentage) else percentage()\n        return bolusWizardProvider.get().doCalc(",
        "        val percentage = if (usePercentage() == DEFAULT) preferences.get(IntKey.OverviewBolusPercentage) else percentage()\n        val wizard = bolusWizardProvider.get().doCalc(",
        "QuickWizardEntry val wizard",
    )
    t = must_replace(
        t,
        '    fun useAlarm(): Int = safeGetInt(storage, "useAlarm", NO)\n}',
        '    fun useAlarm(): Int = safeGetInt(storage, "useAlarm", NO)\n\n'
        '    fun useWalkingSoon(): Int = safeGetInt(storage, "useWalkingSoon", NO)\n\n'
        '    fun useSplitBolus(): Int = safeGetInt(storage, "useSplitBolus", NO)\n\n'
        '    fun splitBolusIntervalMins(): Int = safeGetInt(storage, "splitBolusIntervalMins", 7)\n}',
        "QuickWizardEntry getters",
    )
    write(staging, p, t)

    # OverviewPlugin
    p = "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewPlugin.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusAdvisor, summary = R.string.enable_bolus_advisor_summary, title = R.string.enable_bolus_advisor))\n            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusReminder, summary = R.string.enablebolusreminder_summary, title = R.string.enablebolusreminder))",
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusAdvisor, summary = R.string.enable_bolus_advisor_summary, title = R.string.enable_bolus_advisor))\n"
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.WizardDelayedBolusEnabled, summary = R.string.wizard_split_bolus_summary, title = R.string.wizard_split_bolus_title))\n"
        "            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.OverviewUseBolusReminder, summary = R.string.enablebolusreminder_summary, title = R.string.enablebolusreminder))",
        "OverviewPlugin pref",
    )
    write(staging, p, t)

    # strings
    p = "plugins/main/src/main/res/values/strings.xml"
    t = read(BASE, p)
    t = must_replace(
        t,
        '    <string name="enable_bolus_advisor">Enable bolus advisor</string>\n',
        '    <string name="quick_wizard_max_bolus_title">Quick Wizard bolus limit</string>\n'
        '    <string name="quick_wizard_max_bolus_this_bolus">Max bolus (this bolus only)</string>\n'
        '    <string name="quick_wizard_max_bolus_not_saved">This temporary limit is restored after this Quick Wizard attempt.</string>\n'
        '    <string name="quick_wizard_max_bolus_summary">Calculated: %1$.2f U\\nAllowed now: %2$.2f U</string>\n'
        '    <string name="wizard_split_bolus_title">Enable delayed bolus</string>\n'
        '    <string name="wizard_split_bolus_summary">When profile is 50%: deliver the remaining gap ×90% at +10/20/30 min once BGL rising criteria are met. SMBs are blocked during the check window.</string>\n'
        '    <string name="enable_bolus_advisor">Enable bolus advisor</string>\n',
        "strings.xml",
    )
    write(staging, p, t)

    # OpenAPSAutoISFPlugin SMB block
    p = "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100\n        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()",
        "        val profile_percentage = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100\n"
        "        val delayedBolusBlockUntil = preferences.get(LongKey.DelayedBolusBlockSmbUntil)\n"
        "        val microBolusAllowed = if (delayedBolusBlockUntil > dateUtil.now()) {\n"
        "            inputConstraints.copyReasons(ConstraintObject(false, aapsLogger).also { it.set(false, \"Delayed bolus active — SMBs blocked until ${dateUtil.timeString(delayedBolusBlockUntil)}\", this) })\n"
        "            false\n"
        "        } else {\n"
        "            constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()\n"
        "        }",
        "OpenAPSAutoISFPlugin microBolusAllowed",
    )
    write(staging, p, t)

    # OverviewFragment
    p = "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.objects.wizard.QuickWizard\n",
        "import app.aaps.core.objects.wizard.BolusWizard\n"
        "import app.aaps.core.objects.wizard.QuickWizard\n"
        "import app.aaps.core.objects.wizard.QuickWizardEntry\n",
        "OverviewFragment wizard imports",
    )
    t = must_replace(
        t,
        "import app.aaps.plugins.main.databinding.OverviewFragmentBinding\n",
        "import app.aaps.plugins.main.databinding.DialogQuickWizardMaxBolusBinding\n"
        "import app.aaps.plugins.main.databinding.OverviewFragmentBinding\n",
        "OverviewFragment binding import",
    )
    t = must_replace(
        t,
        "    private var _binding: OverviewFragmentBinding? = null\n",
        "    private var _binding: OverviewFragmentBinding? = null\n"
        "    private var quickWizardMaxBolusDialog: androidx.appcompat.app.AlertDialog? = null\n"
        "    private var quickWizardOriginalSafetyMaxBolus: Double? = null\n",
        "OverviewFragment fields",
    )
    t = must_replace(
        t,
        "    override fun onDestroy() {\n        super.onDestroy()\n        handler.removeCallbacksAndMessages(null)\n        handler.looper.quitSafely()\n    }",
        "    override fun onDestroy() {\n"
        "        super.onDestroy()\n"
        "        quickWizardMaxBolusDialog?.dismiss()\n"
        "        quickWizardMaxBolusDialog = null\n"
        "        restoreQuickWizardSafetyMaxBolus()\n"
        "        handler.removeCallbacksAndMessages(null)\n"
        "        handler.looper.quitSafely()\n    }",
        "OverviewFragment onDestroy",
    )
    t = must_replace(
        t,
        """    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val pump = activePlugin.activePump
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
                activity?.let {
                    if (abs(wizard.insulinAfterConstraints - wizard.calculatedTotalInsulin) >= pump.pumpDescription.pumpType.determineCorrectBolusStepSize(wizard.insulinAfterConstraints) || carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\\n" + rh.gs(R.string.change_your_input))
                        return
                    }
                    wizard.confirmAndExecute(it, quickWizardEntry)
                }
            }
        }
    }
""",
        """    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
                activity?.let {
                    if (carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\\n" + rh.gs(R.string.change_your_input))
                        return
                    }
                    val maxBolusAllowed = constraintChecker.getMaxBolusAllowed().value()
                    if (wizard.calculatedTotalInsulin > maxBolusAllowed && maxBolusAllowed > 0.0) {
                        showQuickWizardMaxBolusDialog(it, quickWizardEntry)
                    } else {
                        wizard.confirmAndExecute(it, quickWizardEntry)
                    }
                }
            }
        }
    }

    private fun showQuickWizardMaxBolusDialog(
        activity: androidx.fragment.app.FragmentActivity,
        quickWizardEntry: QuickWizardEntry
    ) {
        quickWizardMaxBolusDialog?.dismiss()
        restoreQuickWizardSafetyMaxBolus()

        val originalMaxBolus = preferences.get(DoubleKey.SafetyMaxBolus)
        quickWizardOriginalSafetyMaxBolus = originalMaxBolus
        val dialogBinding = DialogQuickWizardMaxBolusBinding.inflate(layoutInflater)
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep.takeIf { it > 0.0 } ?: 0.05

        fun recalculateAndShowSummary(): BolusWizard? {
            val currentProfile = profileFunction.getProfile() ?: return null
            val currentBg = iobCobCalculator.ads.actualBg() ?: return null
            val recalculated = quickWizardEntry.doCalc(currentProfile, profileFunction.getProfileName(), currentBg)
            dialogBinding.summary.text = rh.gs(
                R.string.quick_wizard_max_bolus_summary,
                recalculated.calculatedTotalInsulin,
                recalculated.insulinAfterConstraints
            )
            return recalculated
        }

        dialogBinding.maxBolusInput.setParams(
            originalMaxBolus,
            0.1,
            60.0,
            bolusStep,
            decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
            false,
            null
        )
        dialogBinding.maxBolusInput.setOnValueChangedListener {
            preferences.put(DoubleKey.SafetyMaxBolus, dialogBinding.maxBolusInput.value)
            recalculateAndShowSummary()
        }
        recalculateAndShowSummary()

        quickWizardMaxBolusDialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(rh.gs(R.string.quick_wizard_max_bolus_title))
            .setView(dialogBinding.root)
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .setPositiveButton(app.aaps.core.ui.R.string.ok) { _, _ ->
                recalculateAndShowSummary()?.confirmAndExecute(activity, quickWizardEntry)
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    restoreQuickWizardSafetyMaxBolus()
                    quickWizardMaxBolusDialog = null
                }
                dialog.show()
            }
    }

    private fun restoreQuickWizardSafetyMaxBolus() {
        quickWizardOriginalSafetyMaxBolus?.let { preferences.put(DoubleKey.SafetyMaxBolus, it) }
        quickWizardOriginalSafetyMaxBolus = null
    }
""",
        "OverviewFragment onClickQuickWizard",
    )
    write(staging, p, t)

    # EditQuickWizardDialog.kt
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/EditQuickWizardDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        '                    entry.storage.put("carbs2", carbs2)\n                } catch (e: JSONException) {',
        '                    entry.storage.put("carbs2", carbs2)\n'
        '                    entry.storage.put("useWalkingSoon", checkBoxToRadioNumbers(binding.walkingSoonCheckbox.isChecked))\n'
        '                    entry.storage.put("useSplitBolus", checkBoxToRadioNumbers(binding.splitBolusCheckbox.isChecked))\n'
        '                    entry.storage.put("splitBolusIntervalMins", binding.splitBolusIntervalInput.value.toInt())\n'
        "                } catch (e: JSONException) {",
        "EditQuickWizardDialog save",
    )
    t = must_replace(
        t,
        """        binding.carbTimeInput.setParams(
            savedInstanceState?.getDouble("carb_time_input")
                ?: 0.0, -60.0, 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
        )

        binding.correctionInput.value = entry.percentage().toDouble()
""",
        """        binding.carbTimeInput.setParams(
            savedInstanceState?.getDouble("carb_time_input")
                ?: 0.0, -60.0, 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
        )

        binding.splitBolusIntervalInput.setParams(
            savedInstanceState?.getDouble("split_bolus_interval_input")
                ?: 7.0, 1.0, 60.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.correctionInput.value = entry.percentage().toDouble()
""",
        "EditQuickWizardDialog setParams",
    )
    t = must_replace(
        t,
        "        useECarbs(radioNumbersToCheckBox(entry.useEcarbs()))\n\n        binding.useCob.setOnCheckedChangeListener { _, _ -> processCob() }",
        "        useECarbs(radioNumbersToCheckBox(entry.useEcarbs()))\n\n"
        "        binding.walkingSoonCheckbox.isChecked = radioNumbersToCheckBox(entry.useWalkingSoon())\n"
        "        binding.splitBolusCheckbox.isChecked = radioNumbersToCheckBox(entry.useSplitBolus())\n"
        "        binding.splitBolusIntervalInput.value = SafeParse.stringToDouble(entry.splitBolusIntervalMins().toString())\n"
        "        processSplitBolus()\n"
        "        binding.splitBolusCheckbox.setOnCheckedChangeListener { _, _ -> processSplitBolus() }\n\n"
        "        binding.useCob.setOnCheckedChangeListener { _, _ -> processCob() }",
        "EditQuickWizardDialog load",
    )
    t = must_replace(
        t,
        "    override fun onClick(v: View?) {\n        //\n    }",
        """    private fun processSplitBolus() {
        val visibility = if (binding.splitBolusCheckbox.isChecked) View.VISIBLE else View.GONE
        binding.splitBolusIntervalInput.visibility = visibility
        binding.splitBolusIntervalUnit.visibility = visibility
    }

    override fun onClick(v: View?) {
        //
    }""",
        "EditQuickWizardDialog processSplitBolus",
    )
    write(staging, p, t)

    # dialog_edit_quickwizard.xml
    p = "ui/src/main/res/layout/dialog_edit_quickwizard.xml"
    t = read(BASE, p)
    t = must_replace(
        t,
        """            <CheckBox
                android:id="@+id/use_ecarbs"
                style="@style/Widget.App.CheckBox"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:lines="1"
                android:text="@string/additional_ecarbs"
                android:textAppearance="@style/TextAppearance.AppCompat.Medium" />

        </LinearLayout>
""",
        """            <CheckBox
                android:id="@+id/use_ecarbs"
                style="@style/Widget.App.CheckBox"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:lines="1"
                android:text="@string/additional_ecarbs"
                android:textAppearance="@style/TextAppearance.AppCompat.Medium" />

            <CheckBox
                android:id="@+id/walking_soon_checkbox"
                style="@style/Widget.App.CheckBox"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:lines="1"
                android:text="Walking soon (50% now, rest later if needed)"
                android:textAppearance="@style/TextAppearance.AppCompat.Medium"
                tools:ignore="HardcodedText" />

            <LinearLayout
                android:id="@+id/split_bolus_row"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:gravity="start|center_vertical"
                android:orientation="horizontal">

                <CheckBox
                    android:id="@+id/split_bolus_checkbox"
                    style="@style/Widget.App.CheckBox"
                    android:layout_width="wrap_content"
                    android:layout_height="match_parent"
                    android:gravity="start|center_vertical"
                    android:lines="1"
                    android:text="Split bolus every"
                    android:textAppearance="@style/TextAppearance.AppCompat.Medium"
                    tools:ignore="HardcodedText" />

                <app.aaps.core.ui.elements.NumberPicker
                    android:id="@+id/split_bolus_interval_input"
                    android:layout_width="100dp"
                    android:layout_height="40dp"
                    android:layout_marginStart="4dp"
                    android:layout_marginEnd="4dp" />

                <TextView
                    android:id="@+id/split_bolus_interval_unit"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center_vertical"
                    android:text="@string/unit_minute_short"
                    android:textAppearance="?android:attr/textAppearanceSmall" />

            </LinearLayout>

        </LinearLayout>
""",
        "dialog_edit_quickwizard.xml",
    )
    write(staging, p, t)

    # BolusProgressDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/BolusProgressDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "            BolusProgressData.stopPressed = true\n            binding.stopPressed.visibility = View.VISIBLE",
        "            BolusProgressData.stopPressed = true\n            BolusProgressData.followUpBolusCancelled = true\n            binding.stopPressed.visibility = View.VISIBLE",
        "BolusProgressDialog stop",
    )
    write(staging, p, t)

    # InsulinDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/InsulinDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\n",
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "InsulinDialog import",
    )
    t = must_replace(
        t,
        """                            if (timeOffset == 0)
                                automation.removeAutomationEventBolusReminder()
                        } else {""",
        """                            if (timeOffset == 0)
                                automation.removeAutomationEventBolusReminder()
                            ScheduledDoseSupersession.bump()
                        } else {""",
        "InsulinDialog record-only bump",
    )
    t = must_replace(
        t,
        """                                    } else {
                                        automation.removeAutomationEventBolusReminder()
                                    }""",
        """                                    } else {
                                        automation.removeAutomationEventBolusReminder()
                                        ScheduledDoseSupersession.bump()
                                    }""",
        "InsulinDialog delivery bump",
    )
    write(staging, p, t)

    # CarbsDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/CarbsDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\n",
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "CarbsDialog import",
    )
    t = must_replace(
        t,
        """                                } else if (preferences.get(BooleanKey.OverviewUseBolusReminder) && remindBolus)
                                    automation.scheduleAutomationEventBolusReminder()""",
        """                                } else {
                                    if (preferences.get(BooleanKey.OverviewUseBolusReminder) && remindBolus)
                                        automation.scheduleAutomationEventBolusReminder()
                                    ScheduledDoseSupersession.bump()
                                }""",
        "CarbsDialog bump",
    )
    write(staging, p, t)

    # TreatmentDialog
    p = "ui/src/main/kotlin/app/aaps/ui/dialogs/TreatmentDialog.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\n",
        "import app.aaps.core.interfaces.pump.DetailedBolusInfo\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "TreatmentDialog import",
    )
    t = must_replace(
        t,
        """                            ).subscribe()
                    } else {
                        if (detailedBolusInfo.insulin > 0) {""",
        """                            ).subscribe()
                        if (detailedBolusInfo.insulin > 0 || detailedBolusInfo.carbs > 0) ScheduledDoseSupersession.bump()
                    } else {
                        if (detailedBolusInfo.insulin > 0) {""",
        "TreatmentDialog record bump",
    )
    t = must_replace(
        t,
        """                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    }
                                }
                            })
                        } else {
                            if (detailedBolusInfo.carbs > 0)
                                disposable += persistenceLayer.insertOrUpdateCarbs(
                                    detailedBolusInfo.createCarbs(),
                                    action = action,
                                    source = Sources.TreatmentDialog
                                ).subscribe()
                        }""",
        """                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    } else {
                                        ScheduledDoseSupersession.bump()
                                    }
                                }
                            })
                        } else {
                            if (detailedBolusInfo.carbs > 0) {
                                disposable += persistenceLayer.insertOrUpdateCarbs(
                                    detailedBolusInfo.createCarbs(),
                                    action = action,
                                    source = Sources.TreatmentDialog
                                ).subscribe()
                                ScheduledDoseSupersession.bump()
                            }
                        }""",
        "TreatmentDialog delivery bump",
    )
    write(staging, p, t)

    # TreatmentsBolusCarbsFragment
    p = "ui/src/main/kotlin/app/aaps/ui/activities/fragments/TreatmentsBolusCarbsFragment.kt"
    t = read(BASE, p)
    t = must_replace(
        t,
        "import app.aaps.core.interfaces.db.PersistenceLayer\n",
        "import app.aaps.core.interfaces.db.PersistenceLayer\nimport app.aaps.core.interfaces.pump.ScheduledDoseSupersession\n",
        "TreatmentsBolusCarbsFragment import",
    )
    t = must_replace(
        t,
        """            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selectedItems), Runnable {
                selectedItems.forEach { _, ml ->""",
        """            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selectedItems), Runnable {
                var removingBolusOrCarbs = false
                selectedItems.forEach { _, ml -> if (ml.bolus != null || ml.carbs != null) removingBolusOrCarbs = true }
                if (removingBolusOrCarbs) ScheduledDoseSupersession.bump()
                selectedItems.forEach { _, ml ->""",
        "TreatmentsBolusCarbsFragment bump",
    )
    write(staging, p, t)


def git_diff(old: Path, new: Path, relpath: str, is_new: bool) -> str:
    empty = None
    try:
        if is_new:
            empty = tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8", newline="\n")
            empty.write("")
            empty.close()
            old_arg = empty.name
        else:
            old_arg = str(old)
        r = subprocess.run(
            ["git", "diff", "--no-index", "--", old_arg, str(new)],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        text = r.stdout
        if not text.strip():
            if r.returncode not in (0, 1):
                raise SystemExit(f"git diff failed for {relpath}: {r.stderr}")
            return ""
        text = text.replace("\r\n", "\n")
        # Rewrite path headers to repo-relative a/ b/ form.
        lines = text.splitlines(keepends=True)
        out = []
        inserted_mode = False
        for line in lines:
            if line.startswith("diff --git "):
                out.append(f"diff --git a/{relpath} b/{relpath}\n")
                if is_new and not inserted_mode:
                    out.append("new file mode 100644\n")
                    inserted_mode = True
            elif line.startswith("index ") and is_new:
                out.append("index 0000000000..1111111111\n")
            elif line.startswith("--- "):
                out.append("--- /dev/null\n" if is_new else f"--- a/{relpath}\n")
            elif line.startswith("+++ "):
                out.append(f"+++ b/{relpath}\n")
            else:
                out.append(line)
        if not out[-1].endswith("\n"):
            out.append("\n")
        return "".join(out)
    finally:
        if empty is not None:
            os.unlink(empty.name)


def collect_relpaths(staging: Path) -> list[str]:
    files = []
    for p in staging.rglob("*"):
        if p.is_file():
            files.append(p.relative_to(staging).as_posix())
    return sorted(files)


def main() -> None:
    if not BASE.exists():
        raise SystemExit(f"base clone missing: {BASE}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        old = tmp_path / "old"
        new = tmp_path / "new"
        old.mkdir()
        new.mkdir()

        surgical = [
            "core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt",
            "core/keys/src/main/kotlin/app/aaps/core/keys/LongKey.kt",
            "core/objects/src/main/kotlin/app/aaps/core/objects/di/CoreModule.kt",
            "core/interfaces/src/main/kotlin/app/aaps/core/interfaces/pump/BolusProgressData.kt",
            "core/objects/src/main/kotlin/app/aaps/core/objects/wizard/QuickWizardEntry.kt",
            "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewPlugin.kt",
            "plugins/main/src/main/res/values/strings.xml",
            "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt",
            "plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/EditQuickWizardDialog.kt",
            "ui/src/main/res/layout/dialog_edit_quickwizard.xml",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/BolusProgressDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/InsulinDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/CarbsDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/dialogs/TreatmentDialog.kt",
            "ui/src/main/kotlin/app/aaps/ui/activities/fragments/TreatmentsBolusCarbsFragment.kt",
        ]

        for p in surgical:
            copy_base(old, p)
        apply_surgical(new)
        # apply_surgical wrote into `new`; also seed `new` from BASE then apply? It wrote from BASE text.
        # Full copies: old only if exists in BASE
        for p in FULL_COPY:
            src = BASE / rel(p)
            if src.exists():
                copy_base(old, p)
            copy_ours(new, p)

        # Strip unused IntKey import from BolusWizard in the patch payload
        bw = new / rel("core/objects/src/main/kotlin/app/aaps/core/objects/wizard/BolusWizard.kt")
        bw.write_text(
            bw.read_text(encoding="utf-8").replace("import app.aaps.core.keys.IntKey\n", ""),
            encoding="utf-8",
            newline="\n",
        )

        relpaths = sorted(set(collect_relpaths(old)) | set(collect_relpaths(new)))
        chunks = []
        for relpath in relpaths:
            old_f = old / Path(relpath)
            new_f = new / Path(relpath)
            is_new = not old_f.exists()
            if not new_f.exists():
                raise SystemExit(f"missing new file {relpath}")
            chunk = git_diff(old_f if old_f.exists() else new_f, new_f, relpath, is_new)
            if chunk:
                chunks.append(chunk)
        if not chunks:
            raise SystemExit("empty patch")
        header = (
            "From: bolus-calculator extract\n"
            "Subject: Bolus calculator: max bolus, checkboxes, initial size, subsequent dosing\n"
            "\n"
            "Apply on a fresh 3.4.2.6+aisf3.2.1 tree (AndroidAPS-3426 / tobias aisf 3.2.1 on AAPS 3.4.2.6):\n"
            "  git apply --check bolus-calculator-on-3426-aisf321.patch\n"
            "  git apply bolus-calculator-on-3426-aisf321.patch\n"
            "\n"
        )
        OUT.write_text("".join(chunks), encoding="utf-8", newline="\n")
        print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(chunks)} files)")
        for relpath in relpaths:
            print(" ", relpath)


if __name__ == "__main__":
    main()
