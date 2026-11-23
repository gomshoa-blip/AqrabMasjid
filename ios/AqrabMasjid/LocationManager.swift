import Foundation
import CoreLocation

/// Gets the user's location once (and the country, for the calculation method).
@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var status: CLAuthorizationStatus = .notDetermined
    @Published var location: CLLocation?
    @Published var failed = false

    private let manager = CLLocationManager()
    private var asked = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        status = manager.authorizationStatus
    }

    func refresh() {
        failed = false
        switch manager.authorizationStatus {
        case .notDetermined:
            asked = true
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            failed = true
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        Task { @MainActor in
            self.status = m.authorizationStatus
            if m.authorizationStatus == .authorizedWhenInUse || m.authorizationStatus == .authorizedAlways {
                m.requestLocation()
            } else if m.authorizationStatus == .denied || m.authorizationStatus == .restricted {
                self.failed = true
            }
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let loc = locs.last else { return }
        Task { @MainActor in
            Prefs.location = (loc.coordinate.latitude, loc.coordinate.longitude)
            self.location = loc
            if let pm = try? await CLGeocoder().reverseGeocodeLocation(loc).first, let code = pm.isoCountryCode {
                Prefs.country = code
            }
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.failed = true }
    }
}
