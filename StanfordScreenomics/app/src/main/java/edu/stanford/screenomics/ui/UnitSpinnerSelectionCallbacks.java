package edu.stanford.screenomics.ui;

import android.view.View;
import android.widget.AdapterView;

/**
 * Java SAM wrapper: Kotlin's Android compile classpath can expose {@link AdapterView.OnItemSelectedListener}
 * without a Kotlin-overridable {@code onNothingSelected}, which breaks anonymous object compilation.
 */
public final class UnitSpinnerSelectionCallbacks {

    public interface OnUnitSelectionChanged {
        void onUnitSelectionChanged();
    }

    public static AdapterView.OnItemSelectedListener onItemSelected(OnUnitSelectionChanged callback) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                callback.onUnitSelectionChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        };
    }

    /** Kotlin-friendly: avoids SAM / default-method quirks on some Android compile classpaths. */
    public static AdapterView.OnItemSelectedListener onRunnable(final Runnable callback) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                callback.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        };
    }

    private UnitSpinnerSelectionCallbacks() {}
}
