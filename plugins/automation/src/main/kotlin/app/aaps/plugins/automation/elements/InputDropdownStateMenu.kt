package app.aaps.plugins.automation.elements

import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import app.aaps.core.interfaces.resources.ResourceHelper

class InputDropdownStateMenu(
    private val rh: ResourceHelper,
    val onValueSelected: ((String) -> Unit)? = null
) {
    var value: String = ""
    var values: List<String> = listOf()
    private var spinner: Spinner? = null

    fun addToLayout(root: LinearLayout) {
        spinner = Spinner(root.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, rh.dpToPx(4), 0, rh.dpToPx(4)) }

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position >= 0 && position < values.size) {
                        val selectedValue = values[position]
                        if (value != selectedValue) { value = selectedValue; onValueSelected?.invoke(value) }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }
        updateAdapter()
        root.addView(spinner)
    }

    fun updateAdapter() {
        if (values.isEmpty() || spinner?.context == null) return
        val adapter = ArrayAdapter(spinner?.context ?: return, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner?.adapter = adapter
        val position = values.indexOf(value)
        if (position >= 0) spinner?.setSelection(position)
        else if (values.isNotEmpty()) { value = values[0]; spinner?.setSelection(0); onValueSelected?.invoke(value) }
    }
}
