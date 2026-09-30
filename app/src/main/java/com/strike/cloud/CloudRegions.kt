package com.strike.cloud

import java.util.Locale

/**
 * BYD overseas country-to-server mapping, from hass-byd-vehicle const.py (MIT) via Overdrive.
 * Region "no" is the Middle East and Africa server; Norway is on "eu".
 */
internal object CloudRegions {

    private val regions: Map<String, String> = buildMap {
        fun add(region: String, vararg countries: String) = countries.forEach { put(it, region) }
        add("eu", "NO", "NL", "DE", "DK", "SE", "FR", "AT", "LU", "BE", "FI", "IT", "ES", "PT", "GB", "IE",
            "IS", "IL", "HU", "MT", "GR", "CH", "PL", "CY", "EE", "LV", "LT", "CZ", "RO", "SK", "SI", "BG",
            "HR", "LI", "ME", "RS", "BA", "MK", "AL", "MD", "MC", "VA", "XK", "UA")
        add("sg", "SG", "TH", "MY", "HK", "MO", "KH", "LA", "PH", "BN", "MM", "NP", "BD", "PK", "LK", "PF",
            "NC", "MN", "BT", "MV")
        add("au", "AU", "NZ")
        add("br", "BR")
        add("jp", "JP")
        add("uz", "UZ")
        add("no", "AE", "KW", "QA", "MA", "BH", "JO", "ZA", "RE", "MU", "EG")
        add("mx", "MX", "CL", "UY", "CO", "DO", "CR", "PE", "EC", "PY", "BO", "PA", "GT", "SV", "HN", "NI", "AR")
        add("id", "ID")
        add("tr", "TR")
        add("kr", "KR")
        add("in", "IN")
        add("vn", "VN")
        add("sa", "SA")
        add("om", "OM")
        add("kz", "KZ")
    }

    private val languages: Map<String, String> = buildMap {
        fun add(language: String, vararg countries: String) = countries.forEach { put(it, language) }
        add("es", "AR", "BO", "CL", "CO", "CR", "DO", "EC", "SV", "GT", "HN", "MX", "NI", "PA", "PY", "PE", "ES", "UY")
        add("ar", "BH", "EG", "JO", "KW", "MA", "OM", "QA", "SA", "AE")
        add("fr", "FR", "PF", "LU", "MC", "NC", "RE")
        add("de", "AT", "DE", "LI", "CH")
        add("pt", "BR", "PT")
        add("zh_TW", "HK", "MO")
        add("ru", "KZ", "MD", "UA", "UZ")
        add("id", "ID")
        add("he", "IL")
        add("it", "IT", "VA")
        add("ja", "JP")
        add("ko", "KR")
        add("nl", "NL")
        add("th", "TH")
        add("tr", "TR")
        add("vi", "VN")
    }

    val countries: List<String> get() = regions.keys.sortedBy { name(it) }

    fun supports(country: String): Boolean = country in regions

    fun region(country: String): String = regions[country] ?: "eu"

    fun language(country: String): String = languages[country] ?: "en"

    fun name(country: String): String = Locale("", country).getDisplayCountry(Locale.ENGLISH)
}
