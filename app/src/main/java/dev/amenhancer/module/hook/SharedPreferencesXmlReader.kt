package dev.amenhancer.module.hook

import android.content.SharedPreferences
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/**
 * Fallback reader for the module's SharedPreferences XML when
 * createPackageContext is unavailable (e.g. strict package-visibility ROMs).
 * Parses the standard Android SharedPreferences XML format into an in-memory
 * SharedPreferences instance.
 */
internal object SharedPreferencesXmlReader {
    fun read(file: File): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        if (file.exists()) {
            runCatching {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                file.inputStream().use { parser.setInput(it, "UTF-8") }
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "boolean" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val value = parser.getAttributeValue(null, "value") == "true"
                                if (name != null) values[name] = value
                            }
                            "int" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val value = parser.getAttributeValue(null, "value")?.toIntOrNull()
                                if (name != null && value != null) values[name] = value
                            }
                            "long" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val value = parser.getAttributeValue(null, "value")?.toLongOrNull()
                                if (name != null && value != null) values[name] = value
                            }
                            "float" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val value = parser.getAttributeValue(null, "value")?.toFloatOrNull()
                                if (name != null && value != null) values[name] = value
                            }
                            "string" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val value = parser.nextText()
                                if (name != null) values[name] = value
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        }
        return MemorySharedPreferences(values)
    }
}

private class MemorySharedPreferences(
    private val values: Map<String, Any?>,
) : SharedPreferences {
    override fun getAll(): Map<String, Any?> = values.toMap()
    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int =
        (values[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long =
        (values[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float =
        (values[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (values[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    private class MemoryEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }
}