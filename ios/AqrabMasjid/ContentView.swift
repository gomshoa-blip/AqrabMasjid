import SwiftUI
import UIKit
import CoreLocation

extension Color {
    static let brandGreen = Color(red: 15 / 255, green: 107 / 255, blue: 79 / 255)
    static let brandGold = Color(red: 201 / 255, green: 162 / 255, blue: 39 / 255)
    static let brandBg = Color(red: 245 / 255, green: 247 / 255, blue: 244 / 255)
    static let rowOn = Color(red: 227 / 255, green: 239 / 255, blue: 232 / 255)
}

struct ContentView: View {
    @StateObject private var lm = LocationManager()
    @Environment(\.scenePhase) private var phase
    @Environment(\.openURL) private var openURL

    @State private var now = Date()
    @State private var mosques: [Mosque]?
    @State private var searching = false
    @State private var searchFailed = false
    @State private var alertsOn = Prefs.alertsOn
    @State private var showIqama = false
    @State private var lastSearch: CLLocation?

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var loc: (lat: Double, lng: Double)? { Prefs.location }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                topCard
                BigButton(title: "🧭 خذني لأقرب مسجد", style: .gold) { goNearest() }
                BigButton(title: "📍 حدّث موقعي", style: .outline) { lm.refresh() }
                if inRiyadh {
                    BigButton(title: "🤲 قيراط – مواقع الجنائز والمقابر في الرياض", style: .outline) {
                        openURL(Config.qiratURL)
                    }
                }
                timesCard
                mosquesSection
                alertsSection
                BigButton(title: "📤 شارك التطبيق - الدال على الخير كفاعله", style: .green) { share() }
                BigButton(title: "➕ أضف مسجداً", style: .outline) { openURL(Config.addMosqueURL) }
                VStack(spacing: 4) {
                    BigButton(title: "💚 تبرّع عبر منصة إحسان", style: .outline) { openURL(Config.donateURL) }
                    Text("منصة إحسان الرسمية للعمل الخيري. التطبيق لا يجمع أي تبرعات.")
                        .font(.caption).foregroundColor(.secondary)
                }
                if lm.failed {
                    Text("اسمح للتطبيق بالموقع من الإعدادات لعرض المواقيت الدقيقة وأقرب المساجد.")
                        .font(.footnote).foregroundColor(.secondary).multilineTextAlignment(.center)
                    BigButton(title: "⚙️ فتح الإعدادات", style: .outline) {
                        if let u = URL(string: UIApplication.openSettingsURLString) { openURL(u) }
                    }
                }
            }
            .padding(16)
        }
        .background(Color.brandBg.ignoresSafeArea())
        .onAppear { start() }
        .onReceive(tick) { now = $0 }
        .onChange(of: lm.location) { newLoc in
            guard let l = newLoc else { return }
            if let last = lastSearch, last.distance(from: l) < 300, mosques != nil { return }
            lastSearch = l
            Task { await loadMosques() }
        }
        .onChange(of: phase) { p in
            if p == .active { lm.refresh(); Task { await Alerts.reschedule(nearest: mosques?.first) } }
        }
        .sheet(isPresented: $showIqama) {
            IqamaSettings { Task { await Alerts.reschedule(nearest: mosques?.first) } }
                .environment(\.layoutDirection, .rightToLeft)
        }
    }

    // MARK: - Top card

    /// Driver-first: one big card with only the coming prayer, readable at a glance.
    private var topCard: some View {
        VStack(spacing: 8) {
            if let l = loc, let st = PrayerCalc.status(lat: l.lat, lng: l.lng, now: now) {
                Text(st.isCurrent ? "حان الآن وقت" : "الصلاة القادمة")
                    .font(.title3.weight(.semibold)).foregroundColor(.white.opacity(0.9))
                Text(st.time.prayer.ar)
                    .font(.system(size: 64, weight: .heavy)).foregroundColor(.white)
                    .minimumScaleFactor(0.6).lineLimit(1)
                if st.isCurrent {
                    let toIqama = st.time.iqama.timeIntervalSince(now)
                    Text(toIqama > 0 ? "الإقامة بعد \(PrayerCalc.fmtLeft(toIqama))" : "أُقيمت الصلاة")
                        .font(.system(size: 40, weight: .bold)).foregroundColor(.brandGold)
                        .minimumScaleFactor(0.6).lineLimit(1)
                } else {
                    Text("بعد \(PrayerCalc.fmtLeft(st.time.adhan.timeIntervalSince(now)))")
                        .font(.system(size: 48, weight: .bold)).foregroundColor(.brandGold)
                        .minimumScaleFactor(0.6).lineLimit(1)
                }
                HStack(spacing: 18) {
                    labelTime("الأذان", st.time.adhan)
                    Rectangle().fill(Color.white.opacity(0.4)).frame(width: 1, height: 36)
                    labelTime("الإقامة", st.time.iqama)
                }
                .padding(.top, 4)
                Text(PrayerCalc.hijriText(now)).font(.footnote).foregroundColor(.white.opacity(0.75))
            } else {
                Text("حدّث موقعك لعرض المواقيت").font(.title2.bold()).foregroundColor(.white)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26).padding(.horizontal, 16)
        .background(Color.brandGreen)
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .accessibilityElement(children: .combine)
    }

    private func labelTime(_ title: String, _ d: Date) -> some View {
        VStack(spacing: 2) {
            Text(title).font(.subheadline).foregroundColor(.white.opacity(0.8))
            Text(PrayerCalc.fmtTime(d)).font(.title2.bold()).foregroundColor(.white)
        }
    }

    // MARK: - Times table

    private var timesCard: some View {
        VStack(spacing: 6) {
            if let l = loc, let day = PrayerCalc.day(lat: l.lat, lng: l.lng, day: now) {
                let hl = PrayerCalc.status(lat: l.lat, lng: l.lng, now: now)?.time.prayer
                HStack {
                    cell("الصلاة", header: true); cell("الأذان", header: true); cell("الإقامة", header: true)
                }
                ForEach(day.list) { t in
                    HStack {
                        cell(t.prayer.ar, bold: t.prayer == hl)
                        cell(PrayerCalc.fmtTime(t.adhan), bold: t.prayer == hl)
                        cell(PrayerCalc.fmtTime(t.iqama), bold: t.prayer == hl)
                    }
                    .padding(.vertical, 8)
                    .background(t.prayer == hl ? Color.rowOn : Color.clear)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                Text("طريقة الحساب: \(day.methodName)").font(.caption).foregroundColor(.secondary).padding(.top, 4)
                if !PrayerCalc.isSaudi(lat: l.lat, lng: l.lng) {
                    Button("⚙️ وقت الإقامة لكل صلاة") { showIqama = true }
                        .font(.footnote).foregroundColor(.brandGreen)
                }
            }
        }
        .padding(10)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(white: 0.88), lineWidth: 1))
    }

    private func cell(_ s: String, header: Bool = false, bold: Bool = false) -> some View {
        Text(s)
            .font(header ? .footnote : (bold ? .body.bold() : .body))
            .foregroundColor(header ? .secondary : .primary)
            .frame(maxWidth: .infinity)
    }

    // MARK: - Mosques

    private var mosquesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("أقرب المساجد").font(.title3.bold()).foregroundColor(.brandGreen)
            if searching && mosques == nil {
                HStack { ProgressView(); Text("جاري البحث...").foregroundColor(.secondary) }
            } else if let list = mosques, !list.isEmpty {
                ForEach(list.prefix(3)) { m in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.name).font(.body)
                            Text(MosqueRepo.fmtDistance(m.distKm)).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Button("خذني") { navigate(to: m) }
                            .font(.body.bold()).foregroundColor(.white)
                            .padding(.horizontal, 18).padding(.vertical, 9)
                            .background(Color.brandGreen).clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Color.white).clipShape(RoundedRectangle(cornerRadius: 18))
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(white: 0.88), lineWidth: 1))
                }
            } else if mosques != nil || searchFailed {
                Text(searchFailed ? "ما قدرنا نجيب المساجد الحين. اضغط «حدّث موقعي» للمحاولة مرة ثانية."
                                  : "ما لقينا مساجد قريبة. اعرضها في الخرائط، أو أضف مسجداً.")
                    .font(.footnote).foregroundColor(.secondary)
                BigButton(title: "🔎 اعرض المساجد القريبة في الخرائط", style: .outline) { searchInMaps() }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 6)
    }

    // MARK: - Alerts

    private var alertsSection: some View {
        Toggle(isOn: $alertsOn) {
            Text("تنبيه عند الأذان، وبعده بـ 10 دقائق للإقامة").font(.body)
        }
        .tint(.brandGold)
        .padding(.top, 8)
        .onChange(of: alertsOn) { on in
            Prefs.alertsOn = on
            Task {
                if on { _ = await Alerts.requestPermission() }
                await Alerts.reschedule(nearest: mosques?.first)
            }
        }
    }

    // MARK: - Actions

    private var inRiyadh: Bool {
        guard lm.location != nil, let l = loc else { return false }
        return MosqueRepo.distanceKm(l.lat, l.lng, Config.riyadh.lat, Config.riyadh.lng) < 70
    }

    private func start() {
        lm.refresh()
        if loc != nil { Task { await loadMosques() } }
        Task {
            if Prefs.alertsOn { _ = await Alerts.requestPermission() }
            await Alerts.reschedule(nearest: mosques?.first)
        }
    }

    private func loadMosques() async {
        guard let l = loc else { return }
        searching = true; searchFailed = false
        let result = await MosqueRepo.nearby(lat: l.lat, lng: l.lng)
        searching = false
        if let r = result { mosques = r } else { searchFailed = true; mosques = nil }
        await Alerts.reschedule(nearest: mosques?.first)
    }

    private func goNearest() {
        if let m = mosques?.first { navigate(to: m) } else { searchInMaps() }
    }

    private func navigate(to m: Mosque) {
        if let g = URL(string: "comgooglemaps://?daddr=\(m.lat),\(m.lng)&directionsmode=driving"),
           UIApplication.shared.canOpenURL(g) {
            openURL(g)
        } else if let a = URL(string: "http://maps.apple.com/?daddr=\(m.lat),\(m.lng)&dirflg=d") {
            openURL(a)
        }
    }

    private func searchInMaps() {
        let q = "مسجد".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "masjid"
        let near = loc.map { "&sll=\($0.lat),\($0.lng)" } ?? ""
        if let u = URL(string: "http://maps.apple.com/?q=\(q)\(near)") { openURL(u) }
    }

    private func share() {
        let items: [Any] = [Config.shareText + "\n" + Config.siteURL.absoluteString]
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        guard let root = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        vc.popoverPresentationController?.sourceView = root.view
        root.present(vc, animated: true)
    }
}

