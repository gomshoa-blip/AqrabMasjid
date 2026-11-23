import Foundation
import Adhan

enum Prayer: String, CaseIterable, Identifiable {
    case fajr, dhuhr, asr, maghrib, isha
    var id: String { rawValue }

    var ar: String {
        switch self {
        case .fajr: return "الفجر"
        case .dhuhr: return "الظهر"
        case .asr: return "العصر"
        case .maghrib: return "المغرب"
        case .isha: return "العشاء"
        }
    }
}

struct PrayerTime: Identifiable {
    let prayer: Prayer
    let adhan: Date
    let iqama: Date
    var id: String { prayer.rawValue + "\(adhan.timeIntervalSince1970)" }
}

struct DayTimes {
    let list: [PrayerTime]
    let methodName: String
    let ramadan: Bool
}

/// What to show now: the current prayer (30 min after adhan, or 5 min after iqama if later) or the next one.
struct PrayerStatus {
    let time: PrayerTime
    let isCurrent: Bool
}

/// Same rules as the Android app and the website.
enum PrayerCalc {
    /// Saudi fixed iqama (same as Al-Moazin app).
    static let saudiIqama: [Prayer: Int] = [.fajr: 25, .dhuhr: 20, .asr: 20, .maghrib: 10, .isha: 20]
    static let defaultIqama = 20

    static func isSaudi(lat: Double, lng: Double) -> Bool {
        if let code = Prefs.country { return code == "SA" }
        // Fallback: rough box around Saudi Arabia
        return (16.0...32.5).contains(lat) && (34.5...55.7).contains(lng)
    }

    static func iqamaMinutes(_ p: Prayer, lat: Double, lng: Double) -> Int {
        if let user = Prefs.iqama(p) { return user }
        return isSaudi(lat: lat, lng: lng) ? (saudiIqama[p] ?? 20) : defaultIqama
    }

    static func isRamadan(_ date: Date) -> Bool {
        let cal = Calendar(identifier: .islamicUmmAlQura)
        return cal.component(.month, from: date) == 9
    }

    static func hijriText(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .islamicUmmAlQura)
        f.locale = Locale(identifier: "ar_SA")
        f.dateFormat = "d MMMM y"
        return f.string(from: date) + " هـ"
    }

    private static func method(lat: Double, lng: Double) -> (CalculationMethod, String) {
        if isSaudi(lat: lat, lng: lng) { return (.ummAlQura, "أم القرى") }
        switch Prefs.country ?? "" {
        case "EG": return (.egyptian, "الهيئة المصرية العامة للمساحة")
        case "AE": return (.dubai, "دبي")
        case "KW": return (.kuwait, "الكويت")
        case "QA": return (.qatar, "قطر")
        case "PK": return (.karachi, "كراتشي")
        case "SG", "MY", "ID": return (.singapore, "سنغافورة")
        case "US", "CA": return (.northAmerica, "أمريكا الشمالية")
        default: return (.muslimWorldLeague, "رابطة العالم الإسلامي")
        }
    }

    /// Prayer times for the day that contains `day`.
    static func day(lat: Double, lng: Double, day: Date) -> DayTimes? {
        let (m, name) = method(lat: lat, lng: lng)
        var params = m.params
        params.madhab = .shafi
        let ramadan = isRamadan(day)
        // Saudi: in Ramadan, Isha is 2 hours after Maghrib
        if m == .ummAlQura && ramadan { params.ishaInterval = 120 }

        let comps = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day], from: day)
        guard let pt = PrayerTimes(coordinates: Coordinates(latitude: lat, longitude: lng),
                                   date: comps, calculationParameters: params) else { return nil }
        let adhans: [(Prayer, Date)] = [(.fajr, pt.fajr), (.dhuhr, pt.dhuhr), (.asr, pt.asr),
                                        (.maghrib, pt.maghrib), (.isha, pt.isha)]
        let list = adhans.map { p, d in
            PrayerTime(prayer: p, adhan: d,
                       iqama: d.addingTimeInterval(Double(iqamaMinutes(p, lat: lat, lng: lng)) * 60))
        }
        return DayTimes(list: list, methodName: name, ramadan: ramadan)
    }

    /// Yesterday + today + tomorrow (+ after tomorrow), sorted.
    static func around(lat: Double, lng: Double, now: Date = Date(), days: ClosedRange<Int> = -1...2) -> [PrayerTime] {
        days.flatMap { d -> [PrayerTime] in
            let date = now.addingTimeInterval(Double(d) * 86_400)
            return day(lat: lat, lng: lng, day: date)?.list ?? []
        }.sorted { $0.adhan < $1.adhan }
    }

    static func status(lat: Double, lng: Double, now: Date = Date()) -> PrayerStatus? {
        let all = around(lat: lat, lng: lng, now: now, days: -1...1)
        if let cur = all.last(where: { now >= $0.adhan &&
            now < max($0.adhan.addingTimeInterval(30 * 60), $0.iqama.addingTimeInterval(5 * 60)) }) {
            return PrayerStatus(time: cur, isCurrent: true)
        }
        guard let next = all.first(where: { $0.adhan > now }) else { return nil }
        return PrayerStatus(time: next, isCurrent: false)
    }

    static func fmtTime(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ar")
        f.dateFormat = "h:mm a"
        return f.string(from: d)
    }

    static func fmtLeft(_ seconds: TimeInterval) -> String {
        let totalMin = max(0, Int((seconds + 59) / 60))
        let h = totalMin / 60, m = totalMin % 60
        if h > 0 && m > 0 { return "\(h) س \(m) د" }
        if h > 0 { return "\(h) س" }
        return "\(m) د"
    }
}
