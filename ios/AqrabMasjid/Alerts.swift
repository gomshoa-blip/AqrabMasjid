import Foundation
import UserNotifications

/// Two alerts per prayer: at adhan, and 10 minutes later for iqama (not for Jumuah).
/// iPhone allows 64 waiting alerts, so we plan about 6 days ahead and refresh
/// every time the app opens. Alerts work even when the app is closed.
enum Alerts {
    static let soundName = UNNotificationSoundName("prayer_chime.wav")
    private static let maxPending = 60

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func reschedule(nearest: Mosque?) async {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        guard Prefs.alertsOn, let loc = Prefs.location else { return }
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }

        let now = Date()
        var events: [(Date, String, PrayerTime)] = []
        for t in PrayerCalc.around(lat: loc.lat, lng: loc.lng, now: now, days: 0...6) {
            events.append((t.adhan, "adhan", t))
            if !isJumuah(t) {
                events.append((t.adhan.addingTimeInterval(Double(Config.iqamaAlertAfterMin) * 60), "iqama", t))
            }
        }
        events = events.filter { $0.0 > now.addingTimeInterval(5) }.sorted { $0.0 < $1.0 }
        for (at, type, t) in events.prefix(maxPending) {
            let content = UNMutableNotificationContent()
            if type == "iqama" {
                let left = Int((t.iqama.timeIntervalSince(at) / 60).rounded())
                content.title = left > 0 ? "إقامة \(t.prayer.ar) بعد \(left) د" : "حان وقت إقامة \(t.prayer.ar)"
            } else {
                content.title = "حان الآن وقت أذان \(t.prayer.ar)"
            }
            var body = "اضغط للذهاب لأقرب مسجد"
            if let m = nearest, MosqueRepo.distanceKm(loc.lat, loc.lng, m.lat, m.lng) <= 5 {
                body = "أقرب مسجد: \(m.name) على بعد \(MosqueRepo.fmtDistance(m.distKm))"
            }
            content.body = body + " · الإقامة \(PrayerCalc.fmtTime(t.iqama))"
            content.sound = UNNotificationSound(named: soundName)
            content.threadIdentifier = "prayer"

            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: at)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            let id = "\(type)-\(t.prayer.rawValue)-\(Int(at.timeIntervalSince1970))"
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
    }

    /// Friday Dhuhr = Jumuah: there is a khutbah, so no iqama alert.
    private static func isJumuah(_ t: PrayerTime) -> Bool {
        t.prayer == .dhuhr && Calendar.current.component(.weekday, from: t.adhan) == 6
    }
}
