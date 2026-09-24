package dr.ukccrags

import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView

/**
 * Makes the web addresses in UKC's prose tappable.
 *
 * The import writes each link out as its address (see `link()` in
 * extract.js), so plain text is all there is to work with here. Crags read
 * before that still carry bare addresses UKC typed out in full, which this
 * catches too; the ones that were a word like "here" need the crag refreshing.
 */
fun TextView.showLinks() {
    Linkify.addLinks(this, Linkify.WEB_URLS)
    movementMethod = LinkMovementMethod.getInstance()
}
