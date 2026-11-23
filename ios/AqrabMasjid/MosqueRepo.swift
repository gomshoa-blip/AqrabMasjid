import Foundation

struct Mosque: Identifiable, Hashable {
    let name: String
    let lat: Double
    let lng: Double
    var distKm: Double
    var id: String { "\(lat),\(lng)" }
}

/// Finds mosques from OpenStreetMap by place type (so مصلى and دار عبادة show too),
/// plus your own reviewed list. Same method as the website: ask all servers at once,
/// take the first good answer, and stop after about 20 seconds.
enum MosqueRepo {
    static func nearby(lat: Double, lng: Double) async -> [Mosque]? {
        async let custom = (try? await queryCustom(lat: lat, lng: lng)) ?? []
        var osm: [Mosque]? = await queryOsm(lat: lat, lng: lng, radius: 3000)
        if (osm?.count ?? 0) < 3, let wider = await queryOsm(lat: lat, lng: lng, radius: 10000) {
            osm = wider
        }
        let mine = await custom
        if osm == nil && mine.isEmpty { return nil }
        return Array(dedupe((osm ?? []) + mine).sorted { $0.distKm < $1.distKm }.prefix(10))
    }

    private static func queryOsm(lat: Double, lng: Double, radius: Int) async -> [Mosque]? {
        let a = "(around:\(radius),\(lat),\(lng))"
        let q = "[out:json][timeout:12];(" +
            "node[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]\(a);" +
            "way[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]\(a);" +
            "way[\"building\"=\"mosque\"]\(a);" +
            ");out center 80;"
        guard let enc = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return nil }

        return await withTaskGroup(of: [Mosque]?.self) { group in
            for base in Config.overpassURLs {
                group.addTask {
                    guard let url = URL(string: base + "?data=" + enc) else { return nil }
                    var req = URLRequest(url: url, timeoutInterval: 12)
                    req.setValue("AqrabMasjid-iOS/1.1", forHTTPHeaderField: "User-Agent")
                    guard let res = try? await URLSession.shared.data(for: req),
                          (res.1 as? HTTPURLResponse)?.statusCode == 200 else { return nil }
                    return parse(res.0, lat: lat, lng: lng)
                }
            }
            var gotEmpty = false
            for await result in group {
                guard let list = result else { continue }
                // A server sometimes answers with nothing: wait for the others first
                if !list.isEmpty { group.cancelAll(); return list }
                gotEmpty = true
            }
            return gotEmpty ? [] : nil
        }
    }

    private static func parse(_ data: Data, lat: Double, lng: Double) -> [Mosque]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let els = root["elements"] as? [[String: Any]] else { return nil }
        return els.compactMap { e in
            let center = e["center"] as? [String: Any]
            guard let la = (e["lat"] as? Double) ?? (center?["lat"] as? Double),
                  let lo = (e["lon"] as? Double) ?? (center?["lon"] as? Double) else { return nil }
            let tags = e["tags"] as? [String: Any]
            let name = (tags?["name:ar"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? (tags?["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "مسجد"
            return Mosque(name: name, lat: la, lng: lo, distKm: distanceKm(lat, lng, la, lo))
        }
    }

    private static func queryCustom(lat: Double, lng: Double) async throws -> [Mosque] {
        var req = URLRequest(url: Config.customMosquesURL, timeoutInterval: 6)
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let arr = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            guard let la = o["lat"] as? Double, let lo = o["lng"] as? Double else { return nil }
            let d = distanceKm(lat, lng, la, lo)
            guard d <= 15 else { return nil }
            let name = (o["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "مسجد"
            return Mosque(name: name, lat: la, lng: lo, distKm: d)
        }
    }

    /// Same place listed twice (within ~40 m): keep one, prefer a real name.
    private static func dedupe(_ list: [Mosque]) -> [Mosque] {
        var out: [Mosque] = []
        for m in list.sorted(by: { ($0.name == "مسجد" ? 1 : 0) < ($1.name == "مسجد" ? 1 : 0) }) {
            if !out.contains(where: { distanceKm($0.lat, $0.lng, m.lat, m.lng) < 0.04 }) { out.append(m) }
        }
        return out
    }

    static func distanceKm(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let r = 6371.0
        let dLat = (lat2 - lat1) * .pi / 180, dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    static func fmtDistance(_ km: Double) -> String {
        km < 1 ? "\(Int(km * 1000)) م" : String(format: "%.1f كم", km)
    }
}
