package com.pancakeworks.fridgegrub.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opens this app's Play Store listing, for the "Rate this app" entry on [AboutScreen].
 *
 * Tries `market://` first -- the canonical URI the Play Store app registers -- and falls back to
 * the `https://` listing URL, which degrades to a browser on a device with no Play Store. The
 * https-only approach isn't enough on its own: it reaches the Play Store app only when that app's
 * verified App Link for `play.google.com` is intact, which isn't this app's to guarantee.
 *
 * Deliberately does NOT pre-check with `intent.resolveActivity(packageManager)`. That call IS
 * filtered by Android 11+ package visibility and can return `null` even on a device that does have
 * the Play Store, which would make the `market://` branch unwinnable and silently take the fallback
 * every time. `startActivity` itself is not visibility-filtered, so catching the failure is both
 * sufficient and correct -- and it is why this needs no `<queries>` entry in the manifest, unlike
 * the TTS_SERVICE one, which genuinely does.
 */
internal fun openPlayStoreListing(context: Context, packageName: String = context.packageName) {
    // A non-Activity context (e.g. an application context) needs its own task; from a Composable's
    // LocalContext this is normally an Activity already, so only add the flag when we must.
    val needsNewTask = context !is Activity

    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
    )
    if (needsNewTask) {
        marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // Short-circuited so the web intent is only attempted if the market one threw.
    val opened = runCatching { context.startActivity(marketIntent) }.isSuccess ||
        runCatching { context.startActivity(webIntent) }.isSuccess

    // Both routes dead means nothing visible happened at all, which reads as "the button is
    // broken" rather than "this device has no store and no browser" -- and the second is the actual
    // explanation in that case, so say something. Failure to *dispatch* gets here; a store that
    // opens and then can't serve the listing does not, and can't be detected from here.
    if (!opened) {
        Toast.makeText(context, "Couldn't open the Play Store", Toast.LENGTH_SHORT).show()
    }
}
