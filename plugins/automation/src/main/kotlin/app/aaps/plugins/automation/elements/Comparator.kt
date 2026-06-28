package app.aaps.plugins.automation.elements

import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.annotation.StringRes
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.automation.R

class Comparator(private val rh: ResourceHelper) : Element {

    enum class Compare {
        IS_LESSER,
        IS_EQUAL_OR_LESSER,
        IS_EQUAL,
        IS_EQUAL_OR_GREATER,
        IS_GREATER,
        IS_NOT_EQUAL,
        IS_NOT_AVAILABLE;

        @get:StringRes val stringRes: Int
            get() = when (this) {
                IS_LESSER           -> R.string.islesser
                IS_EQUAL_OR_LESSER  -> R.string.isequalorlesser
                IS_EQUAL            -> R.string.isequal
                IS_EQUAL_OR_GREATER -> R.string.isequalorgreater
                IS_GREATER          -> R.string.isgreater
                IS_NOT_EQUAL        -> R.string.isnotequal
                IS_NOT_AVAILABLE    -> R.string.isnotavailable
            }

        val shortSymbol: String
            get() = when (this) {
                IS_LESSER           -> "<"
                IS_EQUAL_OR_LESSER  -> "<="
                IS_EQUAL            -> "="
                IS_EQUAL_OR_GREATER -> ">="
                IS_GREATER          -> ">"
                IS_NOT_EQUAL        -> "!="
                IS_NOT_AVAILABLE    -> "n/a"
            }

        fun <T : Comparable<T>> check(obj1: T, obj2: T): Boolean {
            val comparison = obj1.compareTo(obj2)
            return when (this) {
                IS_LESSER           -> comparison < 0
                IS_EQUAL_OR_LESSER  -> comparison <= 0
                IS_EQUAL            -> comparison == 0
                IS_EQUAL_OR_GREATER -> comparison >= 0
                IS_GREATER          -> comparison > 0
                IS_NOT_EQUAL        -> comparison != 0
                else                -> false
            }
        }

        // Double overload with tolerance (half the input step) to absorb unit-conversion drift.
        // Only active when fuzzyEquals is enabled in settings.
        fun check(obj1: Double, obj2: Double, tolerance: Double): Boolean {
            val tol = if (fuzzyEquals) tolerance else 0.0
            return when (this) {
                IS_LESSER           -> obj1 < obj2 - tol
                IS_EQUAL_OR_LESSER  -> obj1 <= obj2 + tol
                IS_EQUAL            -> kotlin.math.abs(obj1 - obj2) <= tol
                IS_EQUAL_OR_GREATER -> obj1 >= obj2 - tol
                IS_GREATER          -> obj1 > obj2 + tol
                IS_NOT_EQUAL        -> kotlin.math.abs(obj1 - obj2) > tol
                else                -> false
            }
        }

        companion object {
            var fuzzyEquals = false

            fun labels(rh: ResourceHelper): List<String> {
                val list: MutableList<String> = ArrayList()
                for (c in Compare.entries) {
                    list.add(rh.gs(c.stringRes))
                }
                return list
            }
        }
    }

    constructor(rh: ResourceHelper, value: Compare) : this(rh) {
        this.value = value
    }

    var value = Compare.IS_EQUAL

    override fun addToLayout(root: LinearLayout) {
        root.addView(
            Spinner(root.context).apply {
                adapter = ArrayAdapter(root.context, app.aaps.core.ui.R.layout.spinner_centered, Compare.labels(rh)).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, rh.dpToPx(1), 0, rh.dpToPx(1))
                }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        value = Compare.entries.toTypedArray()[position]
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                setSelection(value.ordinal)
                gravity = Gravity.CENTER_HORIZONTAL
            })
    }

    fun setValue(compare: Compare): Comparator {
        value = compare
        return this
    }
}