package com.aqrabmasjid.app

/** Change these links in one place. */
object Config {
    const val SITE_URL = "https://aqrab-masjid.pages.dev"

    /** Page where people suggest a missing mosque (Google Form or site form). */
    /** "Add a mosque" requests arrive by email at the app address. */
    const val FORM_URL = "https://formsubmit.co/ajax/aqrabmasjid.app@gmail.com"
    const val ADD_MOSQUE_URL = "https://aqrab-masjid.pages.dev/#add"

    /** Donate opens official platforms only. */
    const val DONATE_URL = "https://ehsan.sa"

    /**
     * Optional list of mosques you added yourself (from the requests you review).
     * Format: [{"name":"مسجد ...","lat":24.7,"lng":46.6}]
     * If the file does not exist, the app just skips it.
     */
    const val CUSTOM_MOSQUES_URL = "https://aqrab-masjid.pages.dev/mosques.json"

    val OVERPASS_URLS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    const val SHARE_TEXT =
        "تطبيق مجاني يذكرك بالصلاة ويدلك على أقرب مسجد وأنت تسوق. شاركه والدال على الخير كفاعله\n$SITE_URL"

    /** قيراط: Riyadh funeral prayers and cemeteries (shown in Riyadh only). */
    const val QIRAT_URL = "https://janaez-riyadh.netlify.app"

    /** Privacy policy page (needed for Google Play and App Store). */
    const val PRIVACY_URL = "https://aqrab-masjid.pages.dev/privacy/"

    /** About the project page. */
    const val ABOUT_URL = "https://aqrab-masjid.pages.dev/about/"

    /** WhatsApp contact. */
    const val WHATSAPP_URL = "https://wa.me/966556512678"

    /** Our simple mushaf page (King Fahd Complex text and Hafs font), shown full screen in the app. */
    const val QURAN_URL = "https://aqrab-masjid.pages.dev/quran/"

    /** Recommended Quran apps. */
    const val TARTEEL_PKG = "com.mmmoussa.iqra"
    const val WAHY_PKG = "com.logicdev.quran_reader"

    /** Minutes after adhan for the iqama alert. */
    const val IQAMA_ALERT_AFTER_MIN = 10
}
