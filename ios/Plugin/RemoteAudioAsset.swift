import AVFoundation

public class RemoteAudioAsset: AudioAsset {
    var playerItems: [AVPlayerItem] = []
    var players: [AVPlayer] = []
    var playerObservers: [NSKeyValueObservation] = []
    var duration: TimeInterval = 0
    var asset: AVURLAsset?

    override init(owner: NativeAudio, withAssetId assetId: String, withPath path: String!, withChannels channels: Int!, withVolume volume: Float!, withFadeDelay delay: Float!) {
        super.init(owner: owner, withAssetId: assetId, withPath: path, withChannels: channels, withVolume: volume, withFadeDelay: delay)

        if let url = URL(string: path) {
            // Create a single shared asset with caching
            let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
            self.asset = asset

            for _ in 0..<channels {
                let playerItem = AVPlayerItem(asset: asset)
                let player = AVPlayer(playerItem: playerItem)
                player.volume = volume
                self.playerItems.append(playerItem)
                self.players.append(player)

                // Add observer for duration
                let durationObserver = playerItem.observe(\.status) { [weak self] item, _ in
                    if item.status == .readyToPlay {
                        self?.duration = item.duration.seconds
                    }
                }
                self.playerObservers.append(durationObserver)

                // Add observer for playback finished
                let observer = player.observe(\.timeControlStatus) { [weak self] player, _ in
                    if player.timeControlStatus == .paused && player.currentItem?.currentTime() == player.currentItem?.duration {
                        self?.playerDidFinishPlaying(player: player)
                    }
                }
                self.playerObservers.append(observer)
            }
        }
    }

    func playerDidFinishPlaying(player: AVPlayer) {
        self.owner.notifyListeners("complete", data: [
            "assetId": self.assetId
        ])
    }

    override func play(time: TimeInterval, delay: TimeInterval) {
        guard !players.isEmpty else { return }
        let player = players[playIndex]
        if delay > 0 {
            let timeToPlay = CMTimeAdd(CMTimeMakeWithSeconds(player.currentTime().seconds, preferredTimescale: 1), CMTimeMakeWithSeconds(delay, preferredTimescale: 1))
            player.seek(to: timeToPlay)
        } else {
            player.seek(to: CMTimeMakeWithSeconds(time, preferredTimescale: 1))
        }
        player.play()
        playIndex = (playIndex + 1) % players.count
    }

    override func pause() {
        guard !players.isEmpty else { return }
        let player = players[playIndex]
        player.pause()
    }

    override func resume() {
        guard !players.isEmpty else { return }
        let player = players[playIndex]
        player.play()
    }

    override func stop() {
        for player in players {
            player.pause()
            player.seek(to: CMTime.zero)
        }
    }

    override func loop() {
        for player in players {
            // Set player to loop
            player.actionAtItemEnd = .none

            // Remove any existing notification observers first
            NotificationCenter.default.removeObserver(self,
                                                      name: .AVPlayerItemDidPlayToEndTime,
                                                      object: player.currentItem)

            // Add observer for looping
            NotificationCenter.default.addObserver(self,
                                                   selector: #selector(playerItemDidReachEnd(notification:)),
                                                   name: .AVPlayerItemDidPlayToEndTime,
                                                   object: player.currentItem)

            // Start playing
            player.seek(to: .zero)
            player.play()
        }
    }

    @objc func playerItemDidReachEnd(notification: Notification) {
        if let playerItem = notification.object as? AVPlayerItem,
           let player = players.first(where: { $0.currentItem == playerItem }) {
            player.seek(to: .zero)
            player.play()
        }
    }

    override func unload() {
        stop()
        NotificationCenter.default.removeObserver(self)
        // Remove KVO observers
        for observer in playerObservers {
            observer.invalidate()
        }
        playerObservers = []
        players = []
        playerItems = []
    }

    override func setVolume(volume: NSNumber!) {
        for player in players {
            player.volume = volume.floatValue
        }
    }

    override func setRate(rate: NSNumber!) {
        for player in players {
            player.rate = rate.floatValue
        }
    }

    override func isPlaying() -> Bool {
        guard !players.isEmpty else { return false }
        let player = players[playIndex]
        return player.timeControlStatus == .playing
    }

    override func getCurrentTime() -> TimeInterval {
        guard !players.isEmpty else { return 0 }
        let player = players[playIndex]
        // This will work for both regular files and streams
        return player.currentTime().seconds
    }

    override func getDuration() -> TimeInterval {
        // For infinite streams, duration will be CMTime.indefinite
        guard !players.isEmpty else { return 0 }
        let player = players[playIndex]
        if player.currentItem?.duration == CMTime.indefinite {
            return 0 // Or return -1 to indicate infinite stream
        }
        return player.currentItem?.duration.seconds ?? 0
    }

    static func clearCache() {
        let urls = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
        if let cachePath = urls.first {
            do {
                let fileURLs = try FileManager.default.contentsOfDirectory(at: cachePath, includingPropertiesForKeys: nil)
                for fileURL in fileURLs where fileURL.pathExtension == "mp3" || fileURL.pathExtension == "wav" {
                    try FileManager.default.removeItem(at: fileURL)
                }
            } catch {
                print("Error clearing audio cache: \(error)")
            }
        }
    }
}
