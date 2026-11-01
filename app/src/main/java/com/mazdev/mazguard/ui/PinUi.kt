package com.mazdev.mazguard.ui

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.PinManager

object PinUi {

    fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    fun pinField(activity: Activity): EditText = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = activity.getString(R.string.pin_hint)
        isSingleLine = true
        setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 14))
    }

    /** Asks for a new PIN, stores it, then runs [onDone]. */
    fun askNewPin(activity: Activity, onDone: () -> Unit) {
        val input = pinField(activity)
        val container = FrameLayout(activity).apply {
            setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), 0)
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pin_set_title)
            .setMessage(R.string.pin_set_message)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (!PinManager.isValidFormat(pin)) {
                    input.error = activity.getString(R.string.pin_invalid)
                    return@setOnClickListener
                }
                PinManager.setPin(AppPrefs.get(activity), pin)
                dialog.dismiss()
                onDone()
            }
        }
        dialog.show()
    }

    fun errorText(prefs: AppPrefs): String? = PinManager.lockMessage(prefs)
}
