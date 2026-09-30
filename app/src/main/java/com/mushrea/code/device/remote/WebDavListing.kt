package com.mushrea.code.device.remote

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader

/** One entry of a WebDAV PROPFIND multistatus response. */
data class WebDavEntry(
    val href: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val lastModified: String?,
)

/**
 * Parses the 207 multistatus XML a WebDAV server answers a PROPFIND with, purely: one pass,
 * namespace-blind (the DAV namespace prefix varies between servers), so any parser-compliant
 * server works.
 */
object WebDavListing {
    fun parse(reader: Reader): List<WebDavEntry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(reader)
        val entries = ArrayList<WebDavEntry>()
        var href: String? = null
        var isFolder = false
        var size = 0L
        var modified: String? = null
        var inProp = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name.lowercase()
                    when {
                        local == "response" -> {
                            href = null
                            isFolder = false
                            size = 0L
                            modified = null
                        }
                        local == "propstat" || local == "prop" -> inProp = true
                        local == "href" -> href = parser.nextText()
                        inProp && local == "collection" -> isFolder = true
                        inProp && local == "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                        inProp && local == "getlastmodified" -> modified = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val local = parser.name.lowercase()
                    when {
                        local == "propstat" || local == "prop" -> inProp = false
                        local == "response" -> {
                            href?.let { entries.add(WebDavEntry(it, isFolder, size, modified)) }
                        }
                    }
                }
            }
            parser.next()
        }
        return entries
    }

    /** The display name from an href: its last non-empty segment, URL-decoded. */
    fun displayName(href: String): String =
        href
            .trimEnd('/')
            .substringAfterLast('/')
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
            .ifBlank { "/" }
}