// MARK: - Big button

struct BigButton: View {
    enum Style { case gold, green, outline }
    let title: String
    let style: Style
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(style == .outline ? .body : (style == .gold ? .title3.bold() : .body.bold()))
                .foregroundColor(style == .gold ? Color(white: 0.12) : (style == .green ? .white : .brandGreen))
                .frame(maxWidth: .infinity, minHeight: style == .gold ? 64 : 54)
                .background(style == .gold ? Color.brandGold : (style == .green ? Color.brandGreen : Color.white))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14)
                    .stroke(style == .outline ? Color.brandGreen : Color.clear, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Iqama settings (outside Saudi Arabia)

struct IqamaSettings: View {
    var onSave: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var values: [Prayer: Int] = [:]
    private let options = [5, 10, 15, 20, 25, 30, 40]

    var body: some View {
        NavigationView {
            Form {
                Section(footer: Text("كم دقيقة بعد الأذان تقام الصلاة في مسجدك؟")) {
                    ForEach(Prayer.allCases) { p in
                        Picker(p.ar, selection: Binding(
                            get: { values[p] ?? Prefs.iqama(p) ?? PrayerCalc.defaultIqama },
                            set: { values[p] = $0 })) {
                            ForEach(options, id: \.self) { Text("\($0) د").tag($0) }
                        }
                    }
                }
            }
            .navigationTitle("وقت الإقامة")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("حفظ") {
                        for (p, v) in values { Prefs.setIqama(p, v) }
                        onSave(); dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
            }
        }
    }
}
