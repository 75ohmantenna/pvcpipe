package org.schabi.newpipe.player.ui;

import android.view.LayoutInflater;

import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class PlayerUiList {
    private final List<PlayerUi> playerUis = new ArrayList<>();

    /**
     * Creates a {@link PlayerUiList} starting with the provided player uis. The provided player uis
     * will not be prepared like those passed to {@link #addAndPrepare(PlayerUi)}, because when
     * the {@link PlayerUiList} constructor is called, the player is still not running and it
     * wouldn't make sense to initialize uis then. Instead the player will initialize them by doing
     * proper calls to {@link #call(Consumer)}.
     *
     * @param initialPlayerUis the player uis this list should start with; the order will be kept
     */
    public PlayerUiList(final PlayerUi... initialPlayerUis) {
        playerUis.addAll(List.of(initialPlayerUis));
    }

    /**
     * Selects the mode UI, retiring its previous owner before reusing a video binding.
     * Repeated selection keeps the current UI; notification and session UIs survive mode changes.
     * @param player the player whose readiness is replayed to a replacement UI
     * @param playerType the requested player mode
     */
    public void selectMode(final Player player, final PlayerType playerType) {
        if ((get(MainPlayerUi.class).isPresent() && playerType == PlayerType.MAIN)
                || (get(BackgroundPlayerUi.class).isPresent() && playerType == PlayerType.AUDIO)
                || (get(PopupPlayerUi.class).isPresent() && playerType == PlayerType.POPUP)) {
            // correct UI already in place
            return;
        }

        // try to reuse binding if possible
        final PlayerBinding binding = get(VideoPlayerUi.class).map(VideoPlayerUi::getBinding)
                .orElseGet(() -> {
                    if (playerType == PlayerType.AUDIO) {
                        return null;
                    } else {
                        return PlayerBinding.inflate(LayoutInflater.from(player.getContext()));
                    }
                });

        switch (playerType) {
            case MAIN:
                destroyAll(PopupPlayerUi.class);
                destroyAll(BackgroundPlayerUi.class);
                addAndPrepare(new MainPlayerUi(player, binding));
                break;
            case POPUP:
                destroyAll(MainPlayerUi.class);
                destroyAll(BackgroundPlayerUi.class);
                addAndPrepare(new PopupPlayerUi(player, binding));
                break;
            case AUDIO:
                destroyAll(VideoPlayerUi.class); // destroys both MainPlayerUi and PopupPlayerUi
                addAndPrepare(new BackgroundPlayerUi(player));
                break;
        }
    }

    /**
     * Adds the provided player ui to the list and calls on it the initialization functions that
     * apply based on the current player state. The preparation step needs to be done since when UIs
     * are removed and re-added, the player will not call e.g. initPlayer again since the exoplayer
     * is already initialized, but we need to notify the newly built UI that the player is ready
     * nonetheless.
     * @param playerUi the player ui to prepare and add to the list; its {@link
     *                 PlayerUi#getPlayer()} will be used to query information about the player
     *                 state
     */
    private void addAndPrepare(final PlayerUi playerUi) {
        if (playerUi.getPlayer().getFragmentListener().isPresent()) {
            // make sure UIs know whether a service is connected or not
            playerUi.onFragmentListenerSet();
        }

        if (!playerUi.getPlayer().exoPlayerIsNull()) {
            playerUi.initPlayer();
            if (playerUi.getPlayer().getPlayQueue() != null) {
                playerUi.initPlayback();
            }
        }

        playerUis.add(playerUi);
    }

    /**
     * Destroys all matching player UIs and removes them from the list.
     * @param playerUiType the class of the player UI to destroy; the {@link
     *                     Class#isInstance(Object)} method will be used, so even subclasses will be
     *                     destroyed and removed
     * @param <T>          the class type parameter
     */
    public <T> void destroyAll(final Class<T> playerUiType) {
        playerUis.stream()
                .filter(playerUiType::isInstance)
                .forEach(playerUi -> {
                    playerUi.destroyPlayer();
                    playerUi.destroy();
                });
        playerUis.removeIf(playerUiType::isInstance);
    }

    /**
     * Finds the first player UI assignable to the requested type.
     *
     * @param playerUiType the class of the player UI to return; the {@link
     *                     Class#isInstance(Object)} method will be used, so even subclasses could
     *                     be returned
     * @param <T>          the class type parameter
     * @return the first player UI of the required type found in the list, or an empty {@link
     *         Optional} otherwise
     */
    public <T> Optional<T> get(final Class<T> playerUiType) {
        return playerUis.stream()
                .filter(playerUiType::isInstance)
                .map(playerUiType::cast)
                .findFirst();
    }

    /**
     * Calls the provided consumer on all player UIs in the list, in order of addition.
     * @param consumer the consumer to call with player UIs
     */
    public void call(final Consumer<PlayerUi> consumer) {
        //noinspection SimplifyStreamApiCallChains
        playerUis.stream().forEachOrdered(consumer);
    }
}
