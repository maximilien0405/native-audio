import AVFoundation

/**
 * RemoteAudioAsset extends AudioAsset to handle remote (URL-based) audio files
 * Provides network audio playback using AVPlayer instead of AVAudioPlayer
 */
public class RemoteAudioAsset: AudioAsset {
    var playerItems: [AVPlayerItem] = []
    var players: [AVPlayer] = []
    var playerObservers: [NSKeyValueObservation] = []
    var notificationObservers: [NSObjectProtocol] = []
    var duration: TimeInterval = 0
    var asset: AVURLAsset?

    override init(owner: NativeAudio, withAssetId assetId: String, withPath path: String!, withChannels channels: Int!, withVolume volume: Float!, withFadeDelay delay: Float!) {
        super.init(owner: owner, withAssetId: assetId, withPath: path, withChannels: channels ?? 1, withVolume: volume ?? 1.0, withFadeDelay: delay ?? 0.0)

        owner.executeOnAudioQueue { [self] in
            guard let url = URL(string: path ?? "") else {
                print("Invalid URL: \(String(describing: path))")
                return
            }

            let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
            self.asset = asset

            // Limit channels to a reasonable maximum to prevent resource issues
            let channelCount = min(max(channels ?? Constant.DefaultChannels, 1), Constant.MaxChannels)

            for _ in 0..<channelCount {
                let playerItem = AVPlayerItem(asset: asset)
                let player = AVPlayer(playerItem: playerItem)
                // Apply volume constraints consistent with AudioAsset
                player.volume = self.initialVolume
                player.rate = 1.0
                self.playerItems.append(playerItem)
                self.players.append(player)

                // Add observer for duration
                let durationObserver = playerItem.observe(\.status) { [weak self] item, _ in
                    guard let strongSelf = self else { return }
                    strongSelf.owner?.executeOnAudioQueue {
                        if item.status == .readyToPlay {
                            strongSelf.duration = item.duration.seconds
                        }
                    }
                }
                self.playerObservers.append(durationObserver)

                // Add observer for playback finished
                let observer = player.observe(\.timeControlStatus) { [weak self, weak player] observedPlayer, _ in
                    guard let strongSelf = self,
                          let strongPlayer = player,
                          strongPlayer === observedPlayer else { return }

                    if strongPlayer.timeControlStatus == .paused &&
                        (strongPlayer.currentItem?.currentTime() == strongPlayer.currentItem?.duration ||
                            strongPlayer.currentItem?.duration == .zero) {
                        strongSelf.playerDidFinishPlaying(player: strongPlayer)
                    }
                }
                self.playerObservers.append(observer)
            }
        }
    }

    deinit {
        // Clean up observers
        for observer in playerObservers {
            observer.invalidate()
        }

        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }

        // Clean up players
        for player in players {
            player.pause()
        }

