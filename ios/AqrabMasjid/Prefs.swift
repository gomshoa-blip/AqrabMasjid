import Foundation

/// Small settings saved on the phone only.
enum Prefs {
    private static let d = UserDefaults.standard

    static var location: (lat: Double, lng: Double)? {
        get {
            guard d.object(forKey: "lat") != nil else { return nil }
            return (d.double(forKey: "lat"), d.double(forKey: "lng"))
        }
        set {
            if let v = newValue { d.set(v.lat, forKey: "lat"); d.set(v.lng, forKey: "lng") }
        }
    }

    /// ISO country code like "SA", "EG". Nil if not known yet.
    static var country: String? {
        get { d.string(forKey: "country") }
        set { d.set(newValue, forKey: "country") }
    }

    /// User iqama minutes for a prayer, or nil if not set.
    static func iqama(_ p: Prayer) -> Int? {
        d.object(forKey: "iq_" + p.rawValue) == nil ? nil : d.integer(forKey: "iq_" + p.rawValue)
    }

    static func setIqama(_ p: Prayer, _ min: Int) { d.set(min, forKey: "iq_" + p.rawValue) }

    static var alertsOn: Bool {
        get { d.object(forKey: "alerts") == nil ? true : d.bool(forKey: "alerts") }
        set { d.set(newValue, forKey: "alerts") }
    }
}
