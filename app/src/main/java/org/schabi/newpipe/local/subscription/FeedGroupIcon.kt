package org.schabi.newpipe.local.subscription

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.schabi.newpipe.R

enum class FeedGroupIcon(
    /**
     * The id that will be used to store and retrieve icons from some persistent storage (e.g. DB).
     */
    val id: Int,

    /**
     * The drawable resource.
     */
    @DrawableRes val drawableResource: Int,

    /**
     * The name screen readers announce for this icon.
     */
    @StringRes val nameResource: Int
) {
    ALL(0, R.drawable.ic_asterisk, R.string.feed_group_icon_all),
    MUSIC(1, R.drawable.ic_music_note, R.string.feed_group_icon_music),
    EDUCATION(2, R.drawable.ic_school, R.string.feed_group_icon_education),
    FITNESS(3, R.drawable.ic_fitness_center, R.string.feed_group_icon_fitness),
    SPACE(4, R.drawable.ic_telescope, R.string.feed_group_icon_space),
    COMPUTER(5, R.drawable.ic_computer, R.string.feed_group_icon_computer),
    GAMING(6, R.drawable.ic_videogame_asset, R.string.feed_group_icon_gaming),
    SPORTS(7, R.drawable.ic_directions_bike, R.string.feed_group_icon_sports),
    NEWS(8, R.drawable.ic_campaign, R.string.feed_group_icon_news),
    FAVORITES(9, R.drawable.ic_favorite, R.string.feed_group_icon_favorites),
    CAR(10, R.drawable.ic_directions_car, R.string.feed_group_icon_car),
    MOTORCYCLE(11, R.drawable.ic_motorcycle, R.string.feed_group_icon_motorcycle),
    TREND(12, R.drawable.ic_trending_up, R.string.feed_group_icon_trend),
    MOVIE(13, R.drawable.ic_movie, R.string.feed_group_icon_movie),
    BACKUP(14, R.drawable.ic_backup, R.string.feed_group_icon_backup),
    ART(15, R.drawable.ic_palette, R.string.feed_group_icon_art),
    PERSON(16, R.drawable.ic_person, R.string.feed_group_icon_person),
    PEOPLE(17, R.drawable.ic_people, R.string.feed_group_icon_people),
    MONEY(18, R.drawable.ic_attach_money, R.string.feed_group_icon_money),
    KIDS(19, R.drawable.ic_child_care, R.string.feed_group_icon_kids),
    FOOD(20, R.drawable.ic_fastfood, R.string.feed_group_icon_food),
    SMILE(21, R.drawable.ic_insert_emoticon, R.string.feed_group_icon_smile),
    EXPLORE(22, R.drawable.ic_explore, R.string.feed_group_icon_explore),
    RESTAURANT(23, R.drawable.ic_restaurant, R.string.feed_group_icon_restaurant),
    MIC(24, R.drawable.ic_mic, R.string.feed_group_icon_mic),
    HEADSET(25, R.drawable.ic_headset, R.string.feed_group_icon_headset),
    RADIO(26, R.drawable.ic_radio, R.string.feed_group_icon_radio),
    SHOPPING_CART(27, R.drawable.ic_shopping_cart, R.string.feed_group_icon_shopping_cart),
    WATCH_LATER(28, R.drawable.ic_watch_later, R.string.feed_group_icon_watch_later),
    WORK(29, R.drawable.ic_work, R.string.feed_group_icon_work),
    HOT(30, R.drawable.ic_whatshot, R.string.feed_group_icon_hot),
    CHANNEL(31, R.drawable.ic_tv, R.string.feed_group_icon_channel),
    BOOKMARK(32, R.drawable.ic_bookmark, R.string.feed_group_icon_bookmark),
    PETS(33, R.drawable.ic_pets, R.string.feed_group_icon_pets),
    WORLD(34, R.drawable.ic_public, R.string.feed_group_icon_world),
    STAR(35, R.drawable.ic_stars, R.string.feed_group_icon_star),
    SUN(36, R.drawable.ic_wb_sunny, R.string.feed_group_icon_sun),
    RSS(37, R.drawable.ic_rss_feed, R.string.feed_group_icon_rss),
    WHATS_NEW(38, R.drawable.ic_subscriptions, R.string.feed_group_icon_whats_new);

    @DrawableRes
    fun getDrawableRes(): Int {
        return drawableResource
    }
}