        playerItems = []
        players = []
        playerObservers = []
        notificationObservers = []
    }

    func playerDidFinishPlaying(player: AVPlayer) {
        owner?.executeOnAudioQueue { [self] in
            self.owner?.notifyListeners("complete", data: [
                "assetId": self.assetId
            ])
        }
    }

    override func play(time: TimeInterval, delay: TimeInterval) {
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty else { return }

            // Reset play index if it's out of bounds
            if playIndex >= players.count {
                playIndex = 0
            }

            let player = players[playIndex]

            // Ensure non-negative values for time and delay
            let validTime = max(time, 0)
            let validDelay = max(delay, 0)

            if validDelay > 0 {
                // Convert delay to CMTime and add to current time
                let currentTime = player.currentTime()
                let delayTime = CMTimeMakeWithSeconds(validDelay, preferredTimescale: currentTime.timescale)
                let timeToPlay = CMTimeAdd(currentTime, delayTime)
                player.seek(to: timeToPlay)
            } else {
                player.seek(to: CMTimeMakeWithSeconds(validTime, preferredTimescale: 1))
            }
            player.play()
            playIndex = (playIndex + 1) % players.count
            startCurrentTimeUpdates()
        }
    }

    override func pause() {
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else { return }

            let player = players[playIndex]
            player.pause()
            stopCurrentTimeUpdates()
        }
    }

    override func resume() {
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else { return }

            let player = players[playIndex]
            player.play()

            // Add notification observer for when playback stops
            cleanupNotificationObservers()

            let observer = NotificationCenter.default.addObserver(
                forName: NSNotification.Name.AVPlayerItemDidPlayToEndTime,
                object: player.currentItem,
                queue: nil) { [weak self] notification in
                guard let strongSelf = self else { return }

                if let currentItem = notification.object as? AVPlayerItem,
                   currentItem == strongSelf.playerItems[strongSelf.playIndex] {
                    strongSelf.playerDidFinishPlaying(player: strongSelf.players[strongSelf.playIndex])
                }
            }
            notificationObservers.append(observer)
            startCurrentTimeUpdates()
        }
    }

    override func stop() {
        owner?.executeOnAudioQueue { [self] in
            stopCurrentTimeUpdates()

            for player in players {
                // First pause
                player.pause()
                // Then reset to beginning
                player.seek(to: .zero, completionHandler: { _ in
                    // Reset any loop settings
                    player.actionAtItemEnd = .pause
                })
            }
            // Reset playback state
            playIndex = 0
        }
    }

    override func loop() {
        owner?.executeOnAudioQueue { [self] in
            cleanupNotificationObservers()

            for (index, player) in players.enumerated() {
                player.actionAtItemEnd = .none

                guard let playerItem = player.currentItem else { continue }

                let observer = NotificationCenter.default.addObserver(
                    forName: .AVPlayerItemDidPlayToEndTime,
                    object: playerItem,
                    queue: nil) { [weak player] notification in
                    guard let strongPlayer = player,
                          let item = notification.object as? AVPlayerItem,
                          strongPlayer.currentItem === item else { return }

                    strongPlayer.seek(to: .zero)
                    strongPlayer.play()
                }

                notificationObservers.append(observer)

                if index == playIndex {
                    player.seek(to: .zero)
                    player.play()
                }
            }

            startCurrentTimeUpdates()
        }
    }

    private func cleanupNotificationObservers() {
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        notificationObservers = []
    }

    @objc func playerItemDidReachEnd(notification: Notification) {
        owner?.executeOnAudioQueue { [self] in
            if let playerItem = notification.object as? AVPlayerItem,
               let player = players.first(where: { $0.currentItem == playerItem }) {
                player.seek(to: .zero)
                player.play()
            }
        }
    }

    override func unload() {
        owner?.executeOnAudioQueue { [self] in
            stopCurrentTimeUpdates()
            stop()

            cleanupNotificationObservers()

            // Remove KVO observers
            for observer in playerObservers {
                observer.invalidate()
            }
            playerObservers = []
            players = []
            playerItems = []
        }
    }

    override func setVolume(volume: NSNumber!) {
        owner?.executeOnAudioQueue { [self] in
            // Ensure volume is in valid range (0.0-1.0)
            let validVolume = min(max(volume.floatValue, Constant.MinVolume), Constant.MaxVolume)
            for player in players {
                player.volume = validVolume
            }
        }
    }

    override func setRate(rate: NSNumber!) {
        owner?.executeOnAudioQueue { [self] in
            // Ensure rate is in valid range
            let validRate = min(max(rate.floatValue, Constant.MinRate), Constant.MaxRate)
            for player in players {
                player.rate = validRate
            }
        }
    }

    override func isPlaying() -> Bool {
        var result = false
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else {
                result = false
                return
            }
            let player = players[playIndex]
            result = player.timeControlStatus == .playing
        }
        return result
    }

    override func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else {
                result = 0
                return
            }
            let player = players[playIndex]
            result = player.currentTime().seconds
        }
        return result
    }

    override func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else {
                result = 0
                return
            }
            let player = players[playIndex]
            if player.currentItem?.duration == CMTime.indefinite {
                result = 0
                return
            }
            result = player.currentItem?.duration.seconds ?? 0
        }
        return result
    }

    override func playWithFade(time: TimeInterval) {
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else { return }

            let player = players[playIndex]

            if player.timeControlStatus != .playing {
                player.seek(to: CMTimeMakeWithSeconds(time, preferredTimescale: 1))
                player.volume = 0 // Start with volume at 0
                player.play()
                playIndex = (playIndex + 1) % players.count
                startCurrentTimeUpdates()

                // Start fade-in using the parent class method
                startVolumeRamp(from: 0, to: initialVolume, player: player)
            } else {
                if player.volume < initialVolume {
                    // Continue fade-in if already in progress
                    startVolumeRamp(from: player.volume, to: initialVolume, player: player)
                }
            }
        }
    }

    override func stopWithFade() {
        owner?.executeOnAudioQueue { [self] in
            guard !players.isEmpty && playIndex < players.count else {
                stop()
                return
            }

            let player = players[playIndex]

            if player.timeControlStatus == .playing {
                // Use parent class fade method
                startVolumeRamp(from: player.volume, to: 0, player: player)

                // Schedule the stop when fade is complete
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(self.FADEDELAY * 1000))) { [weak self, weak player] in
                    guard let strongSelf = self, let strongPlayer = player else { return }

                    if strongPlayer.volume < strongSelf.FADESTEP {
                        strongSelf.stop()
                    }
                }
            } else {
                stop()
            }
        }
    }

    static func clearCache() {
        DispatchQueue.global(qos: .background).sync {
            let urls = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
            if let cachePath = urls.first {
                do {
                    let fileURLs = try FileManager.default.contentsOfDirectory(at: cachePath, includingPropertiesForKeys: nil)
                    // Clear all audio file types
                    let audioExtensions = ["mp3", "wav", "aac", "m4a", "ogg", "mp4", "caf", "aiff"]
                    for fileURL in fileURLs where audioExtensions.contains(fileURL.pathExtension.lowercased()) {
                        try FileManager.default.removeItem(at: fileURL)
                    }
                } catch {
                    print("Error clearing audio cache: \(error)")
                }
            }
        }
    }

    // Add helper method for parent class
    private func startVolumeRamp(from startVolume: Float, to endVolume: Float, player: AVPlayer) {
        player.volume = startVolume

        // Calculate steps
        let steps = abs(endVolume - startVolume) / FADESTEP
        guard steps > 0 else { return }

        let timeInterval = FADEDELAY / steps
        var currentStep = 0
        let totalSteps = Int(ceil(steps))

        stopFadeTimer()

        fadeTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(timeInterval), repeats: true) { [weak player] timer in
            guard let strongPlayer = player else {
                timer.invalidate()
                return
            }

            currentStep += 1
            let progress = Float(currentStep) / Float(totalSteps)
            let newVolume = startVolume + progress * (endVolume - startVolume)

            strongPlayer.volume = newVolume

            if currentStep >= totalSteps {
                strongPlayer.volume = endVolume
                timer.invalidate()
                self.fadeTimer = nil
            }
        }
        RunLoop.current.add(fadeTimer!, forMode: .common)
    }
}
