import SwiftUI
import MapKit

struct NavigatorView: View {
    @ObservedObject var model: NavigatorModel
    var signIn: (() -> Void)?
    var close: (() -> Void)?
    @State private var account = false
    @State private var choosingScooter = false
    @State private var editedPlace: SavedPlace?
    @State private var editName = ""
    @State private var removePlace: SavedPlace?
    @State private var dismissPending = false
    @Environment(\.scenePhase) private var scenePhase
    private let brand = Color(red: 0.23, green: 0.23, blue: 0.60)

    var body: some View {
        NavigationStack {
            Group {
                if model.session == nil { welcome }
                else { library }
            }
            .navigationTitle("Sunshine Navigator")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let close { ToolbarItem(placement: .cancellationAction) { Button("Done", action: close) } }
                if model.session != nil { ToolbarItem(placement: .primaryAction) { Button("Account", systemImage: "person.crop.circle") { account = true } } }
            }
            .safeAreaInset(edge: .bottom) { feedback }
            .sheet(isPresented: $account) { accountView }
            .sheet(item: $model.selectedPlace) { place in DestinationView(model: model, place: place) }
            .alert("Edit favourite", isPresented: Binding(get: { editedPlace != nil }, set: { if !$0 { editedPlace = nil } })) {
                TextField("Name", text: $editName)
                Button("Save") { if let place = editedPlace { Task { await model.save(place.place, label: editName, existing: place) } }; editedPlace = nil }
                Button("Cancel", role: .cancel) { editedPlace = nil }
            }
            .confirmationDialog("Delete this saved place?", isPresented: Binding(get: { removePlace != nil }, set: { if !$0 { removePlace = nil } }), titleVisibility: .visible) {
                Button("Delete", role: .destructive) { if let place = removePlace { Task { await model.delete(place) } }; removePlace = nil }
            }
        }
        .tint(brand)
        .task { await model.activate() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.activate() } } }
    }
    private var welcome: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image("sunshine_logo").resizable().scaledToFit().frame(width: 90, height: 94).padding(.top, 32)
                Text("Your next stop, on your scooter.").font(.largeTitle.bold()).multilineTextAlignment(.center)
                Text("Choose a place, then tap a connected scooter to send it.").foregroundStyle(.secondary).multilineTextAlignment(.center)
                if let signIn {
                    Button("Continue with Sunshine", action: signIn).buttonStyle(.borderedProminent).disabled(model.busy).accessibilityIdentifier("sign-in")
                    DisclosureGroup("Connection settings") {
                        TextField("Sunshine server (HTTPS)", text: $model.server).textContentType(.URL).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("server")
                        TextField("Public OAuth client ID", text: $model.clientID).textInputAutocapitalization(.never).autocorrectionDisabled()
                    }.padding()
                } else { Text("Open Navigator and sign in first, then return to this share sheet. This place is saved for your next app launch.").multilineTextAlignment(.center) }
                if model.busy { ProgressView() }
            }.padding(24).frame(maxWidth: 600)
        }
    }
    private var library: some View {
        List {
            Section {
                HStack {
                    TextField("Search, paste a map link or coordinates", text: Binding(get: { model.query }, set: { model.query = $0; model.invalidateSearch() })).submitLabel(.search).autocorrectionDisabled().onSubmit { model.resolve() }
                    Button("Search", systemImage: "magnifyingglass") { model.resolve() }.labelStyle(.iconOnly).disabled(model.query.isEmpty)
                }
                if model.searching { ProgressView("Finding place…") }
                ForEach(model.results) { place in placeButton(place) }
            }
            if let pending = model.pending {
                Section("Unresolved request") {
                    Text("\(pending.place?.label ?? "Stop navigation") → \(pending.scooterName)").font(.headline)
                    Text("Delivery has not been confirmed. Checking status does not resend.").font(.footnote).foregroundStyle(.secondary)
                    Button("Check request status") { Task { await model.checkReceipt() } }.disabled(model.busy)
                    Button("Mark as reviewed", role: .destructive) { dismissPending = true }.disabled(model.busy)
                }.confirmationDialog("Dismiss the local warning?", isPresented: $dismissPending, titleVisibility: .visible) {
                    Button("Dismiss warning", role: .destructive) { Task { await model.acknowledgePending() } }
                } message: { Text("Check the scooter dashboard first. The command may still run; dismissing this warning does not cancel or resend it.") }
            }
            if model.loading { ProgressView("Loading your places…") }
            savedSection("Favourites", places: model.places.favorites)
            savedSection("Recent", places: model.places.recent)
            if !model.scooters.isEmpty {
                Section("Scooter places") {
                    Button { choosingScooter = true } label: {
                        HStack {
                            if let scooter = model.scooters.first(where: { $0.id == model.browseID }) { ScooterRow(scooter: scooter) }
                            else { Text("Choose scooter") }
                            Image(systemName: "chevron.down")
                        }
                    }.buttonStyle(.plain).popover(isPresented: $choosingScooter) {
                        ScrollView {
                            VStack(spacing: 8) {
                                ForEach(model.scooters) { scooter in
                                    Button {
                                        model.browseID = scooter.id; choosingScooter = false
                                        Task { await model.loadScooterPlaces() }
                                    } label: { ScooterRow(scooter: scooter, selected: model.browseID == scooter.id) }.buttonStyle(.plain)
                                }
                            }.padding()
                        }.frame(idealWidth: 330, maxWidth: 400, maxHeight: 360).presentationCompactAdaptation(.popover)
                    }
                    if let place = model.currentDestination {
                        LabeledContent("Current destination", value: place.label)
                        if let scooter = model.scooters.first(where: { $0.id == model.browseID && $0.available }) {
                            StopNavigationButton(model: model, scooter: scooter)
                        }
                    }
                    if model.scooterPlaces.isEmpty { Text("No saved places on this scooter.").foregroundStyle(.secondary) }
                    ForEach(model.scooterPlaces) { place in placeButton(place) }
                }
            }
            if !model.loading && model.places.favorites.isEmpty && model.places.recent.isEmpty && model.results.isEmpty {
                Text("Search for an address or share a location from your map app.").foregroundStyle(.secondary)
            }
        }.listStyle(.insetGrouped).refreshable { await model.refresh() }
    }
    private func savedSection(_ title: String, places: [SavedPlace]) -> some View {
        Section(title) {
            ForEach(places) { saved in
                placeButton(saved.place).contextMenu {
                    Button("Edit name") { editName = saved.label; editedPlace = saved }
                    Button(saved.isFavorite == true ? "Remove from favourites" : "Add to favourites") { Task { await model.save(saved.place, label: saved.label, existing: saved, favorite: saved.isFavorite != true) } }
                    Button("Delete", role: .destructive) { removePlace = saved }
                }
            }
        }
    }
    private func placeButton(_ place: Place) -> some View {
        Button { model.selectedPlace = place } label: {
            Label(place.label, systemImage: "mappin.circle").lineLimit(2, reservesSpace: true).foregroundStyle(.primary).padding(.vertical, 4)
        }
    }
    private var feedback: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let error = model.error { Text(error).foregroundStyle(.red).accessibilityIdentifier("error") }
            if let message = model.message { Text(message).foregroundStyle(.secondary).accessibilityIdentifier("feedback") }
        }.font(.footnote).padding(model.error == nil && model.message == nil ? 0 : 12).frame(maxWidth: .infinity).background(.regularMaterial)
    }
    private var accountView: some View {
        NavigationStack {
            Form {
                if let account = model.session?.account {
                    Section {
                        HStack {
                            if let avatar = account.avatarUrl.flatMap(httpsURL) { AsyncImage(url: avatar) { image in image.resizable().scaledToFill() } placeholder: { Image(systemName: "person.circle.fill").resizable() }.frame(width: 48, height: 48).clipShape(Circle()) }
                            VStack(alignment: .leading) { Text(account.displayName).font(.headline); Text(account.email).foregroundStyle(.secondary) }
                        }
                    }
                    Section("Preferred scooter") {
                        Button { model.prefer(nil) } label: { HStack { Text("Ask every time"); Spacer(); if model.preferredID == nil { Image(systemName: "checkmark") } } }
                        ForEach(model.scooters.filter(\.available)) { scooter in Button { model.prefer(scooter.id) } label: { ScooterRow(scooter: scooter, selected: model.preferredID == scooter.id) } }
                        DisclosureGroup("Unavailable scooters") {
                            ForEach(model.scooters.filter { !$0.available }) { scooter in ScooterRow(scooter: scooter) }
                        }
                    }
                    Section { Button("Sign out", role: .destructive) { accountViewSignOut() }.disabled(model.busy) }
                }
            }.navigationTitle("Account").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { account = false } } }
        }
    }
    private func accountViewSignOut() { account = false; Task { await model.signOut() } }
}

