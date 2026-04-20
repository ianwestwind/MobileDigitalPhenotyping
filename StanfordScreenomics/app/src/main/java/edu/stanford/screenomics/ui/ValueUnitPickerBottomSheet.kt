package edu.stanford.screenomics.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.NumberPicker
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import edu.stanford.screenomics.R

/**
 * Small bottom sheet: [NumberPicker] for 1–60, [NumberPicker] for a two-label unit axis, **Update** to commit.
 */
object ValueUnitPickerBottomSheet {

    fun show(
        context: Context,
        title: CharSequence,
        unitLabels: Array<String>,
        initialValue: Int,
        initialUnitIndex: Int,
        onCommit: (value: Int, unitIndex: Int) -> Unit,
    ) {
        require(unitLabels.size >= 2) { "unitLabels must have at least two entries" }
        val dialog = BottomSheetDialog(context)
        val root = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_value_unit_pickers, null)
        val titleView = root.findViewById<TextView>(R.id.picker_sheet_title)
        val valuePicker = root.findViewById<NumberPicker>(R.id.picker_sheet_value)
        val unitPicker = root.findViewById<NumberPicker>(R.id.picker_sheet_unit)
        val cancel = root.findViewById<MaterialButton>(R.id.picker_sheet_cancel)
        val update = root.findViewById<MaterialButton>(R.id.picker_sheet_update)

        titleView.text = title

        valuePicker.minValue = 1
        valuePicker.maxValue = 60
        valuePicker.wrapSelectorWheel = false

        unitPicker.minValue = 0
        unitPicker.maxValue = unitLabels.size - 1
        unitPicker.displayedValues = unitLabels.copyOf()
        unitPicker.wrapSelectorWheel = false

        valuePicker.value = initialValue.coerceIn(1, 60)
        unitPicker.value = initialUnitIndex.coerceIn(0, unitLabels.size - 1)

        cancel.setOnClickListener { dialog.dismiss() }
        update.setOnClickListener {
            onCommit(valuePicker.value, unitPicker.value)
            dialog.dismiss()
        }

        dialog.setContentView(root)
        dialog.show()
    }
}
