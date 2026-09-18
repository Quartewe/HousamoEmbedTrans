package com.quarty.housamoembedtrans.ui;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Material dialog builder with the prototype confirmation surface applied at
 * the dialog's content boundary.  All normal builder methods and listeners
 * remain owned by MaterialAlertDialogBuilder.
 */
public final class UiMaterialAlertDialogBuilder
    extends MaterialAlertDialogBuilder {

    public UiMaterialAlertDialogBuilder(Context context) {
        super(context);
    }

    public UiMaterialAlertDialogBuilder(Context context, int theme) {
        super(context, theme);
    }

    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        UiDialogPresentation.prepare(dialog);
        return dialog;
    }
}