private struct StopNavigationButton: View {
    @ObservedObject var model: NavigatorModel
    let scooter: Scooter
    @State private var confirming = false
    var body: some View {
        Button("Stop navigation", role: .destructive) { confirming = true }.disabled(model.busy || model.pending != nil)
            .confirmationDialog("Stop navigation on \(scooter.name)?", isPresented: $confirming, titleVisibility: .visible) {
                Button("Stop navigation", role: .destructive) { Task { await model.send(to: scooter, place: nil) } }
            }
    }
}

private struct DestinationView: View {
    @ObservedObject var model: NavigatorModel
    let place: Place
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false
    @State private var name = ""
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Map(initialPosition: .region(MKCoordinateRegion(center: point, latitudinalMeters: 550, longitudinalMeters: 550)), interactionModes: []) {
                        Marker(place.label, coordinate: point).tint(.indigo)
                    }.mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll)).frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 18))
                    Text(place.label).font(.title2.bold())
                    Button("Save favourite", systemImage: "star") { name = place.label; saving = true }.disabled(model.session == nil)
                    if model.session == nil { Text("Sign in to Sunshine to send this place.") }
                    else {
                        Text("Send to scooter").font(.headline)
                        ForEach(model.sendableScooters) { scooter in
                            Button { Task { await model.send(to: scooter, place: place) } } label: { ScooterRow(scooter: scooter, selected: model.preferredID == scooter.id).padding(8).background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14)) }
                                .buttonStyle(.plain).disabled(model.busy || model.searching || model.pending != nil)
                        }
                        if model.sendableScooters.isEmpty { Text("No connected scooters are available for navigation.").foregroundStyle(.secondary) }
                    }
                    if model.busy { ProgressView("Sending…") }
                    if let pending = model.pending { Text("Check the unresolved request for \(pending.scooterName) before sending again."); Button("Check request status") { Task { await model.checkReceipt() } }.disabled(model.busy) }
                    if let error = model.error { Text(error).foregroundStyle(.red) }
                    if let message = model.message { Text(message).foregroundStyle(.secondary) }
                }.padding(20)
            }.navigationTitle("Destination").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                .alert("Save favourite", isPresented: $saving) {
                    TextField("Name", text: $name)
                    Button("Save") { Task { await model.save(place, label: name) } }
                    Button("Cancel", role: .cancel) {}
                }
        }.presentationDragIndicator(.visible)
    }
    private var point: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: place.coordinate.latitude, longitude: place.coordinate.longitude) }
}

struct ScooterRow: View {
    let scooter: Scooter
    var selected = false
    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: scooter.artwork) { image in image.resizable().scaledToFit() } placeholder: { Image("scooter").resizable().scaledToFit() }.frame(width: 96, height: 76)
            VStack(alignment: .leading, spacing: 5) {
                Text(scooter.name).font(.headline).foregroundStyle(.primary)
                HStack { Circle().fill(scooter.online == true ? .green : scooter.online == false ? .red : .orange).frame(width: 8, height: 8).accessibilityLabel(scooter.online == true ? "Online" : scooter.online == false ? "Offline" : "Connection unknown"); Text(scooter.stateLabel).font(.caption).foregroundStyle(.secondary) }
                if let plate = scooter.licensePlate, !plate.isEmpty { PlateBadge(plate: plate, kind: scooter.licensePlateType, recorded: scooter.licensePlateSetAt) }
            }
            Spacer(minLength: 0)
            if selected { Image(systemName: "checkmark").foregroundStyle(.indigo).accessibilityLabel("Preferred scooter") }
        }.frame(minHeight: 76)
    }
}
