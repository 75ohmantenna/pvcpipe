package org.schabi.newpipe.util

import android.content.Context
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds
import org.schabi.newpipe.util.ServiceHelper.getTranslated

/**
 * this class implements all the string mapping between the [org.schabi.newpipe.extractor.search.filter.FilterItem.nameId].
 *
 * Upstream NewPipe does not use [org.schabi.newpipe.extractor.search.filter.FilterItem]
 * as it is part of PVCPipe's searchfilters sort and content filters framework used in
 * PVCPipeExtractor
 */
abstract class PvcServiceHelper {

    /**
     * Icons for PVCPipe only services.
     */
    protected fun pvcGetIcon(serviceId: Int, default: Int): Int {
        return when (serviceId) {
            5 -> R.drawable.ic_placeholder_bitchute
            6 -> R.drawable.ic_placeholder_rumble
            7 -> R.drawable.ic_placeholder_odysee
            8 -> R.drawable.ic_placeholder_kick
            else -> default
        }
    }

    companion object {
        fun getTranslatedFilterString(
            stringId: LibraryStringIds,
            context: Context
        ): String = stringId.getTranslated(context)
    }

    /**
     * handle all PVCPipeExtractor [LibraryStringIds] mapping to translated string.
     *
     *  All [LibraryStringIds] for CHANNEL_TAB_* are not handled here.
     *   - This is an upstream separation. I do not know why they do it like
     *     that and do not want to touch it.
     *   - See [ChannelTabHelper.getShowTabKey]
     *   - We default here to the [Enum.name] itself as it is not used in any way
     */
    fun LibraryStringIds.getTranslated(context: Context): String = when (this) {
        LibraryStringIds.SEARCH_FILTERS_10_30_MIN -> context.getString(R.string.search_filters_10_30_min)
        LibraryStringIds.SEARCH_FILTERS_2_10_MIN -> context.getString(R.string.search_filters_2_10_min)
        LibraryStringIds.SEARCH_FILTERS_360 -> context.getString(R.string.search_filters_360)
        LibraryStringIds.SEARCH_FILTERS_3D -> context.getString(R.string.search_filters_3d)
        LibraryStringIds.SEARCH_FILTERS_4_20_MIN -> context.getString(R.string.search_filters_4_20_min)
        LibraryStringIds.SEARCH_FILTERS_4K -> context.getString(R.string.search_filters_4k)
        LibraryStringIds.SEARCH_FILTERS_ADDED -> context.getString(R.string.search_filters_added)
        LibraryStringIds.SEARCH_FILTERS_ALBUMS -> context.getString(R.string.albums)
        LibraryStringIds.SEARCH_FILTERS_ANY_TIME -> context.getString(R.string.search_filters_any_time)
        LibraryStringIds.SEARCH_FILTERS_ALL -> context.getString(R.string.all)
        LibraryStringIds.SEARCH_FILTERS_CCOMMONS -> context.getString(R.string.search_filters_ccommons)
        LibraryStringIds.SEARCH_FILTERS_CHANNELS -> context.getString(R.string.channels)
        LibraryStringIds.SEARCH_FILTERS_DATE -> context.getString(R.string.search_filters_date)
        LibraryStringIds.SEARCH_FILTERS_DURATION -> context.getString(R.string.search_filters_duration)
        LibraryStringIds.SEARCH_FILTERS_FEATURES -> context.getString(R.string.search_filters_features)
        LibraryStringIds.SEARCH_FILTERS_GREATER_30_MIN -> context.getString(R.string.search_filters_greater_30_min)
        LibraryStringIds.SEARCH_FILTERS_HD -> context.getString(R.string.search_filters_hd)
        LibraryStringIds.SEARCH_FILTERS_HDR -> context.getString(R.string.search_filters_hdr)
        LibraryStringIds.SEARCH_FILTERS_LAST_HOUR -> context.getString(R.string.search_filters_last_hour)
        LibraryStringIds.SEARCH_FILTERS_LAST_YEAR -> context.getString(R.string.search_filters_last_year)
        LibraryStringIds.SEARCH_FILTERS_LENGTH -> context.getString(R.string.search_filters_length)
        LibraryStringIds.SEARCH_FILTERS_LESS_2_MIN -> context.getString(R.string.search_filters_less_2_min)
        LibraryStringIds.SEARCH_FILTERS_LICENSE -> context.getString(R.string.search_filters_license)
        LibraryStringIds.SEARCH_FILTERS_LIVE -> context.getString(R.string.duration_live)
        LibraryStringIds.SEARCH_FILTERS_LOCATION -> context.getString(R.string.search_filters_location)
        LibraryStringIds.SEARCH_FILTERS_THIS_MONTH -> context.getString(R.string.search_filters_this_month)
        LibraryStringIds.SEARCH_FILTERS_ARTISTS -> context.getString(R.string.artists)
        LibraryStringIds.SEARCH_FILTERS_SONGS -> context.getString(R.string.songs)
        LibraryStringIds.SEARCH_FILTERS_OVER_20_MIN -> context.getString(R.string.search_filters_over_20_min)
        LibraryStringIds.SEARCH_FILTERS_PAST_DAY -> context.getString(R.string.search_filters_past_day)
        LibraryStringIds.SEARCH_FILTERS_PAST_HOUR -> context.getString(R.string.search_filters_past_hour)
        LibraryStringIds.SEARCH_FILTERS_PAST_MONTH -> context.getString(R.string.search_filters_past_month)
        LibraryStringIds.SEARCH_FILTERS_PAST_WEEK -> context.getString(R.string.search_filters_past_week)
        LibraryStringIds.SEARCH_FILTERS_PAST_YEAR -> context.getString(R.string.search_filters_past_year)
        LibraryStringIds.SEARCH_FILTERS_PLAYLISTS -> context.getString(R.string.playlists)
        LibraryStringIds.SEARCH_FILTERS_PURCHASED -> context.getString(R.string.search_filters_published)
        LibraryStringIds.SEARCH_FILTERS_RATING -> context.getString(R.string.search_filters_rating)
        LibraryStringIds.SEARCH_FILTERS_RELEVANCE -> context.getString(R.string.search_filters_relevance)
        LibraryStringIds.SEARCH_FILTERS_SORT_BY -> context.getString(R.string.search_filters_sort_by)
        LibraryStringIds.SEARCH_FILTERS_SUBTITLES -> context.getString(R.string.search_filters_subtitles)
        LibraryStringIds.SEARCH_FILTERS_TO_MODIFY_COMMERCIALLY -> context.getString(R.string.search_filters_to_modify_commercially)
        LibraryStringIds.SEARCH_FILTERS_TODAY -> context.getString(R.string.search_filters_today)
        LibraryStringIds.SEARCH_FILTERS_TRACKS -> context.getString(R.string.tracks)
        LibraryStringIds.SEARCH_FILTERS_UNDER_4_MIN -> context.getString(R.string.search_filters_under_4_min)
        LibraryStringIds.SEARCH_FILTERS_UPLOAD_DATE -> context.getString(R.string.search_filters_upload_date)
        LibraryStringIds.SEARCH_FILTERS_USERS -> context.getString(R.string.users)
        LibraryStringIds.SEARCH_FILTERS_VIDEOS -> context.getString(R.string.videos_string)
        LibraryStringIds.SEARCH_FILTERS_VIEWS -> context.getString(R.string.search_filters_views)
        LibraryStringIds.SEARCH_FILTERS_VR180 -> context.getString(R.string.search_filters_vr180)
        LibraryStringIds.SEARCH_FILTERS_THIS_WEEK -> context.getString(R.string.search_filters_this_week)
        LibraryStringIds.SEARCH_FILTERS_THIS_YEAR -> context.getString(R.string.search_filters_this_year)
        LibraryStringIds.SEARCH_FILTERS_YOUTUBE_MUSIC -> context.getString(R.string.search_filters_youtube_music)
        LibraryStringIds.SEARCH_FILTERS_SHORT -> context.getString(R.string.search_filters_short)
        LibraryStringIds.SEARCH_FILTERS_LONG -> context.getString(R.string.search_filters_long)
        LibraryStringIds.SEARCH_FILTERS_RUMBLES -> context.getString(R.string.search_filters_rumbles)
        LibraryStringIds.SEARCH_FILTERS_MOST_RECENT -> context.getString(R.string.search_filters_most_recent)
        LibraryStringIds.SEARCH_FILTERS_SHORT_0_5M -> context.getString(R.string.search_filters_short_0_5_min)
        LibraryStringIds.SEARCH_FILTERS_MEDIUM_5_20M -> context.getString(R.string.search_filters_medium_5_20_min)
        LibraryStringIds.SEARCH_FILTERS_LONG_20M_PLUS -> context.getString(R.string.search_filters_long_20_min_plus)
        LibraryStringIds.SEARCH_FILTERS_FEATURE_45M_PLUS -> context.getString(R.string.search_filters_feature_45_min_plus)
        LibraryStringIds.SEARCH_FILTERS_NEWEST_FIRST -> context.getString(R.string.search_filters_newest_first)
        LibraryStringIds.SEARCH_FILTERS_OLDEST_FIRST -> context.getString(R.string.search_filters_oldest_first)
        LibraryStringIds.SEARCH_FILTERS_SENSITIVITY_SAFE -> context.getString(R.string.search_filters_sensitivity_safe)
        LibraryStringIds.SEARCH_FILTERS_SENSITIVITY_NORMAL -> context.getString(R.string.search_filters_sensitivity_normal)
        LibraryStringIds.SEARCH_FILTERS_SENSITIVITY_NSFW -> context.getString(R.string.search_filters_sensitivity_nsfw)
        LibraryStringIds.SEARCH_FILTERS_SENSITIVITY_NSFL -> context.getString(R.string.search_filters_sensitivity_nsfl)
        LibraryStringIds.SEARCH_FILTERS_SENSITIVITY -> context.getString(R.string.search_filters_sensitivity)
        LibraryStringIds.CHANNEL_TAB_VIDEOS -> LibraryStringIds.CHANNEL_TAB_VIDEOS.name
        LibraryStringIds.CHANNEL_TAB_TRACKS -> LibraryStringIds.CHANNEL_TAB_TRACKS.name
        LibraryStringIds.CHANNEL_TAB_SHORTS -> LibraryStringIds.CHANNEL_TAB_SHORTS.name
        LibraryStringIds.CHANNEL_TAB_LIVESTREAMS -> LibraryStringIds.CHANNEL_TAB_LIVESTREAMS.name
        LibraryStringIds.CHANNEL_TAB_CHANNELS -> LibraryStringIds.CHANNEL_TAB_CHANNELS.name
        LibraryStringIds.CHANNEL_TAB_PLAYLISTS -> LibraryStringIds.CHANNEL_TAB_PLAYLISTS.name
        LibraryStringIds.CHANNEL_TAB_ALBUMS -> LibraryStringIds.CHANNEL_TAB_ALBUMS.name
        LibraryStringIds.CHANNEL_TAB_LIKES -> LibraryStringIds.CHANNEL_TAB_LIKES.name
        LibraryStringIds.CHANNEL_TAB_PODCASTS -> LibraryStringIds.CHANNEL_TAB_PODCASTS.name
    }
}
