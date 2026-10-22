package org.schabi.newpipe.pvc.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.schabi.newpipe.R;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

/**
 * Host another Fragment that might be replaced dynamically on runtime.
 */
public class PvcHostFragment extends Fragment {
    private Fragment fragment;

    @Override
    public View onCreateView(
            @NonNull final LayoutInflater inflater,
            final ViewGroup container,
            final Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        final View view = inflater.inflate(R.layout.pvc_host_fragment, container, false);
        if (fragment != null) {
            replaceFragment(fragment, false);
        }
        return view;
    }

    public void replaceFragment(
            final Fragment frag,
            final boolean addToBackstack) {
        fragment = frag;
        if (addToBackstack) {
            getChildFragmentManager().beginTransaction().replace(R.id.hosted_fragment, frag)
                    .addToBackStack(null).commit();
        } else {
            getChildFragmentManager().beginTransaction().replace(R.id.hosted_fragment, frag)
                    .commit();
        }
    }

    /**
     * Returns the currently hosted fragment, including while its transaction is pending.
     *
     * @return the hosted fragment, or {@code null} when none has been supplied or restored
     */
    @Nullable
    public Fragment getHostedFragment() {
        if (!isAdded()) {
            return fragment;
        }
        final Fragment restored = getChildFragmentManager()
                .findFragmentById(R.id.hosted_fragment);
        return restored == null ? fragment : restored;
    }

    public static PvcHostFragment newInstance(
            final Fragment fragment) {
        final PvcHostFragment pvcHostFragment = new PvcHostFragment();
        pvcHostFragment.fragment = fragment;
        return pvcHostFragment;
    }

    public static boolean handleBackPressed(
            final FragmentManager fm) {
        if (fm.getFragments() != null) {
            for (final Fragment frag : fm.getFragments()) {
                if (frag != null && frag.isVisible() && frag instanceof PvcHostFragment) {
                    if (((PvcHostFragment) frag).onBackPressed()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Finds the nearest host among the fragment's ancestors.
     *
     * @param fragment a fragment that may be shown inside a host
     * @return the nearest host above the fragment, or {@code null} if it is not inside one
     */
    @Nullable
    public static PvcHostFragment findHostOf(@NonNull final Fragment fragment) {
        Fragment parent = fragment.getParentFragment();
        while (parent != null && !(parent instanceof PvcHostFragment)) {
            parent = parent.getParentFragment();
        }
        return (PvcHostFragment) parent;
    }

    /**
     * Leaves the replies shown in this host, as its in-page back button asks.
     *
     * @return whether something was popped
     */
    public boolean handleRepliesBackPressed() {
        return onBackPressed();
    }

    protected boolean onBackPressed() {
        final FragmentManager fm = getChildFragmentManager();
        if (handleBackPressed(fm))  {
            return true;
        } else if (getUserVisibleHint() && fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
            return true;
        }
        return false;
    }
}
