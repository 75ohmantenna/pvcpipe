package org.schabi.newpipe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorPanelHelper;

public class BlankFragment extends BaseFragment {
    private static final String STATE_SUFFIX =
            "org.schabi.newpipe.fragments.BlankFragment$$StateSaver";

    @Nullable
    ErrorInfo errorInfo;
    @Nullable
    ErrorPanelHelper errorPanel = null;

    @Override
    protected void saveFragmentState(@NonNull final Bundle state) {
        super.saveFragmentState(state);
        state.putParcelable("errorInfo" + STATE_SUFFIX, errorInfo);
    }

    @Override
    protected void restoreFragmentState(@NonNull final Bundle state) {
        super.restoreFragmentState(state);
        state.setClassLoader(getClass().getClassLoader());
        if (state.containsKey("errorInfo" + STATE_SUFFIX)) {
            errorInfo = state.getParcelable("errorInfo" + STATE_SUFFIX);
        }
    }

    /**
     * Builds a blank fragment that just says the app name and suggests clicking on search.
     */
    public BlankFragment() {
        this(null);
    }

    /**
     * @param errorInfo if null acts like {@link BlankFragment}, else shows an error panel.
     */
    public BlankFragment(@Nullable final ErrorInfo errorInfo) {
        this.errorInfo = errorInfo;
    }

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container,
                             final Bundle savedInstanceState) {
        setTitle(getString(R.string.app_name));
        final View view = inflater.inflate(R.layout.fragment_blank, container, false);
        if (errorInfo != null) {
            errorPanel = new ErrorPanelHelper(this, view, null);
            errorPanel.showError(errorInfo);
            view.findViewById(R.id.blank_page_content).setVisibility(View.GONE);
        }
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (errorPanel != null) {
            errorPanel.dispose();
            errorPanel = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(getString(R.string.app_name));
    }
}
