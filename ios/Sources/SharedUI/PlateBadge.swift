import SwiftUI

struct PlateBadge: View {
    let plate: String
    let kind: String?
    let recorded: String?
    private var season: Int { PlateSeason.year(at: PlateSeason.date(recorded) ?? Date()) }
    private var expired: Bool { season < PlateSeason.year(at: Date()) }
    private var color: Color {
        switch season % 3 {
        case 0: return Color(red: 0.10, green: 0.43, blue: 0.10)
        case 1: return Color(white: 0.10)
        default: return Color(red: 0.10, green: 0.23, blue: 0.54)
        }
    }
    var body: some View {
        if kind == "versicherungskennzeichen", let values = insurance {
            HStack(spacing: 5) {
                VStack(spacing: -3) { Text(values[1]); Text(values[2]) }
                    .font(.custom("GL-Nummernschild-Eng", size: 20)).foregroundStyle(color).padding(.horizontal, 5).padding(.vertical, 2)
                    .background(.white).clipShape(RoundedRectangle(cornerRadius: 3)).overlay(RoundedRectangle(cornerRadius: 3).stroke(color, lineWidth: 2)).opacity(expired ? 0.6 : 1)
                if expired { Text("Expired").font(.caption2).foregroundStyle(.secondary) }
            }.accessibilityElement(children: .ignore).accessibilityLabel("Licence plate \(plate)\(expired ? ", expired" : "")")
        } else if kind == "zulassungskennzeichen", registration.count >= 2 {
            HStack(alignment: .top, spacing: 4) {
                Text("D").font(.system(size: 9, weight: .bold)).foregroundStyle(.white).padding(3).background(.blue)
                VStack(spacing: -2) {
                    Text(registration[0])
                    HStack(spacing: 2) {
                        Text(registration.dropFirst().dropLast(hasSuffix ? 1 : 0).joined(separator: " "))
                        if hasSuffix { Text(registration.last!).font(.system(size: 9, weight: .bold)).foregroundStyle(.white).padding(2).background(registration.last == "E" ? Color.green : .orange, in: RoundedRectangle(cornerRadius: 2)) }
                    }
                }.font(.custom("GL-Nummernschild-Eng", size: 18)).foregroundStyle(.black)
            }.padding(3).background(.white, in: RoundedRectangle(cornerRadius: 3)).overlay(RoundedRectangle(cornerRadius: 3).stroke(.black, lineWidth: 1)).accessibilityElement(children: .ignore).accessibilityLabel("Licence plate \(plate)")
        } else { Text(plate).font(.caption.monospaced()).foregroundStyle(.secondary) }
    }
    private var insurance: [String]? { PlaceInput.matches(#"^(\d{1,3})([A-Z]{1,3})$"#, plate.uppercased().replacingOccurrences(of: #"[\s·-]"#, with: "", options: .regularExpression)).first }
    private var registration: [String] { plate.uppercased().split(whereSeparator: { $0.isWhitespace }).map(String.init) }
    private var hasSuffix: Bool { registration.count >= 4 && ["E", "H"].contains(registration.last ?? "") }
}
