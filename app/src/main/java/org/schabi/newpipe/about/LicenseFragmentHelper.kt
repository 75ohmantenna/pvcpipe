package org.schabi.newpipe.about

import android.content.Context
import java.io.IOException
import org.jsoup.nodes.Entities
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper

/**
 * @param context the context to use
 * @param softwareComponent the component whose copyright and license are displayed
 * @return String which contains a HTML formatted license page
 * styled according to the context's theme
 */
fun getFormattedLicense(context: Context, softwareComponent: SoftwareComponent): String {
    val license = softwareComponent.license
    try {
        val licenseHtml = context.assets.open(license.filename).bufferedReader().use { it.readText() }
        val notice = softwareComponent.noticeFilename?.let { filename ->
            context.assets.open(filename).bufferedReader().use { it.readText() }
        }
        return formatLicenseHtml(licenseHtml, softwareComponent, notice)
            // split the HTML file and insert the stylesheet into the HEAD of the file
            .replace("</head>", "<style>${getLicenseStylesheet(context)}</style></head>")
    } catch (e: IOException) {
        throw IllegalArgumentException("Could not get license file: ${license.filename}", e)
    }
}

/** Keep the actual component credit with the license, including in MIT and BSD dialogs. */
internal fun formatLicenseHtml(
    licenseHtml: String,
    softwareComponent: SoftwareComponent,
    notice: String? = null
): String {
    val copyright = listOf(softwareComponent.years, softwareComponent.copyrightOwner)
        .filter { it.isNotBlank() }.joinToString(" ")
    val credit = "<h2>${Entities.escape(softwareComponent.name)}</h2>" +
        "<p>Copyright © ${Entities.escape(copyright)}</p>"
    val noticeHtml = notice?.let { "<pre>${Entities.escape(it)}</pre>" }.orEmpty()
    return licenseHtml.replaceFirst("<body>", "<body>$credit$noticeHtml")
}

/**
 * @param context the Android context
 * @return String which is a CSS stylesheet according to the context's theme
 */
fun getLicenseStylesheet(context: Context): String {
    val isLightTheme = ThemeHelper.isLightThemeSelected(context)
    val licenseBackgroundColor = getHexRGBColor(
        context,
        if (isLightTheme) R.color.light_license_background_color else R.color.dark_license_background_color
    )
    val licenseTextColor = getHexRGBColor(
        context,
        if (isLightTheme) R.color.light_license_text_color else R.color.dark_license_text_color
    )
    val youtubePrimaryColor = getHexRGBColor(
        context,
        if (isLightTheme) R.color.light_youtube_primary_color else R.color.dark_youtube_primary_color
    )
    return "body{padding:12px 15px;margin:0;background:#$licenseBackgroundColor;color:#$licenseTextColor}" +
        "a[href]{color:#$youtubePrimaryColor}pre{white-space:pre-wrap}"
}

/**
 * Cast R.color to a hexadecimal color value.
 *
 * @param context the context to use
 * @param color   the color number from R.color
 * @return a six characters long String with hexadecimal RGB values
 */
fun getHexRGBColor(context: Context, color: Int): String {
    return context.getString(color).substring(3)
}
