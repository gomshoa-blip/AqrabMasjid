import Foundation

/// Change these links in one place (same as the Android app).
enum Config {
    static let siteURL = URL(string: "https://masjid.gomshoa.com")!
    /// Page where people suggest a missing mosque.
    static let addMosqueURL = URL(string: "https://masjid.gomshoa.com/#add")!
    /// Donate opens the official Ehsan platform only. The app collects no money.
    static let donateURL = URL(string: "https://ehsan.sa")!
    /// قيراط: Riyadh funeral prayers and cemeteries (shown in Riyadh only).
    static let qiratURL = URL(string: "https://qirat.gomshoa.com")!
    /// Mosques you reviewed and added yourself. Format: [{"name":"مسجد ...","lat":24.7,"lng":46.6}]
    static let customMosquesURL = URL(string: "https://masjid.gomshoa.com/mosques.json")!

    static let overpassURLs = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    ]

    static let shareText = "تطبيق مجاني يذكرك بالصلاة ويدلك على أقرب مسجد وأنت تسوق. شاركه والدال على الخير كفاعله"

    /// Minutes after adhan for the iqama alert.
    static let iqamaAlertAfterMin = 10

    /// Riyadh center, for the قيراط button (about 70 km).
    static let riyadh = (lat: 24.7136, lng: 46.6753)
}
